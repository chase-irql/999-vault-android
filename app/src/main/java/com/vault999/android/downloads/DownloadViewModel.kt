package com.vault999.android.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vault999.android.model.ArchiveEntry
import com.vault999.android.model.DownloadJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadViewModel(private val repository: DownloadRepository) : ViewModel() {
    val jobs: StateFlow<List<DownloadJob>> = repository.observe().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun enqueue(entry: ArchiveEntry, url: String) { viewModelScope.launch { repository.enqueueFile(entry, url) } }
    fun enqueueFullCollection() { viewModelScope.launch { repository.enqueueFullCollection() } }
    fun enqueueSelection(paths: List<String>) { viewModelScope.launch { repository.enqueueCollection(paths.distinct().take(1_000)) } }
    fun pause(id: String) { viewModelScope.launch { repository.pause(id) } }
    fun resume(id: String) { viewModelScope.launch { repository.resume(id) } }
    fun cancel(id: String) { viewModelScope.launch { repository.cancel(id) } }

    companion object {
        fun factory(repository: DownloadRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DownloadViewModel(repository) as T
        }
    }
}
