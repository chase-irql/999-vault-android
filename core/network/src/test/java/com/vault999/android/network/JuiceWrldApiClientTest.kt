package com.vault999.android.network

import com.vault999.android.model.ArchiveKind
import com.vault999.android.model.SongCategory
import com.vault999.android.model.VaultError
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.ArrayDeque

class JuiceWrldApiClientTest {
    @Test fun `catalog request uses bounded query and normalizes canonical identity`() = runBlocking {
        var request: Request? = null
        val api = client { incoming ->
            request = incoming
            response(incoming, 200, fixture("catalog-page.json"))
        }

        val page = api.songs(CatalogQuery(page = 2, pageSize = 25, search = "  two   minutes ", category = "unreleased", eraName = "jute"))

        assertEquals("2", request!!.url.queryParameter("page"))
        assertEquals("25", request!!.url.queryParameter("page_size"))
        assertEquals("two minutes", request!!.url.queryParameter("searchall"))
        assertEquals("unreleased", request!!.url.queryParameter("category"))
        assertEquals("jute", request!!.url.queryParameter("era"))
        assertEquals(1, page.songs.size)
        val song = page.songs.single()
        assertEquals(94316, song.id)
        assertEquals(2354, song.publicNumber)
        assertEquals("2MININHELL", song.title)
        assertEquals(listOf("2 Minutes In Hell", "Alternate Name"), song.aliases)
        assertEquals(222L, song.durationSeconds)
        assertEquals(SongCategory.UNRELEASED, song.category)
        assertEquals(listOf("J Knight", "Nick Mira"), song.producers)
        assertEquals("https://juicewrldapi.com/juicewrld/files/download/?path=Compilation%2F2.%20Unreleased%2F2MININHELL.mp3", song.streamUrl)
        assertTrue(song.artworkUrl!!.startsWith("https://juicewrldapi.com/juicewrld/files/cover-art/"))
        assertTrue(page.hasNext)
        assertFalse(page.hasPrevious)
    }

    @Test fun `health stats eras detail archive and radio use deterministic fixtures`() = runBlocking {
        val responses = ArrayDeque(
            listOf(
                "{\"status\":\"ok\"}",
                "{\"total_songs\":2517,\"category_stats\":{\"released\":322},\"era_stats\":{\"DRFL\":339}}",
                "{\"count\":1,\"next\":null,\"results\":[{\"id\":101,\"name\":\"jute\",\"time_frame\":\"2014-2017\"}]}",
                fixture("archive-listing.json"),
                fixture("radio-live.json"),
            )
        )
        var call = 0
        val api = client { request ->
            call++
            val body = if (call == 4) {
                // Detail is the first valid catalog record, not the page envelope.
                fixture("catalog-page.json").substringAfter("\"results\": [").substringBeforeLast(",\n    {\n      \"id\": -1")
            } else responses.removeFirst()
            response(request, 200, body)
        }

        assertTrue(api.health().healthy)
        assertEquals(2517, api.stats().totalSongs)
        assertEquals("jute", api.eras().eras.single().name)
        assertEquals("fixture lyric", api.song(94316).lyrics)
        val files = api.browseFiles("Compilation")
        assertEquals(2, files.items.size)
        assertEquals(ArchiveKind.DIRECTORY, files.items[0].kind)
        assertEquals(ArchiveKind.AUDIO, files.items[1].kind)
        val radio = api.radioStatus()
        assertEquals(15, radio.listenerCount)
        assertEquals("In My Head", radio.nowPlaying!!.title)
        assertEquals("https://juicewrldapi.com/juicewrld/radio/stream.mp3", radio.streamUrl)
    }

