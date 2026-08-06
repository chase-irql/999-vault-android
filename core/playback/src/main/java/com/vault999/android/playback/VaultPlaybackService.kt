package com.vault999.android.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import android.os.SystemClock
import com.vault999.android.model.PlaybackMode
import com.vault999.android.model.QueueSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VaultPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingSnapshots = Channel<PlaybackPersistenceUpdate>(Channel.CONFLATED)
    private var sessionStore: PlaybackSessionStore = NoOpPlaybackSessionStore
    private var restoring = true

    private val persistenceListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!restoring) pendingSnapshots.trySend(player.persistenceUpdate())
        }
    }

    override fun onCreate() {
        super.onCreate()
        sessionStore = (application as? PlaybackSessionStoreOwner)?.playbackSessionStore
            ?: NoOpPlaybackSessionStore
        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            playWhenReady = false
            addListener(persistenceListener)
        }
        mediaSession = MediaSession.Builder(this, player).build()

        serviceScope.launch(Dispatchers.IO) {
            for (update in pendingSnapshots) runCatching {
                sessionStore.observePlayback(update.snapshot, update.playing, update.buffering, update.monotonicMs)
            }
        }
        serviceScope.launch {
            val restored = runCatching { withContext(Dispatchers.IO) { sessionStore.restore() } }.getOrNull()
            if (restored != null && player.mediaItemCount == 0) {
                val prepared = preparePlayableQueue(restored.items, restored.currentIndex)
                if (prepared.items.isNotEmpty()) {
                    player.setMediaItems(
                        prepared.items.map { it.asMediaItem(restored.playbackMode) },
                        prepared.startIndex,
                        restored.positionMs.coerceAtLeast(0),
                    )
                    player.shuffleModeEnabled = restored.shuffle
                    player.repeatMode = restored.repeatMode.asPlayerRepeatMode()
                    player.prepare()
                    player.playWhenReady = false
                }
            }
            restoring = false
            if (player.mediaItemCount > 0) pendingSnapshots.trySend(player.persistenceUpdate())
        }
        serviceScope.launch {
            while (isActive) {
                delay(2_000)
                if (!restoring && player.mediaItemCount > 0) {
                    pendingSnapshots.trySend(player.persistenceUpdate())
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            pendingSnapshots.trySend(player.persistenceUpdate())
            player.removeListener(persistenceListener)
            player.release()
            release()
        }
        mediaSession = null
        pendingSnapshots.close()
        serviceScope.cancel()
        super.onDestroy()
    }
}

private data class PlaybackPersistenceUpdate(
    val snapshot: QueueSnapshot,
    val playing: Boolean,
    val buffering: Boolean,
    val monotonicMs: Long,
)

private fun Player.persistenceUpdate() = PlaybackPersistenceUpdate(
    snapshot = queueSnapshot(),
    playing = isPlaying,
    buffering = playbackState == Player.STATE_BUFFERING,
    monotonicMs = SystemClock.elapsedRealtime(),
)

private fun Player.queueSnapshot(): QueueSnapshot {
    val items = List(mediaItemCount) { getMediaItemAt(it).asQueueItem() }
    val mode = currentMediaItem?.mediaMetadata?.extras?.getString(
        "com.vault999.android.playback.mode",
    )?.let { runCatching { PlaybackMode.valueOf(it) }.getOrNull() } ?: PlaybackMode.EXPLICIT_QUEUE
    return QueueSnapshot(
        items = items,
        currentIndex = currentMediaItemIndex.takeIf { it in items.indices } ?: -1,
        positionMs = currentPosition.coerceAtLeast(0),
        shuffle = shuffleModeEnabled,
        repeatMode = repeatMode.asRepeatMode(),
        playbackMode = mode,
    )
}
