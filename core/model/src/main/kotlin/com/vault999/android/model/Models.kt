package com.vault999.android.model

import kotlinx.serialization.Serializable

@Serializable
enum class SongCategory { RELEASED, UNRELEASED, UNSURFACED, SESSION, UNKNOWN }

@Serializable
data class Era(
    val id: Long,
    val name: String,
    val description: String = "",
    val timeFrame: String = "",
)

@Serializable
data class CanonicalSong(
    val id: Long,
    val publicNumber: Long,
    val title: String,
    val aliases: List<String> = emptyList(),
    val archivePath: String?,
    val artist: String,
    val durationSeconds: Long?,
    val category: SongCategory,
    val era: Era?,
    val artworkUrl: String?,
    val producers: List<String> = emptyList(),
    val streamUrl: String?,
) {
    init {
        require(id > 0) { "Canonical song IDs must be positive" }
        require(publicNumber > 0) { "Public song numbers must be positive" }
    }

    val isPlayable: Boolean get() = archivePath != null && streamUrl != null
}

@Serializable
enum class ArchiveKind { DIRECTORY, AUDIO, LOSSLESS, ARTWORK, VIDEO, TEXT, OTHER }

@Serializable
data class ArchiveEntry(
    val path: String,
    val name: String,
    val kind: ArchiveKind,
    val sizeBytes: Long? = null,
    val modifiedAtEpochMs: Long? = null,
    val canonicalSongId: Long? = null,
)

@Serializable
enum class Ownership { ON_DEVICE, SYNCED, PENDING_SYNC, SYNC_ERROR }

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val description: String = "",
    val songIds: List<Long> = emptyList(),
    val localPaths: List<String> = emptyList(),
    val ownership: Ownership,
    val revision: String? = null,
)

@Serializable
enum class CloudSyncState { SYNCED, PENDING, CONFLICT, ERROR }

@Serializable
data class CloudLike(
    val songId: Long,
    val liked: Boolean,
    val syncState: CloudSyncState = CloudSyncState.SYNCED,
) {
    init { require(songId > 0) { "Cloud likes require a positive canonical song ID" } }
}

@Serializable
data class CloudLikesSnapshot(
    val songIds: Set<Long>,
    val revision: String,
)

@Serializable
data class CloudPlaylist(
    val id: String,
    val clientMigrationId: String?,
    val name: String,
    val description: String,
    val coverUrl: String?,
    val songIds: List<Long>,
    val revision: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val syncState: CloudSyncState = CloudSyncState.SYNCED,
    val localId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Cloud playlist ID cannot be blank" }
        require(name.length in 1..80) { "Cloud playlist name must contain 1 to 80 characters" }
        require(description.length <= 500) { "Cloud playlist description exceeds 500 characters" }
        require(songIds.size <= 10_000 && songIds.all { it > 0 } && songIds.distinct().size == songIds.size) {
            "Cloud playlist song IDs must be unique positive canonical IDs"
        }
        require(revision.isNotBlank()) { "Cloud playlist revision cannot be blank" }
    }
}

@Serializable
data class Account(
    val id: String,
    val displayName: String,
    val discordUsername: String? = null,
    val avatarUrl: String? = null,
    val cached: Boolean = false,
)

@Serializable
data class ListeningEvent(
    val id: String,
    val songId: Long,
    val playedAtEpochMs: Long,
    val listenedSeconds: Long,
    val durationSeconds: Long,
    val source: String,
    val acknowledged: Boolean = false,
)

@Serializable
enum class RepeatMode { OFF, ALL, ONE }

@Serializable
enum class PlaybackMode { EXPLICIT_QUEUE, CATALOG, LISTEN, RADIO }

@Serializable
data class QueueItem(
    val mediaId: String,
    val title: String,
    val artist: String,
    val uri: String,
    val artworkUri: String? = null,
    val durationMs: Long? = null,
    val canonicalSongId: Long? = null,
    val local: Boolean = false,
    val available: Boolean = true,
)

@Serializable
data class QueueSnapshot(
    val items: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val playbackMode: PlaybackMode = PlaybackMode.EXPLICIT_QUEUE,
    val historyMediaIds: List<String> = emptyList(),
)

@Serializable
enum class DownloadStage {
    QUEUED,
    PREPARING,
    DOWNLOADING,
    PAUSED,
    VALIDATING,
    EXTRACTING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    CANCELLING,
    CANCELLED,
    INTERRUPTED,
    FAILED,
}

@Serializable
enum class DownloadKind { FILE, SELECTION, DIRECTORY, BULK, FULL_COLLECTION }

@Serializable
data class DownloadJob(
    val id: String,
    val kind: DownloadKind,
    val stage: DownloadStage,
    val displayName: String,
    val destinationLabel: String,
    val bytesCompleted: Long = 0,
    val bytesTotal: Long? = null,
    val bytesPerSecond: Long? = null,
    val etaSeconds: Long? = null,
    val currentItem: String? = null,
    val errorCode: String? = null,
)

@Serializable
sealed interface VaultError {
    val operationId: String

    @Serializable data class Offline(override val operationId: String) : VaultError
    @Serializable data class Timeout(override val operationId: String) : VaultError
    @Serializable data class RateLimited(override val operationId: String, val retryAfterSeconds: Long?) : VaultError
    @Serializable data class Server(override val operationId: String, val status: Int) : VaultError
    @Serializable data class Validation(override val operationId: String) : VaultError
    @Serializable data class PermissionLost(override val operationId: String) : VaultError
    @Serializable data class StorageFull(override val operationId: String) : VaultError
    @Serializable data class UnsupportedMedia(override val operationId: String) : VaultError
    @Serializable data class CorruptArchive(override val operationId: String) : VaultError
    @Serializable data class AuthenticationRejected(override val operationId: String) : VaultError
}
