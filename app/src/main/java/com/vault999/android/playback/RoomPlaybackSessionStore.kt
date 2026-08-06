package com.vault999.android.playback

import com.vault999.android.database.PlaybackSessionEntity
import com.vault999.android.database.QueueDao
import com.vault999.android.database.QueueItemEntity
import com.vault999.android.database.SyncDao
import com.vault999.android.database.ListeningEventEntity
import com.vault999.android.model.PlaybackMode
import com.vault999.android.model.QueueItem
import com.vault999.android.model.QueueSnapshot
import com.vault999.android.model.RepeatMode
import com.vault999.android.model.ListeningCreditTracker
import com.vault999.android.model.PlaybackObservation

class RoomPlaybackSessionStore(
    private val dao: QueueDao,
    private val sync: SyncDao,
    private val now: () -> Long = System::currentTimeMillis,
) : PlaybackSessionStore {
    private var trackedMediaId: String? = null
    private var creditTracker: ListeningCreditTracker? = null
    override suspend fun restore(): QueueSnapshot? {
        val session = dao.session() ?: return null
        val items = dao.items().map(QueueItemEntity::asModel)
        if (items.isEmpty()) return null
        return QueueSnapshot(
            items = items,
            currentIndex = session.currentIndex.coerceIn(items.indices),
            positionMs = session.positionMs.coerceAtLeast(0),
            shuffle = session.shuffle,
            repeatMode = runCatching { RepeatMode.valueOf(session.repeatMode) }.getOrDefault(RepeatMode.OFF),
            playbackMode = runCatching { PlaybackMode.valueOf(session.playbackMode) }.getOrDefault(PlaybackMode.EXPLICIT_QUEUE),
            historyMediaIds = session.historyJson.split(SEPARATOR).filter(String::isNotBlank),
        )
    }

    override suspend fun persist(snapshot: QueueSnapshot) {
        if (snapshot.items.isEmpty()) return
        dao.replace(
            snapshot.items.mapIndexed { index, item -> item.asEntity(index) },
            PlaybackSessionEntity(
                currentIndex = snapshot.currentIndex.coerceAtLeast(0),
                positionMs = snapshot.positionMs.coerceAtLeast(0),
                shuffle = snapshot.shuffle,
                repeatMode = snapshot.repeatMode.name,
                playbackMode = snapshot.playbackMode.name,
                historyJson = snapshot.historyMediaIds.joinToString(SEPARATOR.toString()),
                updatedAtEpochMs = now(),
            ),
        )
    }

    override suspend fun observePlayback(snapshot: QueueSnapshot, playing: Boolean, buffering: Boolean, monotonicMs: Long) {
        persist(snapshot)
        val item = snapshot.items.getOrNull(snapshot.currentIndex)
        if (item?.mediaId != trackedMediaId) {
            trackedMediaId = item?.mediaId
            creditTracker = item?.canonicalSongId?.let { songId ->
                item.durationMs?.takeIf { it > 0 }?.let { duration ->
                    ListeningCreditTracker(songId, (duration / 1_000).coerceAtLeast(1), snapshot.playbackMode.name.lowercase())
                }
            }
        }
        val event = creditTracker?.observe(
            PlaybackObservation(monotonicMs, snapshot.positionMs, playing, preloading = buffering),
            now(),
        ) ?: return
        sync.insertEvents(
            listOf(
                ListeningEventEntity(
                    id = event.id,
                    songId = event.songId,
                    playedAtEpochMs = event.playedAtEpochMs,
                    listenedSeconds = event.listenedSeconds,
                    durationSeconds = event.durationSeconds,
                    source = event.source,
                    acknowledged = false,
                ),
            ),
        )
    }

    companion object { private const val SEPARATOR = '\u001F' }
}

private fun QueueItem.asEntity(index: Int) = QueueItemEntity(
    mediaId = mediaId,
    position = index,
    title = title,
    artist = artist,
    uri = uri,
    artworkUri = artworkUri,
    durationMs = durationMs,
    canonicalSongId = canonicalSongId,
    local = local,
    available = available,
)

private fun QueueItemEntity.asModel() = QueueItem(
    mediaId = mediaId,
    title = title,
    artist = artist,
    uri = uri,
    artworkUri = artworkUri,
    durationMs = durationMs,
    canonicalSongId = canonicalSongId,
    local = local,
    available = available,
)
