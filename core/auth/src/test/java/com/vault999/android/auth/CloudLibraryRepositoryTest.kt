package com.vault999.android.auth

import com.vault999.android.model.Account
import com.vault999.android.model.CloudLike
import com.vault999.android.model.CloudLikesSnapshot
import com.vault999.android.model.CloudPlaylist
import com.vault999.android.model.CloudSyncState
import com.vault999.android.model.Ownership
import com.vault999.android.model.Playlist
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudLibraryRepositoryTest {
    @Test fun `like mutation is persisted before optimistic state and UUID cannot change meaning`() = runBlocking {
        val fixture = fixture()
        val key = "00000000-0000-4000-8000-000000000101"

        fixture.repository.setLike(ACCOUNT_ID, 7, true, key)
        fixture.repository.setLike(ACCOUNT_ID, 7, true, key)

        assertEquals("persist:$key", fixture.store.events.first())
        assertEquals("optimistic:like:7", fixture.store.events[1])
        assertEquals(1, fixture.store.events.count { it == "persist:$key" })
        assertEquals(1, fixture.store.mutations.size)
        assertTrue(fixture.repository.projection(ACCOUNT_ID).cloudLikes.single().liked)
        val misuse = runCatching { fixture.repository.setLike(ACCOUNT_ID, 8, true, key) }
        assertTrue(misuse.isFailure)
    }

    @Test fun `logout hides cloud projection while device data survives`() = runBlocking {
        val fixture = fixture()
        fixture.store.localFavorites += "path:Music/local.mp3"
        fixture.store.devicePlaylists += Playlist("device-1", "Offline", ownership = Ownership.ON_DEVICE)
        fixture.store.likes.getOrPut(ACCOUNT_ID) { linkedMapOf() }[1] = CloudLike(1, true)
        fixture.store.putPlaylist(ACCOUNT_ID, playlist("cloud-1", listOf(1)))

        val signedIn = fixture.repository.projection(ACCOUNT_ID)
        val signedOut = fixture.repository.projection(null)

        assertTrue(signedIn.cloudVisible)
        assertEquals(1, signedIn.cloudLikes.size)
        assertFalse(signedOut.cloudVisible)
        assertTrue(signedOut.cloudLikes.isEmpty() && signedOut.cloudPlaylists.isEmpty())
        assertEquals(signedIn.localFavoriteIdentities, signedOut.localFavoriteIdentities)
        assertEquals(signedIn.devicePlaylists, signedOut.devicePlaylists)
    }

    @Test fun `playlist operations serialize while different subjects may run together`() = runBlocking {
        val fixture = fixture()
        fixture.store.putPlaylist(ACCOUNT_ID, playlist("cloud-a", listOf(1)))
        fixture.store.putPlaylist(ACCOUNT_ID, playlist("cloud-b", listOf(2)))
        fixture.repository.setPlaylistSong(ACCOUNT_ID, "cloud-a", 3, true, "00000000-0000-4000-8000-000000000111")
        fixture.repository.setPlaylistSong(ACCOUNT_ID, "cloud-a", 4, true, "00000000-0000-4000-8000-000000000112")
        fixture.repository.setPlaylistSong(ACCOUNT_ID, "cloud-b", 5, true, "00000000-0000-4000-8000-000000000113")
        val active = mutableSetOf<String>()
        val overlapOnSamePlaylist = AtomicBoolean(false)
        fixture.transport.executeBlock = { mutation ->
            val subject = mutation.subjectKey
            synchronized(active) { if (!active.add(subject)) overlapOnSamePlaylist.set(true) }
            delay(20)
            synchronized(active) { active.remove(subject) }
            val current = requireNotNull(fixture.store.playlist(requireNotNull(mutation.playlistLocalId)))
            CloudCallResult.Success(current.copy(revision = "next-${mutation.idempotencyKey.takeLast(3)}", syncState = CloudSyncState.SYNCED))
        }

        val summary = fixture.repository.flushReady(ACCOUNT_ID)

        assertFalse(overlapOnSamePlaylist.get())
        assertEquals(3, summary.acknowledged)
        assertEquals(3, fixture.transport.executeCalls.get())
    }

    @Test fun `safe song conflict refetches rebases with new UUID and retries once`() = runBlocking {
        val ids = ArrayDeque(
            listOf(
                "00000000-0000-4000-8000-000000000121",
                "00000000-0000-4000-8000-000000000122",
            ),
        )
        val fixture = fixture { ids.removeFirst() }
        fixture.store.putPlaylist(ACCOUNT_ID, playlist("cloud-1", listOf(1), revision = "old"))
        fixture.repository.setPlaylistSong(ACCOUNT_ID, "cloud-1", 2, true, ids.removeFirst())
        val sent = mutableListOf<CloudMutation>()
        fixture.transport.executeBlock = { mutation ->
            sent += mutation
            if (sent.size == 1) CloudCallResult.Conflict
            else CloudCallResult.Success(playlist("cloud-1", listOf(1, 2), revision = "rev-3"))
        }
        fixture.transport.playlistResult = CloudCallResult.Success(playlist("cloud-1", listOf(1), revision = "rev-2"))

        val summary = fixture.repository.flushReady(ACCOUNT_ID)

        assertEquals(1, summary.acknowledged)
        assertEquals(2, sent.size)
        assertNotEquals(sent[0].idempotencyKey, sent[1].idempotencyKey)
        assertEquals("rev-2", sent[1].baseRevision)
        assertTrue(fixture.store.mutations.isEmpty())
    }

    @Test fun `unsafe reorder conflict is surfaced without replay`() = runBlocking {
        val fixture = fixture()
        fixture.store.putPlaylist(ACCOUNT_ID, playlist("cloud-1", listOf(1, 2), revision = "old"))
        fixture.repository.reorderPlaylist(ACCOUNT_ID, "cloud-1", listOf(2, 1), "00000000-0000-4000-8000-000000000131")
        fixture.transport.executeBlock = { CloudCallResult.Conflict }
        fixture.transport.playlistResult = CloudCallResult.Success(playlist("cloud-1", listOf(1, 2), revision = "remote"))

        val summary = fixture.repository.flushReady(ACCOUNT_ID)

        assertEquals(1, summary.conflicts)
        assertEquals(1, fixture.transport.executeCalls.get())
        assertEquals(CloudSyncState.CONFLICT, fixture.store.playlist("cloud-1")?.syncState)
    }

    @Test fun `offline create retains mutation and dependent song uses remapped cloud id`() = runBlocking {
        val ids = ArrayDeque(
            listOf(
                "00000000-0000-4000-8000-000000000141",
                "00000000-0000-4000-8000-000000000142",
                "00000000-0000-4000-8000-000000000143",
            ),
        )
        val fixture = fixture { ids.removeFirst() }
        val localId = fixture.repository.createPlaylist(ACCOUNT_ID, "Offline mix", idempotencyKey = ids.removeFirst(), clientMigrationId = ids.removeFirst())
        fixture.repository.setPlaylistSong(ACCOUNT_ID, localId, 9, true, "00000000-0000-4000-8000-000000000144")
        val sent = mutableListOf<CloudMutation>()
        fixture.transport.executeBlock = { mutation ->
            sent += mutation
            if (mutation.operation == CloudMutationOperation.CREATE_PLAYLIST) {
                CloudCallResult.Success(playlist("server-9", emptyList(), revision = "created"))
            } else {
                CloudCallResult.Success(playlist("server-9", listOf(9), revision = "added"))
            }
        }

        assertEquals(2, fixture.repository.flushReady(ACCOUNT_ID).acknowledged)
        assertEquals("server-9", sent[1].playlistCloudId)
        assertEquals("created", sent[1].baseRevision)
        assertTrue(fixture.store.mutations.isEmpty())
    }

    private suspend fun fixture(newUuid: () -> String = { "00000000-0000-4000-8000-${counter.incrementAndGet().toString().padStart(12, '0')}" }): Fixture {
        val session = AccountSession.create(Account(ACCOUNT_ID, "Listener"), "access", "refresh", 1_000_000)
        val sessionStore = CloudTestSessionStore(session)
        val manager = AuthSessionManager(sessionStore, CloudTestSessionTransport()) { 100 }
        manager.restoreAtStartup()
        val store = FakeCloudLibraryStore()
        val transport = FakeCloudTransport()
        return Fixture(store, transport, CloudLibraryRepository(store, manager, transport, { 100 }, newUuid))
    }

    private data class Fixture(
        val store: FakeCloudLibraryStore,
        val transport: FakeCloudTransport,
        val repository: CloudLibraryRepository,
    )

    companion object {
        private const val ACCOUNT_ID = "account-1"
        private val counter = AtomicInteger()
    }
}

