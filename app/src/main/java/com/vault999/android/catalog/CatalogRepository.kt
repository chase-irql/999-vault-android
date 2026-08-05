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

class CatalogRepository(
    private val dao: SongDao,
    private val api: JuiceWrldApiClient,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    fun observeFirstPage(): Flow<List<CanonicalSong>> = dao.observePage(limit = 100, offset = 0).map { entities -> entities.map(SongEntity::asModel) }

    suspend fun refresh(query: CatalogQuery = CatalogQuery(pageSize = 100)): Int {
        val page = api.songs(query)
        dao.upsertAll(page.songs.map { it.asEntity(nowEpochMs()) })
        return page.songs.size
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

