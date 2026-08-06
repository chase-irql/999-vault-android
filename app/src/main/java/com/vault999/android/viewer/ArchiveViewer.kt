package com.vault999.android.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vault999.android.model.ArchiveEntry
import com.vault999.android.model.ArchiveKind
import com.vault999.android.network.JuiceWrldApiClient
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request

data class ViewerUiState(
    val entry: ArchiveEntry? = null,
    val mediaUrl: String? = null,
    val text: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Repository boundary for bounded archive media viewing. */
class ArchiveViewerRepository(
    private val api: JuiceWrldApiClient,
    private val calls: Call.Factory,
) {
    fun mediaUrl(entry: ArchiveEntry): String = when (entry.kind) {
        ArchiveKind.ARTWORK -> api.coverArtUrl(entry.path)
        else -> api.fileDownloadUrl(entry.path)
    }

    suspend fun text(entry: ArchiveEntry): String = withContext(Dispatchers.IO) {
        require(entry.kind == ArchiveKind.TEXT)
        calls.newCall(Request.Builder().url(api.fileDownloadUrl(entry.path)).get().build()).execute().use { response ->
            check(!response.isRedirect && response.isSuccessful) { "Text file is unavailable" }
            val length = response.body.contentLength()
            check(length < 0 || length <= MAX_TEXT_BYTES) { "Text file exceeds the 1 MiB viewer limit" }
            val output = ByteArrayOutputStream()
            val input = response.body.byteStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= MAX_TEXT_BYTES) { "Text file exceeds the 1 MiB viewer limit" }
                output.write(buffer, 0, count)
            }
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString()
        }
    }

    companion object { const val MAX_TEXT_BYTES = 1024 * 1024 }
}

class ArchiveViewerViewModel(private val repository: ArchiveViewerRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = mutable.asStateFlow()

    fun open(entry: ArchiveEntry) {
        mutable.value = ViewerUiState(entry = entry, mediaUrl = repository.mediaUrl(entry), loading = entry.kind == ArchiveKind.TEXT)
        if (entry.kind != ArchiveKind.TEXT) return
        viewModelScope.launch {
            runCatching { repository.text(entry) }
                .onSuccess { value -> mutable.update { it.copy(text = value, loading = false) } }
                .onFailure { failure -> mutable.update { it.copy(loading = false, error = failure.message ?: "This text file cannot be displayed.") } }
        }
    }

    companion object {
        fun factory(repository: ArchiveViewerRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ArchiveViewerViewModel(repository) as T
        }
    }
}
