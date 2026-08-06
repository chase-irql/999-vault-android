package com.vault999.android.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vault999.android.model.CanonicalSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CatalogUiState(
    val songs: List<CanonicalSong> = emptyList(),
    val loading: Boolean = true,
    val offline: Boolean = false,
    val error: String? = null,
    val healthStatus: String? = null,
    val totalSongs: Long? = null,
    val categoryCounts: Map<String, Long> = emptyMap(),
    val randomSong: CanonicalSong? = null,
    val discoveryLoading: Boolean = false,
)

class CatalogViewModel(
    private val repository: CatalogRepository,
    fixtureSongs: List<CanonicalSong> = emptyList(),
) : ViewModel() {
    private val mutable = MutableStateFlow(
        if (fixtureSongs.isEmpty()) CatalogUiState() else CatalogUiState(songs = fixtureSongs, loading = false, totalSongs = fixtureSongs.size.toLong(), randomSong = fixtureSongs.firstOrNull()),
    )
    val state: StateFlow<CatalogUiState> = mutable.asStateFlow()
    private val fixtureMode = fixtureSongs.isNotEmpty()

    init {
        if (!fixtureMode) {
            viewModelScope.launch {
                repository.observeFirstPage()
                    .catch { mutable.update { state -> state.copy(loading = false, error = "The local catalog could not be read.") } }
                    .collect { songs -> mutable.update { it.copy(songs = songs, loading = it.loading && songs.isEmpty()) } }
            }
            refresh()
        }
    }

    fun refresh() {
        if (fixtureMode) return
        viewModelScope.launch {
            mutable.update { it.copy(loading = it.songs.isEmpty(), error = null) }
            runCatching { withContext(Dispatchers.IO) { repository.refresh() } }
                .onSuccess {
                    mutable.update { state -> state.copy(loading = false, offline = false, error = null) }
                    runCatching { withContext(Dispatchers.IO) { repository.dashboard() } }
                        .onSuccess { dashboard -> mutable.update { state -> state.copy(healthStatus = dashboard.healthStatus, totalSongs = dashboard.totalSongs, categoryCounts = dashboard.categoryCounts) } }
                    discover()
                }
                .onFailure {
                    mutable.update { state ->
                        state.copy(
                            loading = false,
                            offline = true,
                            error = if (state.songs.isEmpty()) "The live archive is unavailable. Check the connection and retry." else null,
                        )
                    }
                }
        }
    }

    fun discover() {
        if (fixtureMode) {
            mutable.update { state -> state.copy(randomSong = state.songs.randomOrNull()) }
            return
        }
        viewModelScope.launch {
            mutable.update { it.copy(discoveryLoading = true) }
            runCatching { withContext(Dispatchers.IO) { repository.randomSong() } }
                .onSuccess { song -> mutable.update { it.copy(randomSong = song, discoveryLoading = false) } }
                .onFailure { mutable.update { it.copy(discoveryLoading = false) } }
        }
    }

    companion object {
        fun factory(repository: CatalogRepository, fixtures: List<CanonicalSong>): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CatalogViewModel(repository, fixtures) as T
            }
        }
    }
}
