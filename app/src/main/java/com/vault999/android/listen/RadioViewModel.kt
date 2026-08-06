package com.vault999.android.listen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vault999.android.model.QueueItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RadioUiState(
    val station: RadioStation? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
) {
    val playableStation: QueueItem? get() = station?.asQueueItem()?.takeIf { it.available }
}

class RadioViewModel(private val repository: RadioRepository) : ViewModel() {
    private val mutable = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observe()
                .catch {
                    mutable.update { state ->
                        state.copy(loading = false, error = "The cached radio status could not be read.")
                    }
                }
                .collect { station ->
                    mutable.update { state -> state.copy(station = station, loading = state.loading && station == null) }
                }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutable.update { it.copy(loading = it.station == null, refreshing = it.station != null, error = null) }
            runCatching { withContext(Dispatchers.IO) { repository.refresh() } }
                .onSuccess { station ->
                    mutable.update { it.copy(station = station, loading = false, refreshing = false, offline = false, error = null) }
                }
                .onFailure {
                    mutable.update { state ->
                        state.copy(
                            loading = false,
                            refreshing = false,
                            offline = true,
                            error = if (state.station == null) {
                                "Live radio is unavailable. Check the connection and retry."
                            } else {
                                "Showing the last radio status. Refresh when the connection returns."
                            },
                        )
                    }
                }
        }
    }

    companion object {
        fun factory(repository: RadioRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RadioViewModel(repository) as T
        }
    }
}
