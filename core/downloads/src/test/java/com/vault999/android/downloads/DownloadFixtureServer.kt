package com.vault999.android.downloads

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Loopback-only deterministic HTTP fixture for the transfer integration tests. */
internal class DownloadFixtureServer(
    val fileBytes: ByteArray = ByteArray(256 * 1024) { ((it * 31) % 251).toByte() },
) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val executor = Executors.newCachedThreadPool()
    private val accepting = AtomicBoolean(true)
    private val statusPoll = AtomicInteger()
    val slowHeadersSent = CountDownLatch(1)
    val requests: MutableList<FixtureRequest> = Collections.synchronizedList(mutableListOf())
    val baseUrl: String = "http://127.0.0.1:${server.localPort}"
    val collectionZip: ByteArray = deterministicCollectionZip()

    init {
        executor.execute {
            while (accepting.get()) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                executor.execute { socket.use(::serve) }
            }
        }
    }

    fun awaitSlowHeaders(timeoutSeconds: Long = 5): Boolean =
        slowHeadersSent.await(timeoutSeconds, TimeUnit.SECONDS)

    override fun close() {
        accepting.set(false)
        runCatching { server.close() }
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 5_000
        val input = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = input.readLine() ?: return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        requests += FixtureRequest(parts[0], target, headers)
        when (path) {
            "/file" -> serveFile(socket, headers, STABLE_ETAG)
            "/etag-change" -> serveFile(socket, headers, CHANGED_ETAG)
            "/disconnect-after-headers" -> {
                writeHeaders(socket, 200, "OK", fileBytes.size.toLong(), mapOf("ETag" to STABLE_ETAG))
                // Intentional EOF before any response bytes.
            }
            "/slow-body" -> serveSlowBody(socket)
            "/rate-limit" -> writeResponse(
                socket,
                429,
                "Too Many Requests",
                "rate limited".toByteArray(),
                mapOf("Retry-After" to "1"),
            )
            "/collection/status" -> {
                val step = statusPoll.getAndIncrement()
                val json = when (step) {
                    0 -> "{\"state\":\"preparing\",\"progress\":25}"
                    1 -> "{\"state\":\"preparing\",\"progress\":75}"
                    else -> "{\"state\":\"ready\",\"progress\":100,\"url\":\"/collection.zip\"}"
                }
                writeJson(socket, 200, json)
            }
            "/collection/cancel" -> writeJson(socket, 200, "{\"state\":\"cancelled\"}")
            "/collection.zip" -> writeResponse(
                socket,
                200,
                "OK",
                collectionZip,
                mapOf("ETag" to COLLECTION_ETAG, "Content-Type" to "application/zip"),
            )
            else -> writeResponse(socket, 404, "Not Found", "missing".toByteArray())
        }
    }

    private fun serveFile(socket: Socket, headers: Map<String, String>, responseEtag: String) {
        val rangeStart = headers["range"]?.let(RANGE::matchEntire)?.groupValues?.get(1)?.toIntOrNull()
        if (rangeStart != null && rangeStart in 0..fileBytes.size) {
            val body = fileBytes.copyOfRange(rangeStart, fileBytes.size)
            writeResponse(
                socket,
                206,
                "Partial Content",
                body,
                mapOf(
                    "ETag" to responseEtag,
                    "Content-Range" to "bytes $rangeStart-${fileBytes.lastIndex}/${fileBytes.size}",
                ),
            )
        } else {
            writeResponse(socket, 200, "OK", fileBytes, mapOf("ETag" to responseEtag))
        }
    }

    private fun serveSlowBody(socket: Socket) {
        val total = 8 * 1024 * 1024L
        writeHeaders(socket, 200, "OK", total, mapOf("ETag" to STABLE_ETAG))
        slowHeadersSent.countDown()
        val output = socket.getOutputStream()
        val chunk = ByteArray(8 * 1024) { 19 }
        var sent = 0L
        while (sent < total && accepting.get() && !Thread.currentThread().isInterrupted) {
            output.write(chunk)
            output.flush()
            sent += chunk.size
            Thread.sleep(10)
        }
    }

    private fun writeJson(socket: Socket, status: Int, json: String) = writeResponse(
        socket,
        status,
        if (status == 200) "OK" else "Error",
        json.toByteArray(StandardCharsets.UTF_8),
        mapOf("Content-Type" to "application/json"),
    )

    private fun writeResponse(
        socket: Socket,
        status: Int,
        reason: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ) {
        writeHeaders(socket, status, reason, body.size.toLong(), headers)
        socket.getOutputStream().apply {
            write(body)
            flush()
        }
    }

    private fun writeHeaders(
        socket: Socket,
        status: Int,
        reason: String,
        contentLength: Long,
        headers: Map<String, String>,
    ) {
        val lines = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Connection: close\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().apply {
            write(lines.toByteArray(StandardCharsets.US_ASCII))
            flush()
        }
    }

    data class FixtureRequest(val method: String, val target: String, val headers: Map<String, String>)

    companion object {
        const val STABLE_ETAG = "\"fixture-v1\""
        const val CHANGED_ETAG = "\"fixture-v2\""
        const val COLLECTION_ETAG = "\"collection-v1\""
        private val RANGE = Regex("bytes=(\\d+)-", RegexOption.IGNORE_CASE)

        private fun deterministicCollectionZip(): ByteArray = ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                listOf(
                    "Compilation/Albums/Fixture One/01 - First.txt" to "first fixture track\n",
                    "Compilation/Albums/Fixture One/02 - Second.txt" to "second fixture track\n",
                    "Compilation/Singles/Fixture Single.txt" to "fixture single\n",
                ).forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    zip.write(contents.toByteArray(StandardCharsets.UTF_8))
                    zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }
    }
}