    @Test fun `download and cover URLs reject traversal and encode path`() {
        val api = client { response(it, 200, "{}") }
        assertEquals(
            "https://juicewrldapi.com/juicewrld/files/download/?path=Compilation%2FA%2FB.mp3",
            api.fileDownloadUrl("Compilation/A/B.mp3"),
        )
        assertTrue(api.coverArtUrl("Cover Arts/A.jpg").contains("path=Cover%20Arts%2FA.jpg"))
        assertThrows(IllegalArgumentException::class.java) { api.fileDownloadUrl("../secret") }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.browseFiles("/absolute") } }
    }

    @Test fun `lyrics search sends its exact bounded contract and retains canonical lyrics`() = runBlocking {
        var request: Request? = null
        val api = client { incoming ->
            request = incoming
            response(incoming, 200, fixture("lyrics-search.json"))
        }

        val result = api.lyricsSearch("  searched   phrase ", page = 3, pageSize = 20)

        assertEquals("/juicewrld/songs/", request!!.url.encodedPath)
        assertEquals("searched phrase", request!!.url.queryParameter("lyrics"))
        assertEquals("3", request!!.url.queryParameter("page"))
        assertEquals("20", request!!.url.queryParameter("page_size"))
        assertEquals("true", request!!.url.queryParameter("file_names_array"))
        assertEquals(1, result.hits.size)
        assertEquals(95080L, result.hits.single().song.id)
        assertEquals("First line\nSecond line with the searched phrase.\nThird line", result.hits.single().lyrics)
        assertEquals("First line Second line with the searched phrase. Third line", result.hits.single().excerpt)
    }

    @Test fun `lyrics query bounds fail before network`() {
        assertThrows(IllegalArgumentException::class.java) { LyricsSearchQuery("x") }
        assertThrows(IllegalArgumentException::class.java) { LyricsSearchQuery("valid", page = 0) }
        assertThrows(IllegalArgumentException::class.java) { LyricsSearchQuery("valid", pageSize = 31) }
        assertThrows(IllegalArgumentException::class.java) { LyricsSearchQuery("bad\nquery") }
        assertThrows(IllegalArgumentException::class.java) { LyricsSearchQuery("x".repeat(251)) }
    }

    @Test fun `zip start status and cancellation use unsafe mutation policy`() = runBlocking {
        val id = "12345678-1234-1234-1234-123456789abc"
        val seen = mutableListOf<Request>()
        val bodies = ArrayDeque(
            listOf(
                "{\"job_id\":\"$id\",\"status\":\"queued\"}",
                "{\"id\":\"$id\",\"status\":\"ready\",\"progress\":100,\"download_url\":\"/juicewrld/zip-jobs/$id.zip\"}",
                "{\"status\":\"cancelled\"}",
            )
        )
        val api = client { request -> seen.add(request); response(request, 200, bodies.removeFirst()) }

        assertEquals(ZipJobState.QUEUED, api.startZip(listOf("Compilation", "Compilation")).state)
        val ready = api.zipStatus(id)
        assertEquals(ZipJobState.READY, ready.state)
        assertEquals("https://juicewrldapi.com/juicewrld/zip-jobs/$id.zip", ready.downloadUrl)
        assertEquals(ZipJobState.CANCELLED, api.cancelZip(id).state)
        assertEquals(listOf("POST", "GET", "POST"), seen.map { it.method })
        assertEquals("[\"Compilation\"]", seen.first().bodyText().substringAfter("\"paths\":").substringBeforeLast('}'))
        assertNull(seen[1].url.query)
    }

    @Test fun `idempotent reads retry 429 and 5xx honoring Retry-After`() = runBlocking {
        val queued = ArrayDeque(listOf(429, 503, 200))
        val waits = mutableListOf<Long>()
        var calls = 0
        val api = client(
            responder = { request ->
                calls++
                val code = queued.removeFirst()
                response(request, code, if (code == 200) "{\"status\":\"ok\"}" else "{\"error\":\"secret backend detail\"}", if (code == 429) mapOf("Retry-After" to "3") else emptyMap())
            },
            retryDelay = RetryDelay { waits += it },
        )

        assertTrue(api.health().healthy)
        assertEquals(3, calls)
        assertEquals(listOf(3_000L, 1_500L), waits)
    }

    @Test fun `zip start is never retried and backend text is sanitized`() {
        var calls = 0
        val api = client { request -> calls++; response(request, 503, "{\"detail\":\"token=do-not-display\"}") }

        val failure = assertThrows(NetworkException::class.java) { runBlocking { api.startZip(listOf("Compilation")) } }
        assertEquals(1, calls)
        assertTrue(failure.error is VaultError.Server)
        assertFalse(failure.message!!.contains("token"))
        assertFalse(failure.message!!.contains("do-not-display"))
    }

    @Test fun `cross-origin redirects malformed JSON and declared oversized bodies fail validation`() {
        val redirectApi = client { request -> response(request, 302, "", mapOf("Location" to "https://evil.invalid/leak")) }
        val malformedApi = client { request -> response(request, 200, "{broken") }
        val oversizedApi = client { request ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(DeclaredLargeBody()).build()
        }

        listOf(redirectApi, malformedApi, oversizedApi).forEach { api ->
            val failure = assertThrows(NetworkException::class.java) { runBlocking { api.health() } }
            assertTrue(failure.error is VaultError.Validation)
        }
    }

    @Test fun `query and identifier limits fail before network`() {
        assertThrows(IllegalArgumentException::class.java) { CatalogQuery(page = 0) }
        assertThrows(IllegalArgumentException::class.java) { CatalogQuery(pageSize = 101) }
        assertThrows(IllegalArgumentException::class.java) { CatalogQuery(search = "x".repeat(251)) }
        assertThrows(IllegalArgumentException::class.java) { CatalogQuery(search = "bad\nquery") }
        assertThrows(IllegalArgumentException::class.java) { CatalogQuery(category = "other") }
        val api = client { error("network must not be called") }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.zipStatus("not-a-uuid") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.startZip(emptyList()) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.startZip(listOf("a/../b")) } }
    }

    private fun client(
        retryDelay: RetryDelay = RetryDelay { },
        responder: (Request) -> Response,
    ): JuiceWrldApiClient {
        val interceptor = Interceptor { chain -> responder(chain.request()) }
        return JuiceWrldApiClient(
            client = OkHttpClient.Builder().addInterceptor(interceptor).build(),
            retryDelay = retryDelay,
            clock = Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC),
            operationId = { "00000000-0000-4000-8000-000000000001" },
        )
    }

    private fun response(request: Request, code: Int, body: String, headers: Map<String, String> = emptyMap()): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Failure")
            .header("Content-Type", "application/json")
            .body(body.toResponseBody("application/json".toMediaTypeOrNull()))
        headers.forEach(builder::header)
        return builder.build()
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.classLoader!!.getResource(name)).readText()

    private fun Request.bodyText(): String {
        val buffer = Buffer()
        body!!.writeTo(buffer)
        return buffer.readUtf8()
    }

    private class DeclaredLargeBody : ResponseBody() {
        private val source = Buffer().writeUtf8("{}")
        override fun contentType(): MediaType? = "application/json".toMediaTypeOrNull()
        override fun contentLength(): Long = NetworkBounds.ARCHIVE_JSON_BYTES + 1
        override fun source(): BufferedSource = source
    }
}
