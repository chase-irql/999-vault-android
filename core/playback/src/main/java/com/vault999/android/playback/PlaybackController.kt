package com.vault999.android.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.vault999.android.model.PlaybackMode
import com.vault999.android.model.QueueItem
import com.vault999.android.model.QueueSnapshot
import com.vault999.android.model.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val POSITION_UPDATE_INTERVAL_MS = 500L
private const val EXTRA_DURATION_MS = "com.vault999.android.playback.duration_ms"
private const val EXTRA_CANONICAL_SONG_ID = "com.vault999.android.playback.canonical_song_id"
private const val EXTRA_LOCAL = "com.vault999.android.playback.local"
private const val EXTRA_AVAILABLE = "com.vault999.android.playback.available"
private const val EXTRA_PLAYBACK_MODE = "com.vault999.android.playback.mode"

data class PlaybackUiState(
    val connected: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val currentMediaId: String? = null,
    val currentItem: QueueItem? = null,
    val queue: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val playbackMode: PlaybackMode = PlaybackMode.EXPLICIT_QUEUE,
    val title: String = "",
    val artist: String = "",
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val volume: Float = 1f,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val error: String? = null,
)

/**
 * Activity-safe façade over the single player owned by [VaultPlaybackService]. Commands issued
 * while MediaController is connecting are retained in order and run once the session is ready.
 */
