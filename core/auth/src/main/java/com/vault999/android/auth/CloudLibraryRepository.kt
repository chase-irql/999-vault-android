package com.vault999.android.auth

import com.vault999.android.database.CloudLibraryDao
import com.vault999.android.database.CloudLibraryStateEntity
import com.vault999.android.database.CloudLikeEntity
import com.vault999.android.database.CloudMutationEntity
import com.vault999.android.database.CloudPlaylistEntity
import com.vault999.android.database.CloudPlaylistSongEntity
import com.vault999.android.database.LibraryDao
import com.vault999.android.model.CloudLike
import com.vault999.android.model.CloudLikesSnapshot
import com.vault999.android.model.CloudPlaylist
import com.vault999.android.model.CloudSyncState
import com.vault999.android.model.Ownership
import com.vault999.android.model.Playlist
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class CloudFlushSummary(
    val acknowledged: Int,
    val deferred: Int,
    val conflicts: Int,
)

/** Durable optimistic library semantics. UI code supplies the currently projected account ID. */
class CloudLibraryRepository(
    private val store: CloudLibraryStore,
    private val sessions: AuthSessionManager,
    private val transport: AccountCloudTransport,
    private val nowEpochMs: () -> Long,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
) {
    private val flushMutex = Mutex()

    suspend fun projection(activeAccountId: String?): CloudLibraryProjection = store.projection(activeAccountId)

    suspend fun refresh(activeAccountId: String): Boolean {
        val access = sessions.accessSession() as? SessionAccess.Granted ?: return false
        if (access.session.account.id != activeAccountId) return false
        val likes = access.session.useAccessTokenSuspending { transport.likes(it) }
        val playlists = access.session.useAccessTokenSuspending { transport.playlists(it) }
        if (likes is CloudCallResult.Success) store.replaceLikes(activeAccountId, likes.value, nowEpochMs())
        if (playlists is CloudCallResult.Success) store.replacePlaylists(activeAccountId, playlists.value)
        return likes is CloudCallResult.Success && playlists is CloudCallResult.Success
    }

    suspend fun setLike(activeAccountId: String, songId: Long, liked: Boolean, idempotencyKey: String = newUuid()): String {
        require(songId > 0)
        val now = nowEpochMs()
        store.enqueueLike(
            CloudMutation(
                idempotencyKey = idempotencyKey,
                accountId = activeAccountId,
                operation = CloudMutationOperation.SET_LIKE,
                songId = songId,
                desired = liked,
                nextAttemptAtEpochMs = now,
                createdAtEpochMs = now,
            ),
        )
        return idempotencyKey
    }

    suspend fun createPlaylist(
        activeAccountId: String,
        name: String,
        description: String = "",
        idempotencyKey: String = newUuid(),
        clientMigrationId: String = newUuid(),
    ): String {
        require(CloudMutation.validName(name.trim()) && CloudMutation.validDescription(description.trim()))
        val now = nowEpochMs()
        val localId = "pending-${newUuid()}"
        val mutation = CloudMutation(
            idempotencyKey = idempotencyKey,
            accountId = activeAccountId,
            operation = CloudMutationOperation.CREATE_PLAYLIST,
            playlistLocalId = localId,
            name = name.trim(),
            description = description.trim(),
            clientMigrationId = clientMigrationId,
            nextAttemptAtEpochMs = now,
            createdAtEpochMs = now,
        )
        store.enqueuePlaylist(
            mutation,
            CloudPlaylist(localId, clientMigrationId, mutation.name!!, mutation.description.orEmpty(), null, emptyList(), "pending", now, now, CloudSyncState.PENDING),
        )
        return localId
    }

    suspend fun setPlaylistSong(
        activeAccountId: String,
        localId: String,
        songId: Long,
        included: Boolean,
        idempotencyKey: String = newUuid(),
    ) {
        require(songId > 0)
        val current = requireNotNull(store.playlist(localId)) { "Unknown playlist" }
        val now = nowEpochMs()
        val songs = current.songIds.toMutableList().apply {
            if (included && songId !in this) add(songId)
            if (!included) remove(songId)
        }
        val mutation = CloudMutation(
            idempotencyKey = idempotencyKey,
            accountId = activeAccountId,
            operation = CloudMutationOperation.SET_PLAYLIST_SONG,
            playlistLocalId = localId,
            playlistCloudId = current.id.takeUnless { it.startsWith("pending-") },
            songId = songId,
            desired = included,
            baseRevision = current.revision.takeUnless { it == "pending" },
            nextAttemptAtEpochMs = now,
            createdAtEpochMs = now,
        )
        store.enqueuePlaylist(mutation, current.copy(songIds = songs, syncState = CloudSyncState.PENDING, updatedAtEpochMs = now))
    }

    suspend fun updatePlaylist(
        activeAccountId: String,
        localId: String,
        name: String? = null,
        description: String? = null,
        idempotencyKey: String = newUuid(),
    ) {
        require(name != null || description != null) { "At least one playlist field is required" }
        val current = requireNotNull(store.playlist(localId)) { "Unknown playlist" }
        val nextName = name?.trim() ?: current.name
        val nextDescription = description?.trim() ?: current.description
        require(CloudMutation.validName(nextName) && CloudMutation.validDescription(nextDescription))
        val now = nowEpochMs()
        val mutation = CloudMutation(
            idempotencyKey,
            activeAccountId,
            CloudMutationOperation.UPDATE_PLAYLIST,
            playlistLocalId = localId,
            playlistCloudId = current.id.takeUnless { it.startsWith("pending-") },
            name = name?.trim(),
            description = description?.trim(),
            baseRevision = current.revision.takeUnless { it == "pending" },
            nextAttemptAtEpochMs = now,
            createdAtEpochMs = now,
        )
        store.enqueuePlaylist(
            mutation,
            current.copy(name = nextName, description = nextDescription, syncState = CloudSyncState.PENDING, updatedAtEpochMs = now),
        )
    }

    suspend fun deletePlaylist(
        activeAccountId: String,
        localId: String,
        idempotencyKey: String = newUuid(),
    ) {
        val current = requireNotNull(store.playlist(localId)) { "Unknown playlist" }
        val now = nowEpochMs()
        store.enqueuePlaylist(
            CloudMutation(
                idempotencyKey,
                activeAccountId,
                CloudMutationOperation.DELETE_PLAYLIST,
                playlistLocalId = localId,
                playlistCloudId = current.id.takeUnless { it.startsWith("pending-") },
                nextAttemptAtEpochMs = now,
                createdAtEpochMs = now,
            ),
            current.copy(syncState = CloudSyncState.PENDING, updatedAtEpochMs = now),
        )
    }

    suspend fun reorderPlaylist(
        activeAccountId: String,
        localId: String,
        orderedSongIds: List<Long>,
        idempotencyKey: String = newUuid(),
    ) {
        val current = requireNotNull(store.playlist(localId)) { "Unknown playlist" }
        require(orderedSongIds.toSet() == current.songIds.toSet() && orderedSongIds.size == current.songIds.size) {
            "Reorder must contain every playlist song exactly once"
        }
        val now = nowEpochMs()
        val mutation = CloudMutation(
            idempotencyKey,
            activeAccountId,
            CloudMutationOperation.REORDER_PLAYLIST,
            playlistLocalId = localId,
            playlistCloudId = current.id.takeUnless { it.startsWith("pending-") },
            songIds = orderedSongIds,
            baseRevision = current.revision.takeUnless { it == "pending" },
            nextAttemptAtEpochMs = now,
            createdAtEpochMs = now,
        )
        store.enqueuePlaylist(mutation, current.copy(songIds = orderedSongIds, syncState = CloudSyncState.PENDING, updatedAtEpochMs = now))
    }

    suspend fun flushReady(activeAccountId: String): CloudFlushSummary = flushMutex.withLock {
        val access = sessions.accessSession() as? SessionAccess.Granted ?: return@withLock CloudFlushSummary(0, 0, 0)
        if (access.session.account.id != activeAccountId) return@withLock CloudFlushSummary(0, 0, 0)
        val ready = store.readyMutations(activeAccountId, nowEpochMs(), 50)
        val outcomes = coroutineScope {
            ready.groupBy(CloudMutation::subjectKey).values.map { subject ->
                async {
                    subject.sortedWith(compareBy(CloudMutation::createdAtEpochMs, CloudMutation::idempotencyKey)).map { queued ->
                        val current = store.mutation(queued.idempotencyKey) ?: return@map Outcome.ACKNOWLEDGED
                        flushOne(current, access.session)
                    }
                }
            }.awaitAll().flatten()
        }
        CloudFlushSummary(
            outcomes.count { it == Outcome.ACKNOWLEDGED },
            outcomes.count { it == Outcome.DEFERRED },
            outcomes.count { it == Outcome.CONFLICT },
        )
    }

    private suspend fun flushOne(mutation: CloudMutation, initialSession: AccountSession): Outcome {
        if (mutation.playlistLocalId != null && mutation.operation != CloudMutationOperation.CREATE_PLAYLIST && mutation.playlistCloudId == null) {
            store.reschedule(mutation, "awaiting_playlist_create", nowEpochMs() + 1_000)
            return Outcome.DEFERRED
        }
        var session = initialSession
        var result = session.useAccessTokenSuspending { transport.execute(it, mutation) }
        if (result is CloudCallResult.Unauthorized) {
            val refreshed = sessions.refreshAfterUnauthorized(session) as? SessionAccess.Granted
            if (refreshed == null || refreshed.session.account.id != mutation.accountId) {
                store.reschedule(mutation, "unauthorized", Long.MAX_VALUE)
                return Outcome.DEFERRED
            }
            session = refreshed.session
            result = session.useAccessTokenSuspending { transport.execute(it, mutation) }
        }
        return when (result) {
            is CloudCallResult.Success -> {
                store.acknowledge(mutation, result.value, nowEpochMs())
                Outcome.ACKNOWLEDGED
            }
            CloudCallResult.Conflict -> resolveConflict(mutation, session)
            is CloudCallResult.Retryable -> {
                store.reschedule(mutation, result.code, retryAt(mutation, result.retryAfterSeconds))
                Outcome.DEFERRED
            }
            CloudCallResult.Unauthorized -> {
                store.reschedule(mutation, "unauthorized", Long.MAX_VALUE)
                Outcome.DEFERRED
            }
            is CloudCallResult.Rejected -> {
                if (mutation.operation == CloudMutationOperation.DELETE_PLAYLIST && result.status == 404) {
                    store.acknowledge(mutation, null, nowEpochMs())
                    Outcome.ACKNOWLEDGED
                } else {
                    store.reschedule(mutation, result.code, Long.MAX_VALUE)
                    Outcome.DEFERRED
                }
            }
        }
    }

    private suspend fun resolveConflict(mutation: CloudMutation, session: AccountSession): Outcome {
        val cloudId = mutation.playlistCloudId ?: return Outcome.CONFLICT
        val fetched = session.useAccessTokenSuspending { transport.playlist(it, cloudId) }
        val remote = (fetched as? CloudCallResult.Success)?.value ?: run {
            store.reschedule(mutation, "conflict_refresh_failed", retryAt(mutation, null))
            return Outcome.DEFERRED
        }
        if (mutation.operation != CloudMutationOperation.SET_PLAYLIST_SONG) {
            store.markConflict(mutation, remote, nowEpochMs())
            return Outcome.CONFLICT
        }
        val desiredPresent = mutation.songId in remote.songIds
        if (desiredPresent == mutation.desired) {
            store.acknowledge(mutation, remote, nowEpochMs())
            return Outcome.ACKNOWLEDGED
        }
        val rebased = mutation.rebased(newUuid(), remote.revision, nowEpochMs())
        store.rebase(mutation, rebased, remote)
        return when (val retried = session.useAccessTokenSuspending { transport.execute(it, rebased) }) {
            is CloudCallResult.Success -> {
                store.acknowledge(rebased, retried.value, nowEpochMs())
                Outcome.ACKNOWLEDGED
            }
            CloudCallResult.Conflict -> {
                store.markConflict(rebased, remote, nowEpochMs())
                Outcome.CONFLICT
            }
            is CloudCallResult.Retryable -> {
                store.reschedule(rebased, retried.code, retryAt(rebased, retried.retryAfterSeconds))
                Outcome.DEFERRED
            }
            else -> {
                store.reschedule(rebased, "rebase_rejected", Long.MAX_VALUE)
                Outcome.DEFERRED
            }
        }
    }

    private fun retryAt(mutation: CloudMutation, retryAfterSeconds: Long?): Long {
        val delay = retryAfterSeconds?.coerceIn(1, 86_400)?.times(1_000)
            ?: (1_000L shl mutation.attempt.coerceIn(0, 6)).coerceAtMost(60_000)
        return (nowEpochMs() + delay).coerceAtLeast(0)
    }

    private enum class Outcome { ACKNOWLEDGED, DEFERRED, CONFLICT }
}

