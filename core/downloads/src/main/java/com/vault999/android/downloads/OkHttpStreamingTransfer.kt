package com.vault999.android.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.coroutineContext

data class DownloadCheckpoint(
    val completedBytes: Long,
    val totalBytes: Long?,
    val validator: String?,
)

data class StreamingDownloadResult(
    val completedBytes: Long,
    val totalBytes: Long?,
    val validator: String?,
    val restartedFromZero: Boolean,
)

class HttpTransferException(val status: Int, message: String) : IOException(message)

class OkHttpStreamingTransfer(
    private val callFactory: Call.Factory,
    private val bufferBytes: Int = DEFAULT_BUFFER_BYTES,
    private val checkpointBytes: Long = DEFAULT_CHECKPOINT_BYTES,
) {
    init {
        require(bufferBytes in 8 * 1024..1024 * 1024)
        require(checkpointBytes > 0)
    }

    suspend fun download(
        request: Request,
        storage: VaultStorage,
        destination: VaultPath,
        saved: ResumeMetadata? = null,
        onCheckpoint: suspend (DownloadCheckpoint) -> Unit = {},
    ): StreamingDownloadResult = withContext(Dispatchers.IO) {
        var restarted = false
        var checkpoint = saved
        while (true) {
            coroutineContext.ensureActive()
            val offset = checkpoint?.completedBytes?.takeIf { it > 0 } ?: 0L
            val rangedRequest = request.newBuilder().apply {
                if (offset > 0) {
                    header("Range", "bytes=$offset-")
                    checkpoint?.validator?.takeIf(String::isNotBlank)?.let { header("If-Range", it) }
                } else {
                    removeHeader("Range")
                    removeHeader("If-Range")
                }
            }.build()
            val call = callFactory.newCall(rangedRequest)
            val active = ActiveResources(call)
            val attempt = coroutineScope {
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        active.closeAll()
                    }
                }
                try {
                    val response = try {
                        call.execute()
                    } catch (failure: IOException) {
                        coroutineContext.ensureActive()
                        throw failure
                    }
                    active.add(response)
                    when (val decision = HttpRangeValidator.evaluate(checkpoint, response)) {
                        is RangeDecision.Restart -> {
                            active.closeAll()
                            if (offset == 0L || restarted) {
                                throw IOException("Unsafe HTTP range response: ${decision.reason}")
                            }
                            storage.openSink(destination, 0).use { it.flush() }
                            Attempt.Restart
                        }
                        is RangeDecision.Reject -> {
                            throw HttpTransferException(decision.status, decision.reason)
                        }
                        is RangeDecision.AlreadyComplete -> Attempt.Finished(
                            StreamingDownloadResult(
                                decision.totalBytes,
                                decision.totalBytes,
                                decision.validator,
                                restarted,
                            ),
                        )
                        is RangeDecision.Fresh -> Attempt.Finished(
                            stream(
                                response,
                                active,
                                storage,
                                destination,
                                0,
                                decision.totalBytes,
                                decision.validator,
                                restarted,
                                onCheckpoint,
                            ),
                        )
                        is RangeDecision.Resume -> Attempt.Finished(
                            stream(
                                response,
                                active,
                                storage,
                                destination,
                                offset,
                                decision.totalBytes,
                                decision.validator,
                                restarted,
                                onCheckpoint,
                            ),
                        )
                    }
                } finally {
                    watcher.cancel()
                    active.closeAll()
                }
            }
            when (attempt) {
                Attempt.Restart -> {
                    checkpoint = null
                    restarted = true
                }
                is Attempt.Finished -> return@withContext attempt.result
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable")
    }

    private suspend fun stream(
        response: Response,
        active: ActiveResources,
        storage: VaultStorage,
        destination: VaultPath,
        offset: Long,
        total: Long?,
        validator: String?,
        restarted: Boolean,
        onCheckpoint: suspend (DownloadCheckpoint) -> Unit,
    ): StreamingDownloadResult {
        val source = response.body.byteStream()
        active.add(source)
        val sink = storage.openSink(destination, offset)
        active.add(sink)
        val buffer = ByteArray(bufferBytes)
        var completed = offset
        var nextCheckpoint = saturatingAdd(completed, checkpointBytes)
        while (true) {
            coroutineContext.ensureActive()
            val count = try {
                source.read(buffer)
            } catch (failure: IOException) {
                coroutineContext.ensureActive()
                throw failure
            }
            coroutineContext.ensureActive()
            if (count < 0) break
            if (count == 0) continue
            sink.write(buffer, 0, count)
            completed = Math.addExact(completed, count.toLong())
            if (total != null && completed > total) throw IOException("Response exceeded declared total length")
            if (completed >= nextCheckpoint) {
                sink.flush()
                onCheckpoint(DownloadCheckpoint(completed, total, validator))
                nextCheckpoint = saturatingAdd(completed, checkpointBytes)
            }
        }
        sink.flush()
        if (total != null && completed != total) throw IOException("Response ended before declared total length")
        onCheckpoint(DownloadCheckpoint(completed, total, validator))
        return StreamingDownloadResult(completed, total, validator, restarted)
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private class ActiveResources(private val call: Call) {
        private val resources = mutableListOf<Closeable>()

        @Synchronized fun add(resource: Closeable) {
            resources += resource
        }

        @Synchronized fun closeAll() {
            call.cancel()
            resources.asReversed().forEach { runCatching { it.close() } }
            resources.clear()
        }
    }

    private sealed interface Attempt {
        data object Restart : Attempt
        data class Finished(val result: StreamingDownloadResult) : Attempt
    }

    companion object {
        const val DEFAULT_BUFFER_BYTES = 64 * 1024
        const val DEFAULT_CHECKPOINT_BYTES = 1024L * 1024
    }
}
