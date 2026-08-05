package com.vault999.android.downloads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OkHttpStreamingTransferTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `resume mismatch closes response restarts from zero and checkpoints`() = runBlocking {
        val payload = ByteArray(32 * 1024) { (it % 251).toByte() }
        val seenRanges = mutableListOf<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            seenRanges += chain.request().header("Range")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("ETag", "\"new\"")
                .body(payload.toResponseBody())
                .build()
        }.build()
        val storage = AppSpecificVaultStorage(temporary.newFolder("restart"))
        val path = VaultPath.of("track.bin")
        storage.openSink(path).use { it.write(ByteArray(100) { 42 }) }
        val checkpoints = mutableListOf<DownloadCheckpoint>()

        val result = OkHttpStreamingTransfer(client, checkpointBytes = 4 * 1024L).download(
            Request.Builder().url("https://example.test/track").build(),
            storage,
            path,
            ResumeMetadata("\"old\"", payload.size.toLong(), 100),
            checkpoints::add,
        )

        assertEquals(listOf("bytes=100-", null), seenRanges)
        assertTrue(result.restartedFromZero)
        assertArrayEquals(payload, storage.openSource(path).use { it.readBytes() })
        assertTrue(checkpoints.size > 1)
        assertEquals(payload.size.toLong(), checkpoints.last().completedBytes)
    }

    @Test fun `cancelling after headers closes body and stops file growth`() = runBlocking {
        val body = BlockingResponseBody()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }).build()
        val storage = AppSpecificVaultStorage(temporary.newFolder("cancel"))
        val path = VaultPath.of("slow.bin")
        val job = launch(Dispatchers.Default) {
            OkHttpStreamingTransfer(client, checkpointBytes = 1).download(
                Request.Builder().url("https://example.test/slow").build(), storage, path,
            )
        }
        assertTrue(body.readStarted.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(body.closed.await(5, TimeUnit.SECONDS))
        val firstSize = storage.inspect(path).size
        Thread.sleep(100)
        assertEquals(firstSize, storage.inspect(path).size)
        assertFalse(job.isActive)
    }

    private class BlockingResponseBody : ResponseBody() {
        val readStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val source = BlockingSource(readStarted, closed).buffer()

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = source
    }

    private class BlockingSource(
        private val readStarted: CountDownLatch,
        private val closed: CountDownLatch,
    ) : Source {
        private val monitor = Object()
        private var first = true
        private var isClosed = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            synchronized(monitor) {
                if (first) {
                    first = false
                    sink.write(ByteArray(8 * 1024) { 7 })
                    return 8L * 1024
                }
                readStarted.countDown()
                while (!isClosed) monitor.wait()
                return -1
            }
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            synchronized(monitor) {
                isClosed = true
                monitor.notifyAll()
            }
            closed.countDown()
        }
    }
}