/** Room adapter. Cloud rows are account-scoped; local rows are never cleared or hidden on logout. */
class RoomCloudLibraryStore(
    private val cloud: CloudLibraryDao,
    private val local: LibraryDao,
) : CloudLibraryStore {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    override suspend fun projection(activeAccountId: String?): CloudLibraryProjection {
        val localFavorites = local.favorites().map { it.identity }
        val devicePlaylists = local.devicePlaylists().map { entity ->
            val items = local.playlistItems(entity.id)
            Playlist(
                entity.id,
                entity.name,
                entity.description,
                items.mapNotNull { it.canonicalSongId },
                items.mapNotNull { it.archivePath },
                Ownership.ON_DEVICE,
                entity.revision,
            )
        }
        if (activeAccountId == null) {
            return CloudLibraryProjection(localFavorites, devicePlaylists, emptyList(), emptyList(), cloudVisible = false)
        }
        val likes = cloud.likes(activeAccountId).map { CloudLike(it.songId, it.liked, CloudSyncState.valueOf(it.syncState)) }
        val playlists = cloud.playlists(activeAccountId).map { playlistModel(it) }
        return CloudLibraryProjection(localFavorites, devicePlaylists, likes, playlists, cloudVisible = true)
    }

    override suspend fun replaceLikes(accountId: String, snapshot: CloudLikesSnapshot, nowEpochMs: Long) {
        cloud.replaceLikesSnapshot(
            CloudLibraryStateEntity(accountId, snapshot.revision, nowEpochMs),
            snapshot.songIds.map { CloudLikeEntity(accountId, it, true, CloudSyncState.SYNCED.name, nowEpochMs) },
        )
    }

    override suspend fun replacePlaylists(accountId: String, playlists: List<CloudPlaylist>) {
        cloud.replacePlaylistsSnapshot(accountId, playlists.map { it.entity(accountId, it.id) })
        playlists.forEach { playlist -> cloud.replacePlaylistSongs(playlist.id, playlist.songEntities(playlist.id)) }
    }

    override suspend fun enqueueLike(mutation: CloudMutation) {
        cloud.enqueueLike(
            mutation.entity(json),
            CloudLikeEntity(mutation.accountId, requireNotNull(mutation.songId), requireNotNull(mutation.desired), CloudSyncState.PENDING.name, mutation.createdAtEpochMs),
        )
    }

    override suspend fun enqueuePlaylist(mutation: CloudMutation, optimistic: CloudPlaylist) {
        cloud.enqueuePlaylist(
            mutation.entity(json),
            optimistic.entity(
                mutation.accountId,
                requireNotNull(mutation.playlistLocalId),
                deleted = mutation.operation == CloudMutationOperation.DELETE_PLAYLIST,
            ),
            optimistic.songEntities(requireNotNull(mutation.playlistLocalId)),
        )
    }

    override suspend fun readyMutations(accountId: String, nowEpochMs: Long, limit: Int): List<CloudMutation> =
        cloud.readyMutations(accountId, nowEpochMs, limit).map { it.model(json) }

    override suspend fun mutation(idempotencyKey: String): CloudMutation? = cloud.mutation(idempotencyKey)?.model(json)

    override suspend fun playlist(localId: String): CloudPlaylist? = cloud.playlist(localId)?.let { playlistModel(it) }

    override suspend fun replacePlaylist(accountId: String, playlist: CloudPlaylist) {
        val existing = cloud.playlistByCloudId(accountId, playlist.id)
        val localId = existing?.localId ?: playlist.id
        cloud.upsertPlaylist(playlist.entity(accountId, localId))
        cloud.replacePlaylistSongs(localId, playlist.songEntities(localId))
    }

    override suspend fun acknowledge(mutation: CloudMutation, serverPlaylist: CloudPlaylist?, nowEpochMs: Long) {
        cloud.acknowledgeMutation(mutation.idempotencyKey)
        if (mutation.operation == CloudMutationOperation.SET_LIKE) {
            if (cloud.pendingSubjectCount(mutation.accountId, mutation.subjectKey) == 0) {
                cloud.updateLikeState(mutation.accountId, requireNotNull(mutation.songId), CloudSyncState.SYNCED.name, nowEpochMs)
            }
            return
        }
        val localId = requireNotNull(mutation.playlistLocalId)
        if (serverPlaylist != null && cloud.pendingSubjectCount(mutation.accountId, mutation.subjectKey) == 0) {
            cloud.upsertPlaylist(serverPlaylist.entity(mutation.accountId, localId))
            cloud.replacePlaylistSongs(localId, serverPlaylist.songEntities(localId))
        } else if (serverPlaylist != null) {
            val current = requireNotNull(cloud.playlist(localId))
            cloud.upsertPlaylist(
                current.copy(
                    cloudId = serverPlaylist.id,
                    revision = serverPlaylist.revision,
                    syncState = CloudSyncState.PENDING.name,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
        }
        if (mutation.operation == CloudMutationOperation.CREATE_PLAYLIST && serverPlaylist != null) {
            cloud.subjectMutations(mutation.accountId, mutation.subjectKey).forEach { pending ->
                val value = pending.model(json).copy(
                    playlistCloudId = serverPlaylist.id,
                    baseRevision = pending.model(json).baseRevision ?: serverPlaylist.revision,
                )
                cloud.updateMutationPayload(pending.idempotencyKey, json.encodeToString(value.payload()))
            }
        }
    }

    override suspend fun rebase(previous: CloudMutation, replacement: CloudMutation, remote: CloudPlaylist) {
        cloud.replaceMutation(previous.idempotencyKey, replacement.entity(json))
        cloud.updatePlaylistState(requireNotNull(previous.playlistLocalId), CloudSyncState.PENDING.name, remote.revision, remote.updatedAtEpochMs)
    }

    override suspend fun markConflict(mutation: CloudMutation, remote: CloudPlaylist, nowEpochMs: Long) {
        cloud.acknowledgeMutation(mutation.idempotencyKey)
        cloud.updatePlaylistState(requireNotNull(mutation.playlistLocalId), CloudSyncState.CONFLICT.name, remote.revision, nowEpochMs)
    }

    override suspend fun reschedule(mutation: CloudMutation, code: String, nextAttemptAtEpochMs: Long) {
        cloud.rescheduleMutation(mutation.idempotencyKey, mutation.attempt + 1, nextAttemptAtEpochMs, code)
    }

    private suspend fun playlistModel(entity: CloudPlaylistEntity): CloudPlaylist {
        val songs = cloud.playlistSongs(entity.localId).map { it.songId }
        return CloudPlaylist(
            entity.cloudId ?: entity.localId,
            entity.clientMigrationId,
            entity.name,
            entity.description,
            entity.coverUrl,
            songs,
            entity.revision ?: "pending",
            entity.createdAtEpochMs,
            entity.updatedAtEpochMs,
            CloudSyncState.valueOf(entity.syncState),
            entity.localId,
        )
    }

    private fun CloudPlaylist.entity(accountId: String, localId: String, deleted: Boolean = false) = CloudPlaylistEntity(
        localId, accountId, id.takeUnless { it.startsWith("pending-") }, clientMigrationId, name, description, coverUrl,
        revision.takeUnless { it == "pending" }, createdAtEpochMs, updatedAtEpochMs, syncState.name, deleted = deleted,
    )

    private fun CloudPlaylist.songEntities(localId: String) = songIds.mapIndexed { index, id -> CloudPlaylistSongEntity(localId, id, index) }

    private fun CloudMutation.entity(json: Json) = CloudMutationEntity(
        idempotencyKey, accountId, playlistLocalId, subjectKey, operation.name, json.encodeToString(payload()), attempt,
        nextAttemptAtEpochMs, createdAtEpochMs, errorCode,
    )

    private fun CloudMutation.payload() = MutationPayload(
        playlistCloudId, songId, desired, name, description, songIds, baseRevision, clientMigrationId,
    )

    private fun CloudMutationEntity.model(json: Json): CloudMutation {
        val value = json.decodeFromString<MutationPayload>(payloadJson)
        return CloudMutation(
            idempotencyKey, accountId, CloudMutationOperation.valueOf(operation), playlistLocalId, value.playlistCloudId,
            value.songId, value.desired, value.name, value.description, value.songIds, value.baseRevision,
            value.clientMigrationId, attempt, nextAttemptAtEpochMs, createdAtEpochMs, errorCode,
        )
    }
}

@Serializable
private data class MutationPayload(
    val playlistCloudId: String? = null,
    val songId: Long? = null,
    val desired: Boolean? = null,
    val name: String? = null,
    val description: String? = null,
    val songIds: List<Long> = emptyList(),
    val baseRevision: String? = null,
    val clientMigrationId: String? = null,
)
