package com.vault999.android.archive

import com.vault999.android.database.ArchiveDao
import com.vault999.android.database.ArchiveEntryEntity
import com.vault999.android.database.RadioCacheEntity
import com.vault999.android.model.ArchiveEntry
import com.vault999.android.model.ArchiveKind
import com.vault999.android.network.JuiceWrldApiClient
import com.vault999.android.network.RadioStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ArchiveRepository(
    private val dao: ArchiveDao,
    private val api: JuiceWrldApiClient,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    fun observeFolder(path: String): Flow<List<ArchiveEntry>> = dao.observeFolder(path.trim('/')).map { list -> list.map(ArchiveEntryEntity::asModel) }
    fun search(query: String): Flow<List<ArchiveEntry>> = dao.observeSearch(query.trim()).map { list -> list.map(ArchiveEntryEntity::asModel) }
    fun observeRadio(): Flow<RadioStatus?> = dao.observeRadio().map { it?.asModel() }

    suspend fun refreshIndex(): Int {
        val remote = api.listAllFiles().items
        val all = linkedMapOf<String, ArchiveEntry>()
        remote.forEach { entry ->
            all[entry.path] = entry
            val segments = entry.path.trim('/').split('/').filter(String::isNotBlank)
            for (index in 1 until segments.size) {
                val directoryPath = segments.take(index).joinToString("/")
                all.putIfAbsent(directoryPath, ArchiveEntry(directoryPath, segments[index - 1], ArchiveKind.DIRECTORY))
            }
        }
        val now = nowEpochMs()
        dao.replaceAll(all.values.map { it.asEntity(now) })
        return all.size
    }

    suspend fun refreshRadio(): RadioStatus = api.radioStatus().also { status ->
        dao.upsertRadio(status.asEntity(nowEpochMs()))
    }
}

private fun ArchiveEntry.asEntity(fetchedAt: Long): ArchiveEntryEntity = ArchiveEntryEntity(
    path = path.trim('/'),
    parentPath = path.trim('/').substringBeforeLast('/', ""),
    name = name,
    kind = kind.name,
    sizeBytes = sizeBytes,
    modifiedAtEpochMs = modifiedAtEpochMs,
    canonicalSongId = canonicalSongId,
    fetchedAtEpochMs = fetchedAt,
)

private fun ArchiveEntryEntity.asModel(): ArchiveEntry = ArchiveEntry(
    path = path,
    name = name,
    kind = runCatching { ArchiveKind.valueOf(kind) }.getOrDefault(ArchiveKind.OTHER),
    sizeBytes = sizeBytes,
    modifiedAtEpochMs = modifiedAtEpochMs,
    canonicalSongId = canonicalSongId,
)

private fun RadioStatus.asEntity(fetchedAt: Long): RadioCacheEntity = RadioCacheEntity(
    station = station,
    state = state,
    live = isLive,
    listenerCount = listenerCount,
    nowTitle = nowPlaying?.title,
    nowArtist = nowPlaying?.artist,
    nowAlbum = nowPlaying?.album,
    elapsedMs = nowPlaying?.elapsedMs,
    durationMs = nowPlaying?.durationMs,
    queueJson = queuePreview.joinToString("\u001F"),
    streamUrl = streamUrl,
    fetchedAtEpochMs = fetchedAt,
)

private fun RadioCacheEntity.asModel(): RadioStatus = RadioStatus(
    station = station,
    state = state,
    isLive = live,
    listenerCount = listenerCount,
    nowPlaying = nowTitle?.let { com.vault999.android.network.RadioTrack(it, nowArtist.orEmpty(), nowAlbum.orEmpty(), elapsedMs, durationMs) },
    upNext = null,
    queuePreview = queueJson.split('\u001F').filter(String::isNotBlank),
    streamUrl = streamUrl,
)

