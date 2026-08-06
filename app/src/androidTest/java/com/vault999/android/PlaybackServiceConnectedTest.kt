package com.vault999.android

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.vault999.android.model.PlaybackMode
import com.vault999.android.model.QueueItem
import com.vault999.android.model.QueueSnapshot
import com.vault999.android.model.RepeatMode
import com.vault999.android.playback.PlaybackController
import com.vault999.android.playback.VaultPlaybackService
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackServiceConnectedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var observer: MediaController
    private lateinit var original: PlayerSnapshot
    private lateinit var firstTone: File
    private lateinit var secondTone: File

    @Before
    fun connectAndPreserveSession() {
        observer = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, VaultPlaybackService::class.java)),
        ).buildAsync().get(10, TimeUnit.SECONDS)
        original = onMain { observer.snapshot() }
        onMain { observer.pause() }
        firstTone = createSilentWave("connected-playback-one.wav")
        secondTone = createSilentWave("connected-playback-two.wav")
    }

    @After
    fun restoreSession() {
        if (::observer.isInitialized) {
            onMain {
                observer.shuffleModeEnabled = original.shuffle
                observer.repeatMode = original.repeatMode
                observer.volume = original.volume
                if (original.items.isEmpty()) {
                    observer.clearMediaItems()
                    observer.pause()
                } else {
                    observer.setMediaItems(
                        original.items,
                        original.currentIndex.coerceIn(original.items.indices),
                        original.positionMs,
                    )
                    observer.prepare()
                    observer.playWhenReady = original.playWhenReady
                }
                observer.release()
            }
        }
        if (::firstTone.isInitialized) firstTone.delete()
        if (::secondTone.isInitialized) secondTone.delete()
    }

    @Test
    fun mediaSessionControllerExercisesTransportAndQueuePolicies() {
        val media = testMediaItems()
        onMain {
            observer.setMediaItems(media)
            observer.prepare()
            observer.play()
        }
        await("first item starts playing") {
            onMain { observer.playWhenReady && observer.currentMediaItem?.mediaId == "test:one" }
        }
        await("first item is prepared before transport assertions") {
            onMain { observer.playbackState == Player.STATE_READY && observer.isPlaying }
        }

        onMain { observer.pause() }
        await("pause clears play intent") { onMain { !observer.playWhenReady } }
        assertFalse(onMain { observer.isPlaying })

        onMain { observer.seekTo(750) }
        await("seek position is published") { onMain { observer.currentPosition in 650L..850L } }

        onMain { observer.seekToNextMediaItem() }
        await("next selects the second item") { onMain { observer.currentMediaItem?.mediaId == "test:two" } }

        onMain {
            observer.repeatMode = Player.REPEAT_MODE_ONE
            observer.shuffleModeEnabled = true
        }
        await("repeat and shuffle propagate through the session") {
            onMain { observer.repeatMode == Player.REPEAT_MODE_ONE && observer.shuffleModeEnabled }
        }
    }

    @Test
    fun recreatedActivityControllerObservesTheSameAuthoritativePlayer() {
        val first = PlaybackController(context)
        val queue = listOf(
            QueueItem("test:one", "Connected Track One", "999 Vault Test", firstTone.toURI().toString()),
            QueueItem("test:two", "Connected Track Two", "999 Vault Test", secondTone.toURI().toString()),
        )
        first.setQueue(
            QueueSnapshot(
                items = queue,
                currentIndex = 0,
                repeatMode = RepeatMode.ALL,
                playbackMode = PlaybackMode.EXPLICIT_QUEUE,
            ),
            playWhenReady = false,
        )
        await("first activity controller receives the service queue") {
            first.state.value.connected && first.state.value.currentItem?.mediaId == "test:one"
        }
        first.setVolume(.35f)
        await("activity volume command reaches the authoritative player") {
            first.state.value.volume in .34f..36f && onMain { observer.volume in .34f..36f }
        }
        first.close()

        ActivityScenario.launch(PlaybackProbeActivity::class.java).use { scenario ->
            await("activity controller restores the current item") {
                scenario.readPlaybackState().currentItem?.mediaId == "test:one"
            }
            scenario.recreate()
            await("recreated activity observes the unchanged service queue") {
                val state = scenario.readPlaybackState()
                state.currentItem?.mediaId == "test:one" &&
                    state.queue.map(QueueItem::mediaId) == listOf("test:one", "test:two")
            }
            scenario.onActivity { it.playbackController.next() }
            await("commands from the recreated controller reach the same player") {
                onMain { observer.currentMediaItem?.mediaId == "test:two" } &&
                    scenario.readPlaybackState().currentItem?.mediaId == "test:two"
            }
            assertEquals(2, onMain { observer.mediaItemCount })
        }
    }

    private fun testMediaItems(): List<MediaItem> = listOf(
        mediaItem("test:one", "Connected Track One", firstTone),
        mediaItem("test:two", "Connected Track Two", secondTone),
    )

    private fun mediaItem(id: String, title: String, file: File): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(Uri.fromFile(file))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("999 Vault Test").build())
        .build()

    private fun createSilentWave(name: String): File {
        val file = File(context.cacheDir, name)
        val sampleRate = 8_000
        val sampleCount = sampleRate * 3
        val payloadBytes = sampleCount * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + payloadBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(payloadBytes)
        }
        FileOutputStream(file).use { output ->
            output.write(header.array())
            output.write(ByteArray(payloadBytes))
        }
        return file
    }

    private fun await(description: String, timeoutMs: Long = 10_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        assertTrue(description, predicate())
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val outcome = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { outcome.set(runCatching(block)) }
        return outcome.get().getOrThrow()
    }
}

private fun ActivityScenario<PlaybackProbeActivity>.readPlaybackState() = AtomicReference<com.vault999.android.playback.PlaybackUiState>()
    .also { result -> onActivity { result.set(it.playbackController.state.value) } }
    .get()

private data class PlayerSnapshot(
    val items: List<MediaItem>,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
    val playWhenReady: Boolean,
    val volume: Float,
)

private fun Player.snapshot(): PlayerSnapshot = PlayerSnapshot(
    items = List(mediaItemCount) { getMediaItemAt(it) },
    currentIndex = currentMediaItemIndex,
    positionMs = currentPosition.coerceAtLeast(0),
    shuffle = shuffleModeEnabled,
    repeatMode = repeatMode,
    playWhenReady = playWhenReady,
    volume = volume,
)
