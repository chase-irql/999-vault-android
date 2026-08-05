package com.vault999.android.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vault999.android.model.ArchiveEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ArchiveUiState(
    val path: String = "",
    val items: List<ArchiveEntry> = emptyList(),
    val loading: Boolean = true,
    val offline: Boolean = false,
    val error: String? = null,
)

class ArchiveViewModel(private val repository: ArchiveRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ArchiveUiState())
    val state: StateFlow<ArchiveUiState> = mutable.asStateFlow()
    private var folderJob: Job? = null

    init {
        openFolder("")
        refresh()
    }

    fun openFolder(path: String) {
        val safe = path.trim('/').take(1000)
        folderJob?.cancel()
        mutable.update { it.copy(path = safe, loading = it.items.isEmpty()) }
        folderJob = viewModelScope.launch {
            repository.observeFolder(safe)
                .catch { mutable.update { state -> state.copy(loading = false, error = "The saved archive index could not be read.") } }
                .collect { items -> mutable.update { it.copy(items = items, loading = it.loading && items.isEmpty()) } }
        }
    }

    fun up() {
        val current = mutable.value.path
        openFolder(current.substringBeforeLast('/', ""))
    }

    fun refresh() {
        viewModelScope.launch {
            mutable.update { it.copy(loading = it.items.isEmpty(), error = null) }
            runCatching { withContext(Dispatchers.IO) { repository.refreshIndex() } }
                .onSuccess { mutable.update { state -> state.copy(loading = false, offline = false) } }
                .onFailure { mutable.update { state -> state.copy(loading = false, offline = true, error = if (state.items.isEmpty()) "The archive index is unavailable. Check the connection and retry." else null) } }
        }
    }

    companion object {
        fun factory(repository: ArchiveRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ArchiveViewModel(repository) as T
        }
    }
}