class PlaybackController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateMutable = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = stateMutable.asStateFlow()
    private val videoPlayerMutable = MutableStateFlow<Player?>(null)
    /** Display-only player handle for Media3's lifecycle-aware Compose video surface. */
    val videoPlayer: StateFlow<Player?> = videoPlayerMutable.asStateFlow()

    private val pendingCommands = ArrayDeque<(MediaController) -> Unit>()
    private var connectionFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    @Volatile private var closed = false

    private val positionPublisher = object : Runnable {
        override fun run() {
            if (closed) return
            val active = controller ?: return
            publish(active)
            mainHandler.postDelayed(this, POSITION_UPDATE_INTERVAL_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            stateMutable.value = stateMutable.value.copy(
                error = "Playback unavailable. Check the connection or codec and retry.",
            )
        }
    }

    fun connect() = mainExecutor.execute(::connectOnMain)

    fun play(items: List<QueueItem>, startIndex: Int = 0, startPositionMs: Long = 0) {
        setQueue(
            QueueSnapshot(items = items, currentIndex = startIndex, positionMs = startPositionMs),
            playWhenReady = true,
        )
    }

    fun setQueue(snapshot: QueueSnapshot, playWhenReady: Boolean = true) = enqueue { player ->
        val prepared = preparePlayableQueue(snapshot.items, snapshot.currentIndex)
        if (prepared.items.isEmpty()) return@enqueue
        player.setMediaItems(
            prepared.items.map { it.asMediaItem(snapshot.playbackMode) },
            prepared.startIndex,
            snapshot.positionMs.coerceAtLeast(0),
        )
        player.shuffleModeEnabled = snapshot.shuffle
        player.repeatMode = snapshot.repeatMode.asPlayerRepeatMode()
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    fun append(items: List<QueueItem>, playbackMode: PlaybackMode = state.value.playbackMode) = enqueue { player ->
        val playable = items.filter(QueueItem::available)
        if (playable.isNotEmpty()) player.addMediaItems(playable.map { it.asMediaItem(playbackMode) })
    }

    fun playNext(items: List<QueueItem>, playbackMode: PlaybackMode = state.value.playbackMode) = enqueue { player ->
        val playable = items.filter(QueueItem::available)
        if (playable.isNotEmpty()) {
            val insertionIndex = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
            player.addMediaItems(insertionIndex, playable.map { it.asMediaItem(playbackMode) })
        }
    }

    fun toggle() = enqueue { player ->
        if (shouldPauseOnToggle(player.playWhenReady, player.playbackState == Player.STATE_BUFFERING)) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun retry() = mainExecutor.execute {
        if (closed) return@execute
        val active = controller
        if (active == null) {
            stateMutable.value = stateMutable.value.copy(error = null)
            connectOnMain()
        } else {
            stateMutable.value = stateMutable.value.copy(error = null)
            active.prepare()
            active.play()
            publish(active)
        }
    }

    fun seekTo(positionMs: Long) = enqueue { it.seekTo(positionMs.coerceAtLeast(0)) }
    fun next() = enqueue(MediaController::seekToNextMediaItem)
    fun previous() = enqueue(MediaController::seekToPreviousMediaItem)
    fun skipTo(index: Int) = enqueue { player ->
        if (index in 0 until player.mediaItemCount) player.seekToDefaultPosition(index)
    }
    fun setShuffle(enabled: Boolean) = enqueue { it.shuffleModeEnabled = enabled }
    fun setRepeat(mode: RepeatMode) = enqueue { it.repeatMode = mode.asPlayerRepeatMode() }
    fun setVolume(volume: Float) = enqueue { it.volume = volume.coerceIn(0f, 1f) }
    fun clearQueue() = enqueue(MediaController::clearMediaItems)

    override fun close() {
        if (closed) return
        closed = true
        mainExecutor.execute {
            mainHandler.removeCallbacks(positionPublisher)
            pendingCommands.clear()
            connectionFuture?.let { if (!it.isDone) it.cancel(true) }
            connectionFuture = null
            controller?.removeListener(listener)
            controller?.release()
            controller = null
            videoPlayerMutable.value = null
            stateMutable.value = PlaybackUiState()
        }
    }

    private fun enqueue(command: (MediaController) -> Unit) = mainExecutor.execute {
        if (closed) return@execute
        controller?.let(command) ?: run {
            pendingCommands.addLast(command)
            connectOnMain()
        }
    }

    private fun connectOnMain() {
        if (closed || controller != null || connectionFuture != null) return
        val token = SessionToken(appContext, ComponentName(appContext, VaultPlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        connectionFuture = future
        future.addListener(
            {
                if (closed) {
                    if (!future.isCancelled) runCatching { future.get().release() }
                    return@addListener
                }
                runCatching { future.get() }.onSuccess { mediaController ->
                    connectionFuture = null
                    controller = mediaController
                    videoPlayerMutable.value = mediaController
                    mediaController.addListener(listener)
                    publish(mediaController)
                    while (pendingCommands.isNotEmpty() && !closed) {
                        pendingCommands.removeFirst().invoke(mediaController)
                    }
                    mainHandler.removeCallbacks(positionPublisher)
                    mainHandler.post(positionPublisher)
                }.onFailure {
                    connectionFuture = null
                    stateMutable.value = stateMutable.value.copy(
                        connected = false,
                        error = "Playback service could not be reached. Retry from Now Playing.",
                    )
                }
            },
            mainExecutor,
        )
    }

    private fun publish(player: Player) {
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET }
        val queue = List(player.mediaItemCount) { player.getMediaItemAt(it).asQueueItem() }
        val current = queue.getOrNull(player.currentMediaItemIndex)?.let { item ->
            if (duration == null) item else item.copy(durationMs = duration)
        }
        stateMutable.value = PlaybackUiState(
            connected = true,
            playing = player.isPlaying,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            currentMediaId = current?.mediaId,
            currentItem = current,
            queue = queue,
            currentIndex = player.currentMediaItemIndex.takeIf { it in queue.indices } ?: -1,
            playbackMode = player.currentMediaItem?.mediaMetadata?.extras
                ?.getString(EXTRA_PLAYBACK_MODE)
                ?.let { runCatching { PlaybackMode.valueOf(it) }.getOrNull() }
                ?: PlaybackMode.EXPLICIT_QUEUE,
            title = current?.title.orEmpty(),
            artist = current?.artist.orEmpty(),
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            volume = player.volume,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode.asRepeatMode(),
            error = stateMutable.value.error,
        )
    }
}

internal fun QueueItem.asMediaItem(playbackMode: PlaybackMode): MediaItem {
    val item = this
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(artworkUri?.let(android.net.Uri::parse))
                .setIsPlayable(true)
                .setExtras(
                    Bundle().apply {
                        item.durationMs?.let { putLong(EXTRA_DURATION_MS, it) }
                        item.canonicalSongId?.let { putLong(EXTRA_CANONICAL_SONG_ID, it) }
                        putBoolean(EXTRA_LOCAL, item.local)
                        putBoolean(EXTRA_AVAILABLE, item.available)
                        putString(EXTRA_PLAYBACK_MODE, playbackMode.name)
                    },
                )
                .build(),
        )
        .build()
}

internal fun MediaItem.asQueueItem(): QueueItem {
    val extras = mediaMetadata.extras
    return QueueItem(
        mediaId = mediaId,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        uri = localConfiguration?.uri?.toString().orEmpty(),
        artworkUri = mediaMetadata.artworkUri?.toString(),
        durationMs = extras?.getLong(EXTRA_DURATION_MS)?.takeIf { it > 0 },
        canonicalSongId = extras?.getLong(EXTRA_CANONICAL_SONG_ID)?.takeIf {
            extras.containsKey(EXTRA_CANONICAL_SONG_ID)
        },
        local = extras?.getBoolean(EXTRA_LOCAL) == true,
        available = extras?.getBoolean(EXTRA_AVAILABLE, true) != false,
    )
}

internal fun RepeatMode.asPlayerRepeatMode(): Int = when (this) {
    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
}

internal fun Int.asRepeatMode(): RepeatMode = when (this) {
    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
    else -> RepeatMode.OFF
}
