package com.vault999.android.network

import com.vault999.android.model.ArchiveEntry
import com.vault999.android.model.CanonicalSong
import com.vault999.android.model.Era
import java.text.Normalizer

data class CatalogQuery(
    val page: Int = 1,
    val pageSize: Int = 50,
    val search: String = "",
    val category: String? = null,
    val eraName: String? = null,
) {
    init {
        require(page in 1..10_000) { "page must be between 1 and 10000" }
        require(pageSize in 1..100) { "pageSize must be between 1 and 100" }
        validateSearch(search)
        require(category == null || category in SUPPORTED_CATEGORIES) { "Unsupported category" }
        if (eraName != null) validateTextQuery(eraName, "eraName", 120, allowEmpty = false)
    }

    internal fun parameters(): Map<String, String> = linkedMapOf(
        "page" to page.toString(),
        "page_size" to pageSize.toString(),
    ).apply {
        normalizedQuery(search).takeIf(String::isNotEmpty)?.let { put("searchall", it) }
        category?.let { put("category", it) }
        eraName?.let { put("era", normalizedQuery(it)) }
    }

    companion object {
        val SUPPORTED_CATEGORIES = setOf("released", "unreleased", "unsurfaced", "recording_session")
    }
}

data class CatalogPage(
    val count: Long,
    val page: Int,
    val pageSize: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val songs: List<CanonicalSong>,
)

data class EraPage(val count: Long, val hasNext: Boolean, val eras: List<Era>)

data class SongDetail(val song: CanonicalSong, val lyrics: String)

data class LyricsSearchQuery(
    val query: String,
    val page: Int = 1,
    val pageSize: Int = 30,
) {
    init {
        validateTextQuery(query, "query", 250, allowEmpty = false)
        require(normalizedQuery(query).length >= 2) { "query must contain at least two characters" }
        require(page in 1..10_000) { "page must be between 1 and 10000" }
        require(pageSize in 1..30) { "pageSize must be between 1 and 30" }
    }

    internal fun parameters(): Map<String, String> = linkedMapOf(
        "lyrics" to normalizedQuery(query),
        "page" to page.toString(),
        "page_size" to pageSize.toString(),
        "file_names_array" to "true",
    )
}

data class LyricsHit(
    val song: CanonicalSong,
    val excerpt: String,
    val lyrics: String,
)

data class LyricsSearchPage(
    val count: Long,
    val page: Int,
    val pageSize: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val hits: List<LyricsHit>,
)

data class ApiHealth(val healthy: Boolean, val status: String)

data class ApiStats(
    val totalSongs: Long,
    val categoryCounts: Map<String, Long>,
    val eraCounts: Map<String, Long>,
)

data class ArchiveListing(
    val currentPath: String,
    val pathParts: List<String>,
    val items: List<ArchiveEntry>,
)

data class RadioTrack(
    val title: String,
    val artist: String,
    val album: String,
    val elapsedMs: Long?,
    val durationMs: Long?,
)

data class RadioStatus(
    val station: String,
    val state: String,
    val isLive: Boolean,
    val listenerCount: Long,
    val nowPlaying: RadioTrack?,
    val upNext: RadioTrack?,
    val queuePreview: List<String>,
    val streamUrl: String,
)

data class RadioSelection(
    val path: String,
    val title: String,
    val sizeBytes: Long?,
    val song: CanonicalSong?,
)

enum class ZipJobState { QUEUED, PREPARING, READY, CANCELLED, FAILED, UNKNOWN }

data class ZipJob(
    val id: String,
    val state: ZipJobState,
    val progressPercent: Double,
    val downloadUrl: String? = null,
)

internal fun normalizedQuery(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ")

internal fun validateSearch(value: String) = validateTextQuery(value, "search", 250, allowEmpty = true)

internal fun validateTextQuery(value: String, field: String, maximum: Int, allowEmpty: Boolean) {
    require(value.none { it.code in 0..31 || it.code == 127 }) { "$field contains control characters" }
    val normalized = normalizedQuery(value)
    require(allowEmpty || normalized.isNotEmpty()) { "$field cannot be empty" }
    require(normalized.length <= maximum) { "$field is too long" }
}
