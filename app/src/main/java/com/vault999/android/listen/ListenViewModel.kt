package com.vault999.android.listen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vault999.android.model.CanonicalSong
import com.vault999.android.model.QueueItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListenUiState(
    val mode: ListenMode = ListenMode.ALL,
    val current: QueueItem? = null,
    val lookAhead: List<QueueItem> = emptyList(),
    val recents: List<QueueItem> = emptyList(),
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val catalogReady: Boolean = false,
    val error: String? = null,
    val playRequest: QueueItem? = null,
)

class ListenViewModel(
    catalogSongs: Flow<List<CanonicalSong>>,
    private val engine: EndlessListenEngine = EndlessListenEngine(),
) : ViewModel() {
    private var catalog: List<CanonicalSong> = emptyList()
    private var engineState = EndlessListenState()
    private val mutable = MutableStateFlow(ListenUiState())
    val state: StateFlow<ListenUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            catalogSongs
                .catch { mutable.update { it.copy(error = "The catalog could not be read.") } }
                .collect { songs ->
                    catalog = songs
                    engineState = engine.refill(engineState, catalog)
                    publish(selected = null, catalogReady = true)
                }
        }
    }

    fun start(mode: ListenMode = engineState.activeMode) {
        val transition = engine.start(engineState, mode, catalog)
        engineState = transition.state
        publish(transition.selected)
    }

    fun switchMode(mode: ListenMode) {
        val transition = engine.switchMode(engineState, mode)
        engineState = transition.state
        publish(transition.selected)
    }

    fun next() {
        val transition = engine.next(engineState, catalog)
        engineState = transition.state
        publish(transition.selected)
    }

    fun back() {
        val transition = engine.back(engineState)
        engineState = transition.state
        publish(transition.selected)
    }

    fun forward() {
        val transition = engine.forward(engineState)
        engineState = transition.state
        publish(transition.selected)
    }

    fun consumePlayRequest() {
        mutable.update { it.copy(playRequest = null) }
    }

    private fun publish(selected: QueueItem?, catalogReady: Boolean = mutable.value.catalogReady) {
        val session = engineState.active
        mutable.value = ListenUiState(
            mode = engineState.activeMode,
            current = session.current,
            lookAhead = session.lookAhead,
            recents = session.recents,
            canGoBack = session.canGoBack,
            canGoForward = session.canGoForward,
            catalogReady = catalogReady,
            error = session.refillError,
            playRequest = selected,
        )
    }

    companion object {
        fun factory(catalogSongs: Flow<List<CanonicalSong>>): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ListenViewModel(catalogSongs) as T
        }
    }
}
