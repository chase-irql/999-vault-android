package com.vault999.android.catalog

import com.vault999.android.database.SongDao
import com.vault999.android.database.SongEntity
import com.vault999.android.model.CanonicalSong
import com.vault999.android.model.Era
import com.vault999.android.model.SongCategory
import com.vault999.android.network.CatalogQuery
import com.vault999.android.network.JuiceWrldApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class CatalogDashboard(
    val healthy: Boolean,
    val healthStatus: String,
    val totalSongs: Long,
    val categoryCounts: Map<String, Long>,
)

class CatalogRepository(
    private val dao: SongDao,
    private val api: JuiceWrldApiClient,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    fun observeFirstPage(): Flow<List<CanonicalSong>> = dao.observePage(limit = MAX_CATALOG_SONGS, offset = 0).map { entities -> entities.map(SongEntity::asModel) }

    suspend fun refresh(query: CatalogQuery = CatalogQuery(pageSize = 100)): Int {
        val songsById = linkedMapOf<Long, CanonicalSong>()
        var pageNumber = query.page
        repeat(MAX_CATALOG_PAGES) {
            val page = api.songs(query.copy(page = pageNumber))
            page.songs.forEach { songsById[it.id] = it }
            check(songsById.size <= MAX_CATALOG_SONGS) { "Catalog exceeded the Android cache limit" }
            if (!page.hasNext) {
                val fetchedAt = nowEpochMs()
                val ordered = songsById.values.sortedWith(compareBy<CanonicalSong> { it.publicNumber }.thenBy { it.id })
                dao.replaceAll(ordered.map { it.asEntity(fetchedAt) })
                return ordered.size
            }
            pageNumber++
        }
        error("Catalog pagination exceeded $MAX_CATALOG_PAGES pages")
    }

    suspend fun dashboard(): CatalogDashboard {
        val health = api.health()
        val stats = api.stats()
        return CatalogDashboard(health.healthy, health.status, stats.totalSongs, stats.categoryCounts)
    }

    suspend fun randomSong(): CanonicalSong? = api.randomRadio().song

    suspend fun hydrateByCanonicalIds(songIds: Collection<Long>): Int = coroutineScope {
        val limiter = Semaphore(HYDRATION_CONCURRENCY)
        val songs = songIds.asSequence()
            .filter { it > 0 }
            .distinct()
            .take(MAX_HYDRATION_SONGS)
            .map { id ->
                async {
                    limiter.withPermit { runCatching { api.song(id).song }.getOrNull() }
                }
            }
            .toList()
            .awaitAll()
            .filterNotNull()
            .distinctBy(CanonicalSong::id)
        if (songs.isNotEmpty()) {
            val fetchedAt = nowEpochMs()
            dao.upsertAll(songs.map { it.asEntity(fetchedAt) })
        }
        songs.size
    }

    private companion object {
        const val MAX_CATALOG_PAGES = 100
        const val MAX_CATALOG_SONGS = 10_000
        const val MAX_HYDRATION_SONGS = 500
        const val HYDRATION_CONCURRENCY = 6
    }
}

private const val SEPARATOR = '\u001F'

private fun CanonicalSong.asEntity(fetchedAt: Long): SongEntity = SongEntity(
    id = id,
    publicNumber = publicNumber,
    title = title,
    aliasesJson = aliases.joinToString(SEPARATOR.toString()),
    archivePath = archivePath,
    artist = artist,
    durationSeconds = durationSeconds,
    category = category.name,
    eraId = era?.id,
    eraName = era?.name,
    artworkUrl = artworkUrl,
    producersJson = producers.joinToString(SEPARATOR.toString()),
    streamUrl = streamUrl,
    fetchedAtEpochMs = fetchedAt,
)

private fun SongEntity.asModel(): CanonicalSong = CanonicalSong(
    id = id,
    publicNumber = publicNumber,
    title = title,
    aliases = aliasesJson.split(SEPARATOR).filter(String::isNotBlank),
    archivePath = archivePath,
    artist = artist,
    durationSeconds = durationSeconds,
    category = runCatching { SongCategory.valueOf(category) }.getOrDefault(SongCategory.UNKNOWN),
    era = eraId?.let { Era(it, eraName ?: "Unknown era") },
    artworkUrl = artworkUrl,
    producers = producersJson.split(SEPARATOR).filter(String::isNotBlank),
    streamUrl = streamUrl,
)
