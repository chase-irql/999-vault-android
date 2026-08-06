package com.vault999.android.auth

import com.vault999.android.model.CloudLike
import com.vault999.android.model.CloudLikesSnapshot
import com.vault999.android.model.CloudPlaylist
import com.vault999.android.model.Playlist
import com.vault999.android.model.ListeningEvent

data class ListeningEventPage(val events: List<ListeningEvent>, val nextCursor: String?)

enum class CloudMutationOperation {
    SET_LIKE,
    CREATE_PLAYLIST,
    UPDATE_PLAYLIST,
    DELETE_PLAYLIST,
    SET_PLAYLIST_SONG,
    REORDER_PLAYLIST,
}

/** Persisted before its optimistic projection is exposed. */
data class CloudMutation(
    val idempotencyKey: String,
    val accountId: String,
    val operation: CloudMutationOperation,
    val playlistLocalId: String? = null,
    val playlistCloudId: String? = null,
    val songId: Long? = null,
    val desired: Boolean? = null,
    val name: String? = null,
    val description: String? = null,
    val songIds: List<Long> = emptyList(),
    val baseRevision: String? = null,
    val clientMigrationId: String? = null,
    val attempt: Int = 0,
    val nextAttemptAtEpochMs: Long,
    val createdAtEpochMs: Long,
    val errorCode: String? = null,
) {
    init {
        require(IDEMPOTENCY_KEY.matches(idempotencyKey)) { "Invalid idempotency key" }
        require(accountId.isNotBlank()) { "Account ID cannot be blank" }
        require(songId == null || songId > 0) { "Cloud mutations require positive canonical song IDs" }
        require(songIds.size <= 10_000 && songIds.all { it > 0 } && songIds.distinct().size == songIds.size) {
            "Playlist song IDs must be unique positive canonical IDs"
        }
        require(attempt >= 0 && nextAttemptAtEpochMs >= 0 && createdAtEpochMs >= 0) { "Invalid mutation timing" }
        require(errorCode == null || SAFE_CODE.matches(errorCode)) { "Unsafe mutation error code" }
        when (operation) {
            CloudMutationOperation.SET_LIKE -> require(songId != null && desired != null && playlistLocalId == null)
            CloudMutationOperation.CREATE_PLAYLIST -> require(playlistLocalId != null && playlistCloudId == null && validName(name) && validDescription(description))
            CloudMutationOperation.UPDATE_PLAYLIST -> require(playlistLocalId != null)
            CloudMutationOperation.DELETE_PLAYLIST -> require(playlistLocalId != null)
            CloudMutationOperation.SET_PLAYLIST_SONG -> require(playlistLocalId != null && songId != null && desired != null)
            CloudMutationOperation.REORDER_PLAYLIST -> require(playlistLocalId != null)
        }
    }

    val subjectKey: String get() = playlistLocalId?.let { "playlist:$it" } ?: "like:${requireNotNull(songId)}"

    fun rebased(newKey: String, revision: String, nowEpochMs: Long): CloudMutation = copy(
        idempotencyKey = newKey,
        baseRevision = revision,
        attempt = 0,
        nextAttemptAtEpochMs = nowEpochMs,
        createdAtEpochMs = nowEpochMs,
        errorCode = null,
    )

    companion object {
        private val IDEMPOTENCY_KEY = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val SAFE_CODE = Regex("[A-Za-z0-9_.-]{1,64}")
        internal fun validName(value: String?): Boolean = value != null && value.length in 1..80
        internal fun validDescription(value: String?): Boolean = value == null || value.length <= 500
    }
}

sealed interface CloudCallResult<out T> {
    data class Success<T>(val value: T) : CloudCallResult<T>
    data object Unauthorized : CloudCallResult<Nothing>
    data object Conflict : CloudCallResult<Nothing>
    data class Retryable(val code: String, val retryAfterSeconds: Long? = null) : CloudCallResult<Nothing>
    data class Rejected(val code: String, val status: Int) : CloudCallResult<Nothing>
}

interface AccountCloudTransport {
    suspend fun likes(accessToken: OpaqueSecret): CloudCallResult<CloudLikesSnapshot>
    suspend fun playlists(accessToken: OpaqueSecret): CloudCallResult<List<CloudPlaylist>>
    suspend fun playlist(accessToken: OpaqueSecret, playlistId: String): CloudCallResult<CloudPlaylist>
    suspend fun execute(accessToken: OpaqueSecret, mutation: CloudMutation): CloudCallResult<CloudPlaylist?>
}

data class CloudLibraryProjection(
    val localFavoriteIdentities: List<String>,
    val devicePlaylists: List<Playlist>,
    val cloudLikes: List<CloudLike>,
    val cloudPlaylists: List<CloudPlaylist>,
    val cloudVisible: Boolean,
)

interface CloudLibraryStore {
    suspend fun projection(activeAccountId: String?): CloudLibraryProjection
    suspend fun replaceLikes(accountId: String, snapshot: CloudLikesSnapshot, nowEpochMs: Long)
    suspend fun replacePlaylists(accountId: String, playlists: List<CloudPlaylist>)
    suspend fun enqueueLike(mutation: CloudMutation)
    suspend fun enqueuePlaylist(mutation: CloudMutation, optimistic: CloudPlaylist)
    suspend fun readyMutations(accountId: String, nowEpochMs: Long, limit: Int = 50): List<CloudMutation>
    suspend fun mutation(idempotencyKey: String): CloudMutation?
    suspend fun playlist(localId: String): CloudPlaylist?
    suspend fun replacePlaylist(accountId: String, playlist: CloudPlaylist)
    suspend fun acknowledge(mutation: CloudMutation, serverPlaylist: CloudPlaylist?, nowEpochMs: Long)
    suspend fun rebase(previous: CloudMutation, replacement: CloudMutation, remote: CloudPlaylist)
    suspend fun markConflict(mutation: CloudMutation, remote: CloudPlaylist, nowEpochMs: Long)
    suspend fun reschedule(mutation: CloudMutation, code: String, nextAttemptAtEpochMs: Long)
}
