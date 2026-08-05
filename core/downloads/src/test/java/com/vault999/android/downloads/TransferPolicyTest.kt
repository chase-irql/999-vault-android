package com.vault999.android.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferPolicyTest {
    @Test(expected = IllegalArgumentException::class) fun `rejects traversal`() { SafeArchivePath.segments("Compilation/../secret") }

    @Test fun `keeps hierarchy while sanitizing names`() {
        assertEquals(listOf("Compilation", "Folder", "song_.mp3"), SafeArchivePath.segments("Compilation/Folder/song?.mp3"))
    }

    @Test fun `resume requires range and stable validator`() {
        val saved = ResumeMetadata("etag-1", 1000, 400)
        assertTrue(canResume(saved, ResumeResponse(206, "etag-1", 400, 1000)))
        assertFalse(canResume(saved, ResumeResponse(206, "etag-2", 400, 1000)))
        assertFalse(canResume(saved, ResumeResponse(200, "etag-1", null, 1000)))
    }

    @Test fun `eta waits for samples and resets between stages`() {
        val eta = EtaEstimator(minimumSamples = 2)
        assertNull(eta.sample("download", 0, 1000, 1_000_000_000).etaSeconds)
        assertNull(eta.sample("download", 100, 1000, 2_000_000_000).etaSeconds)
        assertEquals(8L, eta.sample("download", 200, 1000, 3_000_000_000).etaSeconds)
        assertNull(eta.sample("extract", 0, 1000, 4_000_000_000).etaSeconds)
    }
}