private class CloudTestSessionStore(private val session: AccountSession) : SessionStore {
    override fun read(): SessionReadResult = SessionReadResult.Loaded(session)
    override fun write(session: AccountSession, writtenAtEpochMs: Long) = Unit
    override fun clear() = Unit
}

private class CloudTestSessionTransport : AccountSessionTransport {
    override suspend fun exchange(ticket: OpaqueSecret, state: OpaqueSecret, verifier: OpaqueSecret, redirectUri: String) =
        AccountTransportResult.TransientFailure("unused")
    override suspend fun refresh(refreshToken: OpaqueSecret) = AccountTransportResult.TransientFailure("unused")
    override suspend fun revoke(accessToken: OpaqueSecret) = AccountTransportResult.Success(Unit)
    override suspend fun account(accessToken: OpaqueSecret) = AccountTransportResult.TransientFailure("unused")
}

private class FakeCloudTransport : AccountCloudTransport {
    var executeBlock: suspend (CloudMutation) -> CloudCallResult<CloudPlaylist?> = { CloudCallResult.Retryable("offline") }
    var playlistResult: CloudCallResult<CloudPlaylist> = CloudCallResult.Retryable("offline")
    val executeCalls = AtomicInteger()
    override suspend fun likes(accessToken: OpaqueSecret) = CloudCallResult.Success(CloudLikesSnapshot(emptySet(), "1"))
    override suspend fun playlists(accessToken: OpaqueSecret) = CloudCallResult.Success(emptyList<CloudPlaylist>())
    override suspend fun playlist(accessToken: OpaqueSecret, playlistId: String) = playlistResult
    override suspend fun execute(accessToken: OpaqueSecret, mutation: CloudMutation): CloudCallResult<CloudPlaylist?> {
        executeCalls.incrementAndGet()
        return executeBlock(mutation)
    }
}

