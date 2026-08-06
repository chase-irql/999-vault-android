package com.vault999.android.downloads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadFixtureIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

    @Test fun `stable Range fixture resumes exact bytes over a real socket`() = runBlocking {
        DownloadFixtureServer().use { server ->
            val root = temporary.newFolder("range")
            val storage = AppSpecificVaultStorage(root)
            val path = VaultPath.of("fixture.bin")
            val offset = 64 * 1024
            storage.openSink(path).use { it.write(server.fileBytes, 0, offset) }

            val result = OkHttpStreamingTransfer(client).download(
                Request.Builder().url("${server.baseUrl}/file").build(),
                storage,
                path,
                ResumeMetadata(DownloadFixtureServer.STABLE_ETAG, server.fileBytes.size.toLong(), offset.toLong()),
            )

            assertFalse(result.restartedFromZero)
            assertArrayEquals(server.fileBytes, storage.openSource(path).use { it.readBytes() })
            val request = server.requests.single()
            assertEquals("bytes=$offset-", request.headers["range"])
            assertEquals(DownloadFixtureServer.STABLE_ETAG, request.headers["if-range"])
        }
    }

    @Test fun `disconnect immediately after headers fails without accepting a short file`() = runBlocking {
        DownloadFixtureServer().use { server ->
            val storage = AppSpecificVaultStorage(temporary.newFolder("disconnect"))
            val path = VaultPath.of("fixture.bin")

            val failure = runCatching {
                OkHttpStreamingTransfer(client).download(
                    Request.Builder().url("${server.baseUrl}/disconnect-after-headers").build(),
                    storage,
                    path,
                )
            }.exceptionOrNull()

            assertTrue(failure != null)
            assertTrue(storage.inspect(path).size == 0L)
        }
    }

    @Test fun `cancelling slow fixture closes socket and stops byte growth`() = runBlocking {
        DownloadFixtureServer().use { server ->
            val storage = AppSpecificVaultStorage(temporary.newFolder("slow-cancel"))
            val path = VaultPath.of("fixture.bin")
            val job = launch(Dispatchers.Default) {
                OkHttpStreamingTransfer(client, checkpointBytes = 8 * 1024L).download(
                    Request.Builder().url("${server.baseUrl}/slow-body").build(),
                    storage,
                    path,
                )
            }
            assertTrue(server.awaitSlowHeaders())
            val deadline = System.nanoTime() + 5_000_000_000L
            while ((storage.inspect(path).size ?: 0L) == 0L && System.nanoTime() < deadline) delay(10)
            assertTrue((storage.inspect(path).size ?: 0L) > 0L)

            job.cancelAndJoin()
            val stoppedAt = storage.inspect(path).size
            delay(250)

            assertEquals(stoppedAt, storage.inspect(path).size)
            assertFalse(job.isActive)
        }
    }

    @Test fun `changed ETag fixture forces a clean restart`() = runBlocking {
        DownloadFixtureServer().use { server ->
            val storage = AppSpecificVaultStorage(temporary.newFolder("etag"))
            val path = VaultPath.of("fixture.bin")
            val offset = 32 * 1024
            storage.openSink(path).use { it.write(server.fileBytes, 0, offset) }

            val result = OkHttpStreamingTransfer(client).download(
                Request.Builder().url("${server.baseUrl}/etag-change").build(),
                storage,
                path,
                ResumeMetadata(DownloadFixtureServer.STABLE_ETAG, server.fileBytes.size.toLong(), offset.toLong()),
            )

            assertTrue(result.restartedFromZero)
            assertEquals(DownloadFixtureServer.CHANGED_ETAG, result.validator)
            assertArrayEquals(server.fileBytes, storage.openSource(path).use { it.readBytes() })
            assertEquals("bytes=$offset-", server.requests[0].headers["range"])
            assertEquals(null, server.requests[1].headers["range"])
        }
    }

    @Test fun `429 fixture is surfaced as a typed HTTP transfer failure`() = runBlocking {
        DownloadFixtureServer().use { server ->
            val storage = AppSpecificVaultStorage(temporary.newFolder("rate-limit"))
            val failure = runCatching {
                OkHttpStreamingTransfer(client).download(
                    Request.Builder().url("${server.baseUrl}/rate-limit").build(),
                    storage,
                    VaultPath.of("fixture.bin"),
                )
            }.exceptionOrNull()

            assertTrue(failure is HttpTransferException)
            assertEquals(429, (failure as HttpTransferException).status)
        }
    }

    @Test fun `collection preparation progress cancellation and ZIP extraction are deterministic`() = runBlocking {
        DownloadFixtureServer().use { server ->
            val states = List(3) {
                client.newCall(Request.Builder().url("${server.baseUrl}/collection/status").build())
                    .execute().use { response -> response.body.string() }
            }
            assertTrue(states[0].contains("\"progress\":25"))
            assertTrue(states[1].contains("\"progress\":75"))
            assertTrue(states[2].contains("\"state\":\"ready\""))
            val cancelled = client.newCall(
                Request.Builder().url("${server.baseUrl}/collection/cancel").post(ByteArray(0).toRequestBody()).build(),
            ).execute().use { response -> response.body.string() }
            assertTrue(cancelled.contains("\"state\":\"cancelled\""))

            val archiveRoot = temporary.newFolder("collection-archive")
            val archiveStorage = AppSpecificVaultStorage(archiveRoot)
            val archivePath = VaultPath.of("collection.zip")
            OkHttpStreamingTransfer(client).download(
                Request.Builder().url("${server.baseUrl}/collection.zip").build(),
                archiveStorage,
                archivePath,
            )
            assertArrayEquals(server.collectionZip, File(archiveRoot, archivePath.value).readBytes())

            val outputRoot = temporary.newFolder("collection-output")
            val extraction = SafeZipExtractor().extract(
                File(archiveRoot, archivePath.value),
                AppSpecificVaultStorage(outputRoot),
            )
            assertEquals(3, extraction.extractedEntries)
            assertEquals("first fixture track\n", File(outputRoot, "Compilation/Albums/Fixture One/01 - First.txt").readText())
            assertEquals("fixture single\n", File(outputRoot, "Compilation/Singles/Fixture Single.txt").readText())
        }
    }
}
