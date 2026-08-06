package com.vault999.android.downloads

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DownloadDeviceFixtureTest {
    @Test fun appSpecificStorageResumesAndRollsBackFailedReplacement() = runBlocking {
        withFixtureRoot("storage") { root ->
            val storage = AppSpecificVaultStorage(root)
            val partial = VaultPath.of("Compilation/song.part")
            storage.openSink(partial).use { it.write(byteArrayOf(1, 2, 3, 4, 5)) }
            storage.openSink(partial, 3).use { it.write(byteArrayOf(8, 9)) }
            val final = VaultPath.of("Compilation/song.bin")
            storage.openSink(final).use { it.write("prior".toByteArray()) }

            val failure = runCatching {
                storage.move(VaultPath.of("Compilation/missing.part"), final, replaceExisting = true)
            }.exceptionOrNull()

            assertTrue(failure != null)
            assertArrayEquals("prior".toByteArray(), storage.openSource(final).use { it.readBytes() })
            storage.move(partial, final, replaceExisting = true)
            assertArrayEquals(byteArrayOf(1, 2, 3, 8, 9), storage.openSource(final).use { it.readBytes() })
        }
    }

    @Test fun smallCollectionStreamsAndExtractsOnDeviceFilesystem() = runBlocking {
        withFixtureRoot("collection") { root ->
            val archive = File(root, "fixture.zip")
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                listOf(
                    "Compilation/Albums/Device Fixture/01 - One.txt" to "one",
                    "Compilation/Singles/Device Fixture.txt" to "single",
                ).forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
            val output = File(root, "output")

            val result = SafeZipExtractor().extract(archive, AppSpecificVaultStorage(output))

            assertEquals(2, result.extractedEntries)
            assertEquals("one", File(output, "Compilation/Albums/Device Fixture/01 - One.txt").readText())
            assertEquals("single", File(output, "Compilation/Singles/Device Fixture.txt").readText())
            assertFalse(File(output, "Compilation/Albums/Device Fixture/.01 - One.txt.vault-part").exists())
            assertTrue(archive.isFile)
        }
    }

    @Test fun cancellationClosesDeviceSocketAndStopsPartialGrowthForFiveSeconds() = runBlocking {
        withFixtureRoot("socket-cancel") { root ->
            val server = SlowLoopbackServer()
            val storage = AppSpecificVaultStorage(root)
            val path = VaultPath.of("slow.part")
            try {
                val transfer = OkHttpStreamingTransfer(OkHttpClient(), checkpointBytes = 1)
                val job = launch(Dispatchers.IO) {
                    transfer.download(Request.Builder().url(server.url).build(), storage, path)
                }
                assertTrue(server.firstBodyBytes.await(5, TimeUnit.SECONDS))
                val deadline = System.currentTimeMillis() + 5_000
                while ((storage.inspect(path).size ?: 0) == 0L && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
                job.cancelAndJoin()
                assertTrue(server.clientDisconnected.await(5, TimeUnit.SECONDS))
                val sizeAfterCancel = storage.inspect(path).size
                Thread.sleep(5_200)
                assertEquals(sizeAfterCancel, storage.inspect(path).size)
                assertFalse(job.isActive)
            } finally {
                server.close()
            }
        }
    }

    private suspend fun <T> withFixtureRoot(name: String, block: suspend (File) -> T): T {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "download-fixture-$name-${UUID.randomUUID()}")
        check(root.parentFile == context.cacheDir && root.mkdir())
        return try {
            block(root)
        } finally {
            check(root.parentFile == context.cacheDir)
            root.deleteRecursively()
        }
    }

    private class SlowLoopbackServer : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val firstBodyBytes = CountDownLatch(1)
        val clientDisconnected = CountDownLatch(1)
        val url = "http://127.0.0.1:${server.localPort}/slow"
        private val thread = Thread({ serve() }, "vault-device-slow-http").apply {
            isDaemon = true
            start()
        }

        private fun serve() {
            runCatching {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 134217728\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                    val block = ByteArray(8 * 1024) { 7 }
                    firstBodyBytes.countDown()
                    while (true) {
                        output.write(block)
                        output.flush()
                        Thread.sleep(10)
                    }
                }
            }
            clientDisconnected.countDown()
        }

        override fun close() {
            runCatching { server.close() }
            thread.join(1_000)
        }
    }
}
