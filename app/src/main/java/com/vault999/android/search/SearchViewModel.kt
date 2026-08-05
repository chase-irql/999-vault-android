package com.vault999.android.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vault999.android.model.CanonicalSong
import com.vault999.android.network.CatalogQuery
import com.vault999.android.network.JuiceWrldApiClient
import com.vault999.android.network.LyricsSearchQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchMode { SONGS, LYRICS }
data class SearchResult(val song: CanonicalSong, val excerpt: String? = null)
data class SearchUiState(
    val query: String = "",
    val mode: SearchMode = SearchMode.SONGS,
    val results: List<SearchResult> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class SearchRepository(private val api: JuiceWrldApiClient) {
    suspend fun search(mode: SearchMode, query: String): List<SearchResult> = when (mode) {
        SearchMode.SONGS -> api.songs(CatalogQuery(pageSize = 30, search = query)).songs.map(::SearchResult)
        SearchMode.LYRICS -> api.lyricsSearch(LyricsSearchQuery(query, pageSize = 30)).hits.map { SearchResult(it.song, it.excerpt) }
    }
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val repository: SearchRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val mode = MutableStateFlow(SearchMode.SONGS)
    private val mutable = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            combine(query.debounce(350), mode) { text, selected -> text.trim() to selected }
                .collectLatest { (text, selected) ->
                    mutable.update { it.copy(query = query.value, mode = selected, loading = text.length >= 2, error = null, results = if (text.length < 2) emptyList() else it.results) }
                    if (text.length < 2) return@collectLatest
                    runCatching { withContext(Dispatchers.IO) { repository.search(selected, text) } }
                        .onSuccess { results -> mutable.update { it.copy(results = results, loading = false, error = null) } }
                        .onFailure { mutable.update { it.copy(loading = false, error = "Search is unavailable. Saved music remains available.") } }
                }
        }
    }

    fun setQuery(value: String) { query.value = value.take(250); mutable.update { it.copy(query = query.value) } }
    fun setMode(value: SearchMode) { mode.value = value }

    companion object {
        fun factory(repository: SearchRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(repository) as T
        }
    }
}
