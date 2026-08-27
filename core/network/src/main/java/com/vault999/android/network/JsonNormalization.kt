package com.vault999.android.network

import com.vault999.android.model.ArchiveEntry
import com.vault999.android.model.ArchiveKind
import com.vault999.android.model.CanonicalSong
import com.vault999.android.model.Era
import com.vault999.android.model.SongCategory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.Normalizer

internal class JsonNormalizer(
    private val origin: ExactOrigin,
    private val apiRoot: String,
) {
    fun health(root: JsonElement): ApiHealth {
        val status = clean(root.obj().string("status"), 40, "unknown").lowercase()
        return ApiHealth(status in setOf("ok", "healthy", "up"), status)
    }

    fun stats(root: JsonElement): ApiStats {
        val objectValue = root.obj()
        return ApiStats(
            totalSongs = objectValue.nonNegativeLong("total_songs") ?: 0,
            categoryCounts = boundedCountMap(objectValue["category_stats"]),
            eraCounts = boundedCountMap(objectValue["era_stats"]),
        )
    }

    fun catalog(root: JsonElement, query: CatalogQuery): CatalogPage {
        val objectValue = root as? JsonObject
        val rawItems = when (root) {
            is JsonArray -> root
            is JsonObject -> root.array("results") ?: root.array("items") ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val songs = rawItems.take(query.pageSize).mapNotNull(::songOrNull)
        val rawCount = objectValue?.nonNegativeLong("count")
        return CatalogPage(
            count = rawCount?.coerceAtLeast(songs.size.toLong()) ?: songs.size.toLong(),
            page = query.page,
            pageSize = query.pageSize,
            hasNext = objectValue?.present("next") == true,
            hasPrevious = objectValue?.present("previous") == true || (objectValue == null && query.page > 1),
            songs = songs,
        )
    }

    fun songDetail(root: JsonElement): SongDetail {
        val value = root.obj()
        val song = songOrNull(value) ?: throw IllegalArgumentException("Invalid canonical song")
        return SongDetail(song, cleanMultiline(value.string("lyrics"), 500_000))
    }

    fun lyricsSearch(root: JsonElement, query: LyricsSearchQuery): LyricsSearchPage {
        val objectValue = root as? JsonObject
        val rawItems = when (root) {
            is JsonArray -> root
            is JsonObject -> root.array("results") ?: root.array("items") ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val hits = rawItems.take(query.pageSize).mapNotNull { element ->
            val value = element as? JsonObject ?: return@mapNotNull null
            val song = songOrNull(value) ?: return@mapNotNull null
            val lyrics = cleanMultiline(value.string("lyrics"), 500_000)
            LyricsHit(song, lyricsExcerpt(lyrics), lyrics)
        }
        val count = objectValue?.nonNegativeLong("count")?.coerceAtLeast(hits.size.toLong()) ?: hits.size.toLong()
        return LyricsSearchPage(
            count = count,
            page = query.page,
            pageSize = query.pageSize,
            hasNext = objectValue?.present("next") == true,
            hasPrevious = objectValue?.present("previous") == true || (objectValue == null && query.page > 1),
            hits = hits,
        )
    }

    fun eras(root: JsonElement): EraPage {
        val objectValue = root as? JsonObject
        val rawItems = when (root) {
            is JsonArray -> root
            is JsonObject -> root.array("results") ?: root.array("items") ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val values = rawItems.take(100).mapNotNull { eraOrNull(it as? JsonObject) }
        val count = objectValue?.nonNegativeLong("count")?.coerceAtLeast(values.size.toLong()) ?: values.size.toLong()
        return EraPage(count, objectValue?.present("next") == true, values)
    }

    fun archive(root: JsonElement): ArchiveListing {
        val value = root.obj()
        val currentPath = safePath(value.string("current_path"), allowEmpty = true) ?: ""
        val parts = value.array("path_parts")?.take(128)?.mapNotNull { clean(it.primitiveContent(), 255).takeIf(String::isNotEmpty) }.orEmpty()
        val items = (value.array("items") ?: value.array("results") ?: JsonArray(emptyList()))
            .take(100_000)
            .mapNotNull(::archiveEntryOrNull)
        return ArchiveListing(currentPath, parts, items)
    }

    fun radio(root: JsonElement): RadioStatus {
        val value = root.obj()
        val listeners = listOf(value.nonNegativeLong("total_listeners"), value.nonNegativeLong("web_listeners"), value.nonNegativeLong("discord_listeners"))
            .firstOrNull { it != null } ?: 0
        return RadioStatus(
            station = clean(value.string("station"), 120, "999 FM"),
            state = clean(value.string("state"), 40, "unknown").lowercase(),
            isLive = value.boolean("is_live") ?: false,
            listenerCount = listeners,
            nowPlaying = radioTrack(value["now_playing"] as? JsonObject),
            upNext = radioTrack(value["up_next"] as? JsonObject),
            queuePreview = value.array("queue_preview")?.take(20)?.mapNotNull { clean(it.primitiveContent(), 300).takeIf(String::isNotEmpty) }.orEmpty(),
            streamUrl = origin.route("$apiRoot/radio/stream.mp3").toString(),
        )
    }

    fun radioSelection(root: JsonElement): RadioSelection {
        val value = root.obj()
        val path = safePath(value.string("path") ?: value.string("id")) ?: throw IllegalArgumentException("Invalid radio path")
        return RadioSelection(
            path = path,
            title = clean(value.string("title"), 300, path.substringAfterLast('/')),
            sizeBytes = value.nonNegativeLong("size"),
            song = songOrNull(value["song"]),
        )
    }

    fun zipJob(root: JsonElement, requiredId: String? = null): ZipJob {
        val value = unwrap(root.obj())
        val id = clean(value.string("job_id") ?: value.string("jobId") ?: value.string("id") ?: requiredId, 80)
        require(validJobId(id)) { "Invalid ZIP job identifier" }
        if (requiredId != null) require(id.equals(requiredId, ignoreCase = true)) { "ZIP job identifier mismatch" }
        val rawState = clean(value.string("status") ?: value.string("state") ?: value.string("phase"), 40).lowercase()
        val state = when (rawState) {
            "queued", "pending" -> ZipJobState.QUEUED
            "starting", "started", "accepted", "created", "preparing", "processing", "running", "working" -> ZipJobState.PREPARING
            "done", "complete", "completed", "ready", "success", "succeeded", "finished" -> ZipJobState.READY
            "cancelled", "canceled" -> ZipJobState.CANCELLED
            "failed", "error" -> ZipJobState.FAILED
            else -> ZipJobState.UNKNOWN
        }
        val rawUrl = value.string("download_url") ?: value.string("downloadUrl")
        val downloadUrl = rawUrl?.takeIf(String::isNotBlank)?.let { origin.resolve(it).toString() }
        if (downloadUrl != null) require(downloadUrl.contains("/$id.")) { "ZIP download URL does not match job" }
        return ZipJob(id, state, (value.double("progress") ?: 0.0).coerceIn(0.0, 100.0), downloadUrl)
    }

    private fun unwrap(root: JsonObject): JsonObject = sequenceOf("job", "data", "result")
        .mapNotNull { root[it] as? JsonObject }
        .firstOrNull() ?: root

    private fun songOrNull(element: JsonElement?): CanonicalSong? {
        val value = element as? JsonObject ?: return null
        val id = value.positiveLong("id") ?: return null
        // A small set of valid archive records has no public catalogue number. Their canonical
        // database ID is still stable and is the ID stored by account playlists. Retaining those
        // records is required for synced playlists to resolve and play them.
        val publicNumber = value.positiveLong("public_id") ?: value.positiveLong("publicId") ?: id
        val titles = value.array("track_titles")?.mapNotNull { clean(it.primitiveContent(), 300).takeIf(String::isNotEmpty) }.orEmpty()
        val rawName = clean(value.string("name"), 300)
        val title = titles.firstOrNull() ?: rawName.ifEmpty { "Unknown track" }
        val aliases = (titles + rawName).asSequence()
            .filter(String::isNotEmpty)
            .distinctBy { it.lowercase() }
            .filterNot { it.equals(title, ignoreCase = true) }
            .take(50)
            .toList()
        val path = safePath(value.string("path"))
        val artist = clean(value.string("credited_artists") ?: value.string("artist"), 300, "Juice WRLD")
        val imageUrl = trustedUrl(value.string("image_url") ?: value.string("imageUrl"))
            ?: path?.let { origin.route("$apiRoot/files/cover-art/", mapOf("path" to it)).toString() }
        return CanonicalSong(
            id = id,
            publicNumber = publicNumber,
            title = title,
            aliases = aliases,
            archivePath = path,
            artist = artist,
            durationSeconds = parseDuration(value["length"] ?: value["duration"]),
            category = when (value.string("category")?.lowercase()) {
                "released" -> SongCategory.RELEASED
                "unreleased" -> SongCategory.UNRELEASED
                "unsurfaced" -> SongCategory.UNSURFACED
                "recording_session", "session" -> SongCategory.SESSION
                else -> SongCategory.UNKNOWN
            },
            era = eraOrNull(value["era"] as? JsonObject),
            artworkUrl = imageUrl,
            producers = producers(value["producers"]),
            streamUrl = path?.let { origin.route("$apiRoot/files/download/", mapOf("path" to it)).toString() },
        )
    }

    private fun eraOrNull(value: JsonObject?): Era? {
        value ?: return null
        val id = value.positiveLong("id") ?: return null
        val name = clean(value.string("name"), 120)
        if (name.isEmpty()) return null
        return Era(id, name, clean(value.string("description"), 1_000), clean(value.string("time_frame") ?: value.string("timeFrame"), 200))
    }

    private fun archiveEntryOrNull(element: JsonElement): ArchiveEntry? {
        val value = element as? JsonObject ?: return null
        val path = safePath(value.string("path")) ?: return null
        val name = clean(value.string("name"), 255, path.substringAfterLast('/'))
        val type = value.string("type")?.lowercase()
        val extension = (value.string("extension") ?: path.substringAfterLast('.', "")).lowercase().removePrefix(".")
        val kind = when {
            type == "directory" -> ArchiveKind.DIRECTORY
            extension in setOf("mp3", "m4a", "aac", "ogg", "opus") -> ArchiveKind.AUDIO
            extension in setOf("wav", "flac", "alac", "aiff") -> ArchiveKind.LOSSLESS
            extension in setOf("jpg", "jpeg", "png", "webp", "gif") -> ArchiveKind.ARTWORK
            extension in setOf("mp4", "mkv", "webm", "mov") -> ArchiveKind.VIDEO
            extension in setOf("txt", "lrc", "md", "json") -> ArchiveKind.TEXT
            else -> ArchiveKind.OTHER
        }
        return ArchiveEntry(path, name, kind, value.nonNegativeLong("size"))
    }

    private fun radioTrack(value: JsonObject?): RadioTrack? {
        value ?: return null
        val title = clean(value.string("title"), 300)
        if (title.isEmpty()) return null
        return RadioTrack(
            title,
            clean(value.string("artist"), 300, "Juice WRLD"),
            clean(value.string("album"), 300),
            value.nonNegativeLong("elapsed_ms"),
            value.nonNegativeLong("duration_ms"),
        )
    }

    private fun parseDuration(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        primitive.longOrNull?.let { return it.takeIf { seconds -> seconds in 0..86_400 } }
        val text = primitive.contentOrNull?.trim() ?: return null
        text.toDoubleOrNull()?.let { return it.takeIf { seconds -> seconds in 0.0..86_400.0 }?.toLong() }
        val pieces = text.split(':')
        if (pieces.size !in 2..3 || pieces.any { !it.matches(Regex("\\d{1,3}")) }) return null
        val numbers = pieces.map(String::toLong)
        val seconds = numbers.last()
        val minutes = numbers[numbers.lastIndex - 1]
        val hours = if (numbers.size == 3) numbers.first() else 0
        if (seconds > 59 || minutes > 59 || hours > 24) return null
        return (hours * 3_600 + minutes * 60 + seconds).takeIf { it <= 86_400 }
    }

    private fun producers(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { clean(it.primitiveContent(), 120).takeIf(String::isNotEmpty) }
        is JsonPrimitive -> clean(element.contentOrNull, 1_000).split(',', ';', '\n').map { clean(it, 120) }.filter(String::isNotEmpty)
        else -> emptyList()
    }.distinctBy { it.lowercase() }.take(50)

    private fun trustedUrl(value: String?): String? {
        val input = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2_048 } ?: return null
        return runCatching { origin.resolve(input).toString() }.getOrNull()
    }

    private fun boundedCountMap(element: JsonElement?): Map<String, Long> = (element as? JsonObject)
        ?.entries
        ?.asSequence()
        ?.take(500)
        ?.mapNotNull { (key, raw) -> clean(key, 120).takeIf(String::isNotEmpty)?.let { it to ((raw as? JsonPrimitive)?.longOrNull ?: 0).coerceAtLeast(0) } }
        ?.toMap(linkedMapOf())
        .orEmpty()
}

internal fun safePath(value: String?, allowEmpty: Boolean = false): String? {
    val normalized = value?.trim()?.replace('\\', '/') ?: return null
    if (normalized.isEmpty()) return normalized.takeIf { allowEmpty }
    if (normalized.length > 4_096 || normalized.startsWith('/') || normalized.any(Char::isISOControl)) return null
    if (normalized.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
    return normalized
}

internal fun validJobId(value: String): Boolean = value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))

private fun clean(value: String?, maximum: Int, fallback: String = ""): String {
    val normalized = value?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
        ?.replace(Regex("[\\u0000-\\u001f\\u007f]+"), " ")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        .orEmpty()
    return normalized.take(maximum).ifEmpty { fallback }
}

private fun cleanMultiline(value: String?, maximum: Int): String = value.orEmpty()
    .replace("\u0000", "")
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .take(maximum)

private fun lyricsExcerpt(value: String): String = value
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(300)

private fun JsonElement?.primitiveContent(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: throw IllegalArgumentException("Expected JSON object")
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.positiveLong(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
private fun JsonObject.nonNegativeLong(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.present(key: String): Boolean = this[key] != null && this[key] !is JsonNull