private class FakeCloudLibraryStore : CloudLibraryStore {
    val localFavorites = mutableListOf<String>()
    val devicePlaylists = mutableListOf<Playlist>()
    val likes = linkedMapOf<String, MutableMap<Long, CloudLike>>()
    val playlists = linkedMapOf<String, Pair<String, CloudPlaylist>>()
    val mutations = linkedMapOf<String, CloudMutation>()
    val events = mutableListOf<String>()

    fun putPlaylist(accountId: String, playlist: CloudPlaylist) { playlists[playlist.id] = accountId to playlist }

    override suspend fun projection(activeAccountId: String?): CloudLibraryProjection {
        val cloudLikes = activeAccountId?.let { likes[it]?.values?.toList() }.orEmpty()
        val cloudPlaylists = activeAccountId?.let { id -> playlists.values.filter { it.first == id }.map { it.second } }.orEmpty()
        return CloudLibraryProjection(localFavorites.toList(), devicePlaylists.toList(), cloudLikes, cloudPlaylists, activeAccountId != null)
    }

    override suspend fun replaceLikes(accountId: String, snapshot: CloudLikesSnapshot, nowEpochMs: Long) {
        likes[accountId] = snapshot.songIds.associateWith { CloudLike(it, true) }.toMutableMap()
    }
    override suspend fun replacePlaylists(accountId: String, playlists: List<CloudPlaylist>) {
        this.playlists.entries.removeIf { it.value.first == accountId && it.value.second.syncState == CloudSyncState.SYNCED }
        playlists.forEach { putPlaylist(accountId, it) }
    }
    override suspend fun enqueueLike(mutation: CloudMutation) {
        persist(mutation)
        likes.getOrPut(mutation.accountId) { linkedMapOf() }[requireNotNull(mutation.songId)] =
            CloudLike(mutation.songId, requireNotNull(mutation.desired), CloudSyncState.PENDING)
        events += "optimistic:like:${mutation.songId}"
    }
    override suspend fun enqueuePlaylist(mutation: CloudMutation, optimistic: CloudPlaylist) {
        persist(mutation)
        playlists[requireNotNull(mutation.playlistLocalId)] = mutation.accountId to optimistic
        events += "optimistic:${mutation.subjectKey}"
    }
    private fun persist(mutation: CloudMutation) {
        val existing = mutations[mutation.idempotencyKey]
        if (existing != null) {
            require(existing == mutation) { "Idempotency misuse" }
            return
        }
        events += "persist:${mutation.idempotencyKey}"
        mutations[mutation.idempotencyKey] = mutation
    }
    override suspend fun readyMutations(accountId: String, nowEpochMs: Long, limit: Int) =
        mutations.values.filter { it.accountId == accountId && it.nextAttemptAtEpochMs <= nowEpochMs }.take(limit)
    override suspend fun mutation(idempotencyKey: String) = mutations[idempotencyKey]
    override suspend fun playlist(localId: String) = playlists[localId]?.second
    override suspend fun replacePlaylist(accountId: String, playlist: CloudPlaylist) { playlists[playlist.id] = accountId to playlist }
    override suspend fun acknowledge(mutation: CloudMutation, serverPlaylist: CloudPlaylist?, nowEpochMs: Long) {
        mutations.remove(mutation.idempotencyKey)
        if (mutation.operation == CloudMutationOperation.SET_LIKE) {
            likes[mutation.accountId]?.set(requireNotNull(mutation.songId), CloudLike(mutation.songId, requireNotNull(mutation.desired)))
            return
        }
        val localId = requireNotNull(mutation.playlistLocalId)
        if (serverPlaylist != null) playlists[localId] = mutation.accountId to serverPlaylist
        if (mutation.operation == CloudMutationOperation.CREATE_PLAYLIST && serverPlaylist != null) {
            mutations.replaceAll { _, queued ->
                if (queued.subjectKey == mutation.subjectKey) queued.copy(
                    playlistCloudId = serverPlaylist.id,
                    baseRevision = queued.baseRevision ?: serverPlaylist.revision,
                ) else queued
            }
        }
    }
    override suspend fun rebase(previous: CloudMutation, replacement: CloudMutation, remote: CloudPlaylist) {
        mutations.remove(previous.idempotencyKey)
        mutations[replacement.idempotencyKey] = replacement
        val localId = requireNotNull(previous.playlistLocalId)
        playlists[localId] = previous.accountId to requireNotNull(playlists[localId]).second.copy(revision = remote.revision)
    }
    override suspend fun markConflict(mutation: CloudMutation, remote: CloudPlaylist, nowEpochMs: Long) {
        mutations.remove(mutation.idempotencyKey)
        val localId = requireNotNull(mutation.playlistLocalId)
        playlists[localId] = mutation.accountId to requireNotNull(playlists[localId]).second.copy(syncState = CloudSyncState.CONFLICT, revision = remote.revision)
    }
    override suspend fun reschedule(mutation: CloudMutation, code: String, nextAttemptAtEpochMs: Long) {
        mutations[mutation.idempotencyKey] = mutation.copy(attempt = mutation.attempt + 1, nextAttemptAtEpochMs = nextAttemptAtEpochMs, errorCode = code)
    }
}

private fun playlist(id: String, songs: List<Long>, revision: String = "rev-1") = CloudPlaylist(
    id, null, "Playlist $id", "", null, songs, revision, 1, 1,
)
