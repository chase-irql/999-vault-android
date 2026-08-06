package com.vault999.android.auth

import com.vault999.android.model.CloudLikesSnapshot
import com.vault999.android.model.CloudPlaylist
import com.vault999.android.model.CloudSyncState
import com.vault999.android.model.ListeningEvent
import com.vault999.android.network.ExactOrigin
import com.vault999.android.network.NetworkBounds
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Strict, redirect-free account library transport. It performs no automatic mutation retries. */
class AccountCloudHttpTransport(
    client: OkHttpClient,
    private val origin: ExactOrigin,
) : AccountCloudTransport {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = false; explicitNulls = false }

    override suspend fun likes(accessToken: OpaqueSecret): CloudCallResult<CloudLikesSnapshot> =
        execute(accessToken, Request.Builder().url(origin.route("/v1/library/likes")).get()) { root ->
            val value = root.objectValue()
            val songs = value.array("song_ids").orEmpty().mapNotNull { it.jsonPrimitive.longOrNull?.takeIf { id -> id > 0 } }.toSet()
            val revision = value.text("revision").boundedOpaque("likes revision")
            CloudLikesSnapshot(songs, revision)
        }

    override suspend fun playlists(accessToken: OpaqueSecret): CloudCallResult<List<CloudPlaylist>> =
        execute(accessToken, Request.Builder().url(origin.route("/v1/library/playlists")).get()) { root ->
            val values = when (root) {
                is JsonArray -> root
                is JsonObject -> root.array("playlists") ?: root.array("results") ?: throw IllegalArgumentException("Missing playlists")
                else -> throw IllegalArgumentException("Invalid playlists")
            }
            require(values.size <= 2_000) { "Too many playlists" }
            values.map(::playlistValue)
        }

    override suspend fun playlist(accessToken: OpaqueSecret, playlistId: String): CloudCallResult<CloudPlaylist> =
        execute(accessToken, Request.Builder().url(playlistRoute(playlistId)).get(), ::playlistValue)

    override suspend fun execute(accessToken: OpaqueSecret, mutation: CloudMutation): CloudCallResult<CloudPlaylist?> {
        val builder = Request.Builder().header("Idempotency-Key", mutation.idempotencyKey)
        val expectsPlaylist: Boolean
        when (mutation.operation) {
            CloudMutationOperation.SET_LIKE -> {
                val route = origin.route("/v1/library/likes/${requireNotNull(mutation.songId)}")
                builder.url(route)
                if (mutation.desired == true) builder.put(EMPTY_BODY) else builder.delete()
                expectsPlaylist = false
            }
            CloudMutationOperation.CREATE_PLAYLIST -> {
                builder.url(origin.route("/v1/library/playlists")).post(
                    body(
                        jsonObject(
                            "name" to mutation.name,
                            "description" to mutation.description.orEmpty(),
                            "client_migration_id" to mutation.clientMigrationId,
                        ),
                    ),
                )
                expectsPlaylist = true
            }
            CloudMutationOperation.UPDATE_PLAYLIST -> {
                builder.url(playlistRoute(requireNotNull(mutation.playlistCloudId))).patch(
                    body(
                        jsonObject(
                            "name" to mutation.name,
                            "description" to mutation.description,
                            "base_revision" to mutation.baseRevision,
                        ),
                    ),
                )
                expectsPlaylist = true
            }
            CloudMutationOperation.DELETE_PLAYLIST -> {
                builder.url(playlistRoute(requireNotNull(mutation.playlistCloudId))).delete()
                expectsPlaylist = false
            }
            CloudMutationOperation.SET_PLAYLIST_SONG -> {
                val route = playlistRoute(requireNotNull(mutation.playlistCloudId)).newBuilder()
                    .addPathSegment("songs")
                    .addPathSegment(requireNotNull(mutation.songId).toString())
                    .build()
                builder.url(route)
                if (mutation.desired == true) {
                    builder.put(body(jsonObject("base_revision" to mutation.baseRevision)))
                } else {
                    // The owned Worker intentionally does not parse DELETE bodies.
                    builder.delete()
                }
                expectsPlaylist = true
            }
            CloudMutationOperation.REORDER_PLAYLIST -> {
                val route = playlistRoute(requireNotNull(mutation.playlistCloudId)).newBuilder().addPathSegment("order").build()
                val encodedIds = mutation.songIds.joinToString(prefix = "[", postfix = "]")
                val encodedRevision = Json.encodeToString(requireNotNull(mutation.baseRevision))
                builder.url(route).put(body("{\"song_ids\":$encodedIds,\"base_revision\":$encodedRevision}"))
                expectsPlaylist = true
            }
        }
        return execute(accessToken, builder) { root ->
            if (expectsPlaylist) playlistValue(root) else null
        }
    }

    suspend fun uploadListeningEvents(accessToken: OpaqueSecret, events: List<ListeningEvent>): CloudCallResult<Set<String>> {
        require(events.size <= 500) { "Listening-event batches are limited to 500" }
        val encodedEvents = events.joinToString(prefix = "[", postfix = "]") { event ->
            require(event.id.matches(UUID_PATTERN) && event.songId > 0 && event.playedAtEpochMs > 0)
            "{" + listOf(
                "\"id\":" + Json.encodeToString(event.id.lowercase()),
                "\"songId\":${event.songId}",
                "\"timestamp\":" + Json.encodeToString(Instant.ofEpochMilli(event.playedAtEpochMs).toString()),
                "\"listenedSeconds\":${event.listenedSeconds.coerceIn(0, 86_400)}",
                "\"durationSeconds\":${event.durationSeconds.coerceIn(1, 86_400)}",
                "\"source\":" + Json.encodeToString(event.source.takeIf(SOURCES::contains) ?: "unknown"),
            ).joinToString(",") + "}"
        }
        return execute(
            accessToken,
            Request.Builder().url(origin.route("/v1/listening/events/batch")).post(body("{\"events\":$encodedEvents}")),
        ) { root ->
            val ids = root.objectValue().array("acknowledged_event_ids") ?: throw IllegalArgumentException("Missing acknowledgements")
            require(ids.size <= 500)
            ids.map { it.jsonPrimitive.contentOrNull?.lowercase()?.takeIf(UUID_PATTERN::matches) ?: throw IllegalArgumentException("Invalid acknowledgement") }.toSet()
        }
    }

    suspend fun listeningEvents(accessToken: OpaqueSecret, cursor: String? = null): CloudCallResult<ListeningEventPage> {
        require(cursor == null || (cursor.length <= 512 && cursor.none(Char::isISOControl))) { "Invalid listening cursor" }
        val url = origin.route("/v1/listening/events").newBuilder().apply {
            cursor?.takeIf(String::isNotBlank)?.let { addQueryParameter("cursor", it) }
        }.build()
        return execute(accessToken, Request.Builder().url(url).get()) { root ->
            val value = root.objectValue()
            val events = value.array("events") ?: throw IllegalArgumentException("Missing listening events")
            require(events.size <= 500)
            ListeningEventPage(
                events = events.map { eventValue(it.objectValue()) },
                nextCursor = value.text("next_cursor")?.takeIf(String::isNotBlank)?.boundedCursor(),
            )
        }
    }

    private suspend fun <T> execute(
        accessToken: OpaqueSecret,
        request: Request.Builder,
        decoder: (JsonElement) -> T,
    ): CloudCallResult<T> {
        val built = accessToken.use { raw ->
            request.header("Authorization", "Bearer $raw").header("Accept", JSON_MEDIA).build()
        }
        val response = try {
            http.newCall(built).await()
        } catch (_: IOException) {
            return CloudCallResult.Retryable("offline")
        }
        return response.use { value ->
            if (value.isRedirect) return@use CloudCallResult.Rejected("redirect_rejected", value.code)
            val bytes = try {
                readBounded(value, if (value.isSuccessful) NetworkBounds.ACCOUNT_JSON_BYTES else NetworkBounds.ERROR_BODY_BYTES)
            } catch (_: Exception) {
                return@use CloudCallResult.Rejected("invalid_response", value.code)
            }
            val root = if (bytes.isEmpty()) JsonNull else try {
                json.parseToJsonElement(strictUtf8(bytes))
            } catch (_: Exception) {
                return@use CloudCallResult.Rejected("invalid_response", value.code)
            } finally {
                bytes.fill(0)
            }
            when {
                value.isSuccessful -> try {
                    CloudCallResult.Success(decoder(root))
                } catch (_: IllegalArgumentException) {
                    CloudCallResult.Rejected("invalid_response", value.code)
                }
                value.code == 401 -> CloudCallResult.Unauthorized
                value.code == 409 -> CloudCallResult.Conflict
                value.code == 429 || value.code in 500..599 -> {
                    val code = errorCode(root, if (value.code == 429) "rate_limited" else "service_failure")
                    CloudCallResult.Retryable(code, retryAfter(root, value))
                }
                else -> CloudCallResult.Rejected(errorCode(root, "http_error"), value.code)
            }
        }
    }

    private fun playlistValue(root: JsonElement): CloudPlaylist {
        val value = root.objectValue()
        val id = value.text("id").boundedOpaque("playlist id")
        val name = value.text("name")?.trim().orEmpty()
        require(name.length in 1..80) { "Invalid playlist name" }
        val description = value.text("description")?.trim().orEmpty()
        require(description.length <= 500) { "Invalid playlist description" }
        val songIds = value.array("song_ids").orEmpty().map { element ->
            element.jsonPrimitive.longOrNull?.takeIf { it > 0 } ?: throw IllegalArgumentException("Invalid song ID")
        }
        require(songIds.size <= 10_000 && songIds.distinct().size == songIds.size) { "Invalid playlist songs" }
        val cover = value.text("cover_url")?.takeIf(String::isNotBlank)?.let(::safeHttpsUrl)
        return CloudPlaylist(
            id = id,
            clientMigrationId = value.text("client_migration_id")?.takeIf(String::isNotBlank)?.boundedOpaque("migration id"),
            name = name,
            description = description,
            coverUrl = cover,
            songIds = songIds,
            revision = value.text("revision").boundedOpaque("playlist revision"),
            createdAtEpochMs = parseInstant(value.text("created_at")),
            updatedAtEpochMs = parseInstant(value.text("updated_at")),
            syncState = CloudSyncState.SYNCED,
        )
    }

    private fun eventValue(value: JsonObject): ListeningEvent {
        val id = value.text("id")?.lowercase()?.takeIf(UUID_PATTERN::matches) ?: throw IllegalArgumentException("Invalid event ID")
        val songId = (value["songId"] ?: value["song_id"])?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Invalid event song")
        val listened = (value["listenedSeconds"] ?: value["listened_seconds"])?.jsonPrimitive?.longOrNull?.takeIf { it in 0..86_400 }
            ?: throw IllegalArgumentException("Invalid listened seconds")
        val duration = (value["durationSeconds"] ?: value["duration_seconds"])?.jsonPrimitive?.longOrNull?.takeIf { it in 1..86_400 }
            ?: throw IllegalArgumentException("Invalid duration")
        val source = value.text("source")?.takeIf(SOURCES::contains) ?: "unknown"
        return ListeningEvent(id, songId, parseInstant(value.text("timestamp") ?: value.text("played_at")), listened, duration, source, acknowledged = true)
    }

    private fun playlistRoute(id: String): HttpUrl {
        val safe = id.boundedOpaque("playlist id")
        return origin.route("/v1/library/playlists").newBuilder().addPathSegment(safe).build()
    }

    private fun readBounded(response: Response, limit: Long): ByteArray {
        if (response.body.contentLength() > limit) throw IOException("Response too large")
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024).toInt())
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        response.body.byteStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw IOException("Response too large")
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun strictUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private fun errorCode(root: JsonElement, fallback: String): String = (root as? JsonObject)?.text("code")
        ?.takeIf { SAFE_CODE.matches(it) }
        ?: fallback

    private fun retryAfter(root: JsonElement, response: Response): Long? =
        (root as? JsonObject)?.get("retry_after_seconds")?.jsonPrimitive?.longOrNull?.takeIf { it in 0..86_400 }
            ?: response.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it in 0..86_400 }

    private fun safeHttpsUrl(input: String): String {
        require(input.length <= 2_048) { "Cover URL too long" }
        val uri = URI(input)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null) { "Unsafe cover URL" }
        return uri.toString()
    }

    private fun parseInstant(value: String?): Long = try {
        Instant.parse(value ?: throw IllegalArgumentException("Missing timestamp")).toEpochMilli()
    } catch (failure: Exception) {
        throw IllegalArgumentException("Invalid timestamp", failure)
    }

    private fun jsonObject(vararg fields: Pair<String, String?>): String = fields
        .filter { it.second != null }
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${Json.encodeToString(key)}:${Json.encodeToString(requireNotNull(value))}"
        }

    private fun body(value: String) = value.toRequestBody(JSON_MEDIA.toMediaType())

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

    private fun JsonElement.objectValue(): JsonObject = this as? JsonObject ?: throw IllegalArgumentException("Expected object")
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
    private fun String?.boundedOpaque(label: String): String {
        val value = this?.trim().orEmpty()
        require(value.isNotEmpty() && value.length <= 128 && value.none(Char::isISOControl)) { "Invalid $label" }
        return value
    }

    private fun String.boundedCursor(): String {
        require(length <= 512 && none(Char::isISOControl)) { "Invalid listening cursor" }
        return this
    }

    private companion object {
        const val JSON_MEDIA = "application/json"
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        val SAFE_CODE = Regex("[A-Za-z0-9_.-]{1,64}")
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        val SOURCES = setOf("catalog", "playlist", "radio", "downloaded", "files", "queue", "unknown", "explicit_queue", "listen")
    }
}
