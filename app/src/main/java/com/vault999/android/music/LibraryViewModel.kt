package com.vault999.android.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vault999.android.database.DownloadDao
import com.vault999.android.database.LibraryDao
import com.vault999.android.database.ListeningEventEntity
import com.vault999.android.database.LocalFavoriteEntity
import com.vault999.android.database.PlaylistEntity
import com.vault999.android.database.PlaylistItemEntity
import com.vault999.android.database.SyncDao
import com.vault999.android.model.ListeningEvent
import com.vault999.android.model.WrappedAggregator
import com.vault999.android.model.WrappedSummary
import java.util.UUID
import java.io.File
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

data class DeviceFavorite(val identity: String, val canonicalSongId: Long?, val archivePath: String?)
data class DevicePlaylist(val id: String, val name: String, val description: String, val ownership: String)
data class DevicePlaylistDetail(val playlist: DevicePlaylist, val songIds: List<Long>)
data class DownloadedItem(val id: String, val name: String, val locationLabel: String, val localUri: String?)
data class LibraryUiState(
    val favorites: List<DeviceFavorite> = emptyList(),
    val playlists: List<DevicePlaylist> = emptyList(),
    val downloads: List<DownloadedItem> = emptyList(),
    val allTime: WrappedSummary = WrappedAggregator.aggregate(emptyList(), 0, null),
    val thirtyDays: WrappedSummary = WrappedAggregator.aggregate(emptyList(), 0, 30),
    val sevenDays: WrappedSummary = WrappedAggregator.aggregate(emptyList(), 0, 7),
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepository(
    private val library: LibraryDao,
    private val downloads: DownloadDao,
    private val sync: SyncDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val activeAccountId = MutableStateFlow<String?>(null)
    private val visibleEvents = activeAccountId.flatMapLatest(sync::observeEvents)
    val state = combine(
        library.observeFavorites(),
        library.observePlaylists(null),
        downloads.observeAll(),
        visibleEvents,
    ) { favorites, playlists, jobs, events ->
        val listening = events.map(ListeningEventEntity::asModel)
        val instant = now()
        LibraryUiState(
            favorites = favorites.map { DeviceFavorite(it.identity, it.canonicalSongId, it.archivePath) },
            playlists = playlists.map { DevicePlaylist(it.id, it.name, it.description, it.ownership) },
            downloads = jobs.filter { it.stage == "COMPLETED" || it.stage == "COMPLETED_WITH_ERRORS" }
                .map { row -> DownloadedItem(row.id, row.displayName, row.destinationIdentity, row.localPlaybackUri()) },
            allTime = WrappedAggregator.aggregate(listening, instant, null),
            thirtyDays = WrappedAggregator.aggregate(listening, instant, 30),
            sevenDays = WrappedAggregator.aggregate(listening, instant, 7),
        )
    }

    fun setActiveAccountId(accountId: String?) { activeAccountId.value = accountId }

    suspend fun createPlaylist(name: String, description: String = ""): String {
        val clean = name.trim().take(80)
        require(clean.isNotBlank())
        val id = "device:${UUID.randomUUID()}"
        library.upsertPlaylist(
            PlaylistEntity(
                id = id,
                name = clean,
                description = description.trim().take(500),
                ownership = "ON_DEVICE",
                revision = null,
                accountId = null,
                migrationKey = null,
                updatedAtEpochMs = now(),
            ),
        )
        return id
    }

    suspend fun deletePlaylist(id: String) {
        require(id.startsWith("device:")) { "Only on-device playlists may be deleted locally" }
        library.deletePlaylistItems(id)
        library.deletePlaylist(id)
    }

    fun observePlaylist(id: String) = combine(library.observePlaylist(id), library.observePlaylistItems(id)) { playlist, items ->
        playlist?.let {
            DevicePlaylistDetail(
                DevicePlaylist(it.id, it.name, it.description, it.ownership),
                items.mapNotNull { it.canonicalSongId },
            )
        }
    }

    suspend fun updatePlaylist(id: String, name: String, description: String) {
        require(id.startsWith("device:"))
        val playlist = requireNotNull(library.playlist(id))
        val cleanName = name.trim().take(80)
        require(cleanName.isNotBlank())
        library.upsertPlaylist(playlist.copy(name = cleanName, description = description.trim().take(500), updatedAtEpochMs = now()))
    }

    suspend fun setPlaylistSongs(id: String, songIds: List<Long>) {
        require(id.startsWith("device:"))
        require(songIds.size <= 10_000 && songIds.all { it > 0 } && songIds.distinct().size == songIds.size)
        requireNotNull(library.playlist(id))
        library.replacePlaylistItems(
            id,
            songIds.mapIndexed { index, songId -> PlaylistItemEntity(id, "song:$songId", index, songId, null) },
        )
    }

    suspend fun toggleFavorite(songId: Long) {
        require(songId > 0)
        val identity = "song:$songId"
        if (library.isFavorite(identity)) library.deleteFavorite(identity) else library.upsertFavorite(
            LocalFavoriteEntity(identity, songId, null, now()),
        )
    }
}

private fun com.vault999.android.database.DownloadEntity.localPlaybackUri(): String? {
    if (destinationType != "APP_SPECIFIC" || kind != "FILE") return null
    val parts = sourceJson.split('\u001F', limit = 3)
    if (parts.size != 3 || parts[0] != "FILE") return null
    return runCatching {
        val root = File(destinationIdentity).canonicalFile
        val media = File(root, parts[2]).canonicalFile
        require(media.toPath().startsWith(root.toPath()))
        media.takeIf(File::isFile)?.toURI()?.toString()
    }.getOrNull()
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val repository: LibraryRepository) : ViewModel() {
    private val selectedPlaylistId = MutableStateFlow<String?>(null)
    val state: StateFlow<LibraryUiState> = repository.state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LibraryUiState(),
    )
    val selectedPlaylist: StateFlow<DevicePlaylistDetail?> = selectedPlaylistId
        .flatMapLatest { id -> id?.let(repository::observePlaylist) ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun createPlaylist(name: String) { viewModelScope.launch { runCatching { repository.createPlaylist(name) } } }
    fun deletePlaylist(id: String) { viewModelScope.launch { runCatching { repository.deletePlaylist(id) } } }
    fun toggleFavorite(songId: Long) { viewModelScope.launch { repository.toggleFavorite(songId) } }
    fun selectPlaylist(id: String?) { selectedPlaylistId.value = id }
    fun updatePlaylist(id: String, name: String, description: String) { viewModelScope.launch { runCatching { repository.updatePlaylist(id, name, description) } } }
    fun setPlaylistSongs(id: String, songIds: List<Long>) { viewModelScope.launch { runCatching { repository.setPlaylistSongs(id, songIds) } } }

    companion object {
        fun factory(repository: LibraryRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(repository) as T
        }
    }
}

private fun ListeningEventEntity.asModel() = ListeningEvent(
    id = id,
    songId = songId,
    playedAtEpochMs = playedAtEpochMs,
    listenedSeconds = listenedSeconds,
    durationSeconds = durationSeconds,
    source = source,
    acknowledged = acknowledged,
)
