package com.vault999.android.listen

import com.vault999.android.database.ArchiveDao
import com.vault999.android.database.RadioCacheEntity
import com.vault999.android.model.QueueItem
import com.vault999.android.network.JuiceWrldApiClient
import com.vault999.android.network.RadioStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class RadioStation(
    val station: String,
    val state: String,
    val isLive: Boolean,
    val listenerCount: Long,
    val nowTitle: String?,
    val nowArtist: String?,
    val nowAlbum: String?,
    val elapsedMs: Long?,
    val durationMs: Long?,
    val queuePreview: List<String>,
    val streamUrl: String,
    val fetchedAtEpochMs: Long,
) {
    fun asQueueItem(): QueueItem = QueueItem(
        mediaId = "radio:live",
        title = nowTitle?.takeIf(String::isNotBlank) ?: station,
        artist = nowArtist?.takeIf(String::isNotBlank) ?: "Live radio",
        uri = streamUrl,
        durationMs = durationMs,
        local = false,
        available = streamUrl.isNotBlank(),
    )
}

class RadioRepository(
    private val api: JuiceWrldApiClient,
    private val archiveDao: ArchiveDao,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    fun observe(): Flow<RadioStation?> = archiveDao.observeRadio().map { it?.asStation() }

    suspend fun refresh(): RadioStation {
        val entity = api.radioStatus().asEntity(nowEpochMs())
        archiveDao.upsertRadio(entity)
        return entity.asStation()
    }
}

private const val QUEUE_SEPARATOR = '\u001F'

private fun RadioStatus.asEntity(fetchedAtEpochMs: Long): RadioCacheEntity = RadioCacheEntity(
    station = station,
    state = state,
    live = isLive,
    listenerCount = listenerCount,
    nowTitle = nowPlaying?.title,
    nowArtist = nowPlaying?.artist,
    nowAlbum = nowPlaying?.album,
    elapsedMs = nowPlaying?.elapsedMs,
    durationMs = nowPlaying?.durationMs,
    queueJson = queuePreview.joinToString(QUEUE_SEPARATOR.toString()),
    streamUrl = streamUrl,
    fetchedAtEpochMs = fetchedAtEpochMs,
)

private fun RadioCacheEntity.asStation(): RadioStation = RadioStation(
    station = station,
    state = state,
    isLive = live,
    listenerCount = listenerCount,
    nowTitle = nowTitle,
    nowArtist = nowArtist,
    nowAlbum = nowAlbum,
    elapsedMs = elapsedMs,
    durationMs = durationMs,
    queuePreview = queueJson.split(QUEUE_SEPARATOR).filter(String::isNotBlank),
    streamUrl = streamUrl,
    fetchedAtEpochMs = fetchedAtEpochMs,
)
