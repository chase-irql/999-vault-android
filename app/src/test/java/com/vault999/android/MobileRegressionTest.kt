package com.vault999.android

import com.vault999.android.downloads.HttpTransferException
import com.vault999.android.downloads.retryableTransferFailure
import com.vault999.android.downloads.zipJobNeedsPolling
import com.vault999.android.model.VaultError
import com.vault999.android.model.QueueItem
import com.vault999.android.network.NetworkException
import com.vault999.android.network.ZipJobState
import com.vault999.android.playback.PlaybackUiState
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileRegressionTest {
    @Test fun `streaming client has no whole-call deadline but retains bounded idle timeout`() {
        val base = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

        val streaming = base.forStreamingTransfers()

        assertEquals(0, streaming.callTimeoutMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(2).toInt(), streaming.readTimeoutMillis)
        assertEquals(base.connectTimeoutMillis, streaming.connectTimeoutMillis)
        assertSame(base.dispatcher, streaming.dispatcher)
        assertSame(base.connectionPool, streaming.connectionPool)
    }

    @Test fun `timeline retains metadata duration while player changes songs`() {
        val item = QueueItem(
            mediaId = "next",
            title = "Next song",
            artist = "Juice WRLD",
            uri = "https://juicewrldapi.com/next.mp3",
            durationMs = 184_000,
        )

        assertEquals(184_000L, playbackTimelineDuration(PlaybackUiState(currentItem = item)))
        assertEquals(185_000L, playbackTimelineDuration(PlaybackUiState(currentItem = item, durationMs = 185_000)))
        assertNull(playbackTimelineDuration(PlaybackUiState(currentItem = item.copy(durationMs = null))))
    }

    @Test fun `download failures use actionable consumer language`() {
        assertEquals("Connection interrupted. Retrying automatically.", downloadFailureMessage("InterruptedIOException"))
        assertEquals("Download stopped. Resume to try again.", downloadFailureMessage("UnknownFailure"))
    }

    @Test fun `new and unknown zip preparation states keep polling`() {
        assertTrue(zipJobNeedsPolling(ZipJobState.QUEUED))
        assertTrue(zipJobNeedsPolling(ZipJobState.PREPARING))
        assertTrue(zipJobNeedsPolling(ZipJobState.UNKNOWN))
        assertFalse(zipJobNeedsPolling(ZipJobState.READY))
        assertFalse(zipJobNeedsPolling(ZipJobState.FAILED))
    }

    @Test fun `only transient transfer failures are automatically retried`() {
        assertTrue(retryableTransferFailure(NetworkException(VaultError.Offline("zip"), "zip_status")))
        assertTrue(retryableTransferFailure(NetworkException(VaultError.Server("zip", 503), "zip_status", 503)))
        assertTrue(retryableTransferFailure(HttpTransferException(503, "temporary")))
        assertFalse(retryableTransferFailure(NetworkException(VaultError.Validation("zip"), "zip_status", 400)))
        assertFalse(retryableTransferFailure(HttpTransferException(404, "missing")))
    }
}
