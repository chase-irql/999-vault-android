package com.vault999.android.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.vault999.android.model.QueueItem
import com.vault999.android.model.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackUiState(
    val connected: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val currentMediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val error: String? = null,
)

class PlaybackController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val stateMutable = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = stateMutable.asStateFlow()
    private var controller: MediaController? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            stateMutable.value = stateMutable.value.copy(error = "Playback unavailable. Check the connection or codec and retry.")
        }
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(appContext, ComponentName(appContext, VaultPlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener(
            {
                runCatching { future.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    mediaController.addListener(listener)
                    publish(mediaController)
                }.onFailure {
                    stateMutable.value = PlaybackUiState(error = "Playback service could not be reached. Retry from Now Playing.")
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    fun play(items: List<QueueItem>, startIndex: Int = 0, startPositionMs: Long = 0) {
        val player = controller ?: return
        val playable = items.filter { it.available }
        if (playable.isEmpty()) return
        player.setMediaItems(playable.map(QueueItem::asMediaItem), startIndex.coerceIn(playable.indices), startPositionMs.coerceAtLeast(0))
        player.prepare()
        player.play()
    }

    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs.coerceAtLeast(0)) }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }
    fun setShuffle(enabled: Boolean) { controller?.shuffleModeEnabled = enabled }
    fun setRepeat(mode: RepeatMode) {
        controller?.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    override fun close() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        stateMutable.value = PlaybackUiState()
    }

    private fun publish(player: Player) {
        val metadata = player.mediaMetadata
        stateMutable.value = PlaybackUiState(
            connected = true,
            playing = player.isPlaying,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            currentMediaId = player.currentMediaItem?.mediaId,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 && it != C.TIME_UNSET },
            shuffle = player.shuffleModeEnabled,
            repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            },
        )
    }
}

private fun QueueItem.asMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(artworkUri?.let(android.net.Uri::parse))
            .setIsPlayable(true)
            .build(),
    )
    .build()
