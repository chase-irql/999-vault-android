package com.vault999.android.network

import com.vault999.android.model.VaultError
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class JuiceWrldApiClient(
    client: OkHttpClient,
    private val exactOrigin: ExactOrigin = ExactOrigin(PRODUCTION_ORIGIN.toHttpUrl()),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val retryDelay: RetryDelay = RetryDelay.DEFAULT,
    private val clock: Clock = Clock.systemUTC(),
    private val operationId: () -> String = { UUID.randomUUID().toString() },
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = false; explicitNulls = false }
    private val normalizer = JsonNormalizer(exactOrigin, API_ROOT)

    suspend fun health(): ApiHealth = normalize("health") { normalizer.health(getJson("health", "$API_ROOT/health/")) }

    suspend fun stats(): ApiStats = normalize("stats") { normalizer.stats(getJson("stats", "$API_ROOT/stats/")) }

    suspend fun songs(query: CatalogQuery = CatalogQuery()): CatalogPage =
        normalize("songs") { normalizer.catalog(getJson("songs", "$API_ROOT/songs/", query.parameters()), query) }

    suspend fun song(id: Long): SongDetail {
        require(id > 0) { "song id must be positive" }
        return normalize("song_detail") { normalizer.songDetail(getJson("song_detail", "$API_ROOT/songs/$id/")) }
    }

    suspend fun lyricsSearch(query: LyricsSearchQuery): LyricsSearchPage = normalize("lyrics_search") {
        normalizer.lyricsSearch(getJson("lyrics_search", "$API_ROOT/songs/", query.parameters()), query)
    }

    suspend fun lyricsSearch(query: String, page: Int = 1, pageSize: Int = 30): LyricsSearchPage =
        lyricsSearch(LyricsSearchQuery(query, page, pageSize))

    suspend fun eras(page: Int = 1): EraPage {
        require(page in 1..100) { "page must be between 1 and 100" }
        return normalize("eras") { normalizer.eras(getJson("eras", "$API_ROOT/eras/", mapOf("page" to page.toString()))) }
    }

    suspend fun browseFiles(path: String = "", search: String = ""): ArchiveListing {
        require(safePath(path, allowEmpty = true) != null) { "Invalid archive path" }
        validateSearch(search)
        val query = linkedMapOf("path" to path.replace('\\', '/'))
        normalizedQuery(search).takeIf(String::isNotEmpty)?.let { query["search"] = it }
        return normalize("files_browse") { normalizer.archive(getJson("files_browse", "$API_ROOT/files/browse/", query)) }
    }

    suspend fun listAllFiles(): ArchiveListing = normalize("files_list") { normalizer.archive(getJson("files_list", "$API_ROOT/files/list-all/")) }

    fun fileDownloadUrl(path: String): String = fileUrl("download", path)

    fun coverArtUrl(path: String): String = fileUrl("cover-art", path)

    suspend fun radioStatus(): RadioStatus = normalize("radio_live") { normalizer.radio(getJson("radio_live", "$API_ROOT/radio/live/")) }

    suspend fun randomRadio(): RadioSelection = normalize("radio_random") { normalizer.radioSelection(getJson("radio_random", "$API_ROOT/radio/random/")) }

    fun radioStreamUrl(): String = exactOrigin.route("$API_ROOT/radio/stream.mp3").toString()

    suspend fun startZip(paths: List<String>): ZipJob {
        val safePaths = validatedPaths(paths)
        val body = buildString {
            append("{\"paths\":[")
            safePaths.forEachIndexed { index, path ->
                if (index > 0) append(',')
                append(Json.encodeToString(path))
            }
            append("]}")
        }
        return normalize("zip_start") { normalizer.zipJob(postJson("zip_start", "$API_ROOT/start-zip-job/", body)) }
    }

    suspend fun zipStatus(jobId: String): ZipJob {
        require(validJobId(jobId)) { "Invalid ZIP job identifier" }
        return normalize("zip_status") { normalizer.zipJob(getJson("zip_status", "$API_ROOT/zip-job-status/$jobId/"), jobId) }
    }

    suspend fun cancelZip(jobId: String): ZipJob {
        require(validJobId(jobId)) { "Invalid ZIP job identifier" }
        return normalize("zip_cancel") {
            val response = postJson("zip_cancel", "$API_ROOT/cancel-zip-job/$jobId/", null)
            if (response is JsonNull) ZipJob(jobId, ZipJobState.CANCELLED, 0.0) else normalizer.zipJob(response, jobId)
        }
    }

    private fun validatedPaths(paths: List<String>): List<String> {
        require(paths.isNotEmpty() && paths.size <= 1_000) { "ZIP selection must contain 1 to 1000 paths" }
        val safe = paths.map { safePath(it) ?: throw IllegalArgumentException("Invalid archive path") }.distinct()
        require(safe.isNotEmpty()) { "ZIP selection cannot be empty" }
        require(safe.sumOf(String::length) <= 256_000) { "ZIP selection is too large" }
        return safe
    }

    private fun fileUrl(route: String, path: String): String {
        val safe = safePath(path) ?: throw IllegalArgumentException("Invalid archive path")
        return exactOrigin.route("$API_ROOT/files/$route/", mapOf("path" to safe)).toString()
    }

    private suspend fun getJson(route: String, path: String, query: Map<String, String> = emptyMap()): JsonElement =
        executeJson(route, Request.Builder().url(exactOrigin.route(path, query)).get().header("Accept", JSON_MEDIA).build(), retryable = true)

    private suspend fun postJson(route: String, path: String, body: String?): JsonElement {
        val requestBody = body?.toRequestBody(JSON_MEDIA.toMediaType()) ?: ByteArray(0).toRequestBody(null)
        val request = Request.Builder().url(exactOrigin.route(path)).post(requestBody).header("Accept", JSON_MEDIA).build()
        return executeJson(route, request, retryable = false)
    }

    private suspend fun executeJson(route: String, request: Request, retryable: Boolean): JsonElement {
        val op = operationId()
        var attempt = 1
        while (true) {
            val response = try {
                http.newCall(request).await()
            } catch (exception: IOException) {
                if (retryable && attempt < retryPolicy.maxAttempts) {
                    retryDelay.wait(retryPolicy.delayMs(attempt++, null, clock.instant()))
                    continue
                }
                val error = if (exception is SocketTimeoutException) VaultError.Timeout(op) else VaultError.Offline(op)
                throw NetworkException(error, route)
            }

            try {
                response.use { value ->
                    if (value.isRedirect) throw NetworkException(VaultError.Validation(op), route, value.code)
                    if (!value.isSuccessful) {
                        readBounded(value, NetworkBounds.ERROR_BODY_BYTES, op, route)
                        if (retryable && attempt < retryPolicy.maxAttempts && (value.code == 429 || value.code in 500..599)) {
                            val wait = retryPolicy.delayMs(attempt++, value.headers, clock.instant())
                            retryDelay.wait(wait)
                            continue
                        }
                        throw httpFailure(value.code, value.headers["Retry-After"], op, route)
                    }
                    val bytes = readBounded(value, NetworkBounds.ARCHIVE_JSON_BYTES, op, route)
                    if (value.code == 204 || bytes.isEmpty()) return JsonNull
                    if (!value.header("Content-Type").orEmpty().lowercase().contains("json")) {
                        throw NetworkException(VaultError.Validation(op), route, value.code)
                    }
                    return try {
                        val text = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString()
                        json.parseToJsonElement(text)
                    } catch (_: Exception) {
                        throw NetworkException(VaultError.Validation(op), route, value.code)
                    }
                }
            } catch (failure: NetworkException) {
                throw failure
            } catch (failure: IOException) {
                if (retryable && attempt < retryPolicy.maxAttempts) {
                    retryDelay.wait(retryPolicy.delayMs(attempt++, null, clock.instant()))
                    continue
                }
                val error = if (failure is SocketTimeoutException) VaultError.Timeout(op) else VaultError.Offline(op)
                throw NetworkException(error, route)
            }
        }
    }

    private suspend inline fun <T> normalize(route: String, crossinline block: suspend () -> T): T = try {
        block()
    } catch (failure: NetworkException) {
        throw failure
    } catch (_: IllegalArgumentException) {
        throw NetworkException(VaultError.Validation(operationId()), route)
    }

    private fun readBounded(response: Response, limit: Long, op: String, route: String): ByteArray {
        val body = response.body
        if (body.contentLength() > limit) throw NetworkException(VaultError.Validation(op), route, response.code)
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024).toInt())
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        val input = body.byteStream()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw NetworkException(VaultError.Validation(op), route, response.code)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun httpFailure(status: Int, retryAfter: String?, op: String, route: String): NetworkException {
        val error = when (status) {
            429 -> VaultError.RateLimited(op, retryAfter?.trim()?.toLongOrNull())
            in 500..599 -> VaultError.Server(op, status)
            else -> VaultError.Validation(op)
        }
        return NetworkException(error, route, status)
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    companion object {
        const val PRODUCTION_ORIGIN = "https://juicewrldapi.com"
        const val API_ROOT = "/juicewrld"
        private const val JSON_MEDIA = "application/json"
    }
}
