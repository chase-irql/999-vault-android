package com.vault999.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.vault999.android.model.PlaybackMode
import com.vault999.android.model.QueueItem
import com.vault999.android.model.QueueSnapshot
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Profileable-build-only deterministic state seed; this component is absent from release. */
class BenchmarkSeedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runBlocking(Dispatchers.IO) {
            val application = application as VaultApplication
            val preferences = getSharedPreferences("benchmark-seed", MODE_PRIVATE)
            if (!preferences.getBoolean("library", false)) {
                repeat(40) { index ->
                    application.graph.libraryRepository.createPlaylist("Benchmark playlist ${index + 1}")
                }
                preferences.edit().putBoolean("library", true).apply()
            }
            val tone = createSilentWave()
            application.playbackSessionStore.persist(
                QueueSnapshot(
                    items = listOf(
                        QueueItem("benchmark:one", "Benchmark Track One", "999 Vault", tone.toURI().toString(), local = true),
                        QueueItem("benchmark:two", "Benchmark Track Two", "999 Vault", tone.toURI().toString(), local = true),
                    ),
                    currentIndex = 0,
                    playbackMode = PlaybackMode.EXPLICIT_QUEUE,
                ),
            )
            finish()
        }
    }

    private fun createSilentWave(): File {
        val file = File(cacheDir, "benchmark-silence.wav")
        if (file.isFile) return file
        val sampleRate = 8_000
        val payloadBytes = sampleRate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + payloadBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(payloadBytes)
        }
        FileOutputStream(file).use { output ->
            output.write(header.array())
            output.write(ByteArray(payloadBytes))
        }
        return file
    }
}
