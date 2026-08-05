package com.vault999.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListeningTest {
    @Test fun `credits continuous forward listening but ignores seeks and pauses`() {
        val tracker = ListeningCreditTracker(9, 120, "catalog") { "event-1" }
        assertNull(tracker.observe(PlaybackObservation(0, 0, true), 1000))
        assertNull(tracker.observe(PlaybackObservation(5_000, 5_000, true), 2000))
        assertNull(tracker.observe(PlaybackObservation(6_000, 90_000, true, explicitSeek = true), 3000))
        assertNull(tracker.observe(PlaybackObservation(11_000, 95_000, true), 4000))
        assertNull(tracker.observe(PlaybackObservation(16_000, 100_000, true), 5000))
        assertNull(tracker.observe(PlaybackObservation(21_000, 105_000, true), 6000))
        assertNull(tracker.observe(PlaybackObservation(26_000, 110_000, true), 7000))
        val event = tracker.observe(PlaybackObservation(31_000, 115_000, true), 8000)
        assertEquals("event-1", event?.id)
    }

    @Test fun `short songs credit at half duration`() {
        val tracker = ListeningCreditTracker(2, 20, "queue") { "short" }
        tracker.observe(PlaybackObservation(0, 0, true), 1)
        val event = tracker.observe(PlaybackObservation(10_000, 10_000, true), 2)
        assertEquals("short", event?.id)
    }

    @Test fun `wrapped deduplicates event IDs and keeps honest coverage`() {
        val events = listOf(
            ListeningEvent("a", 1, 100, 30, 100, "queue"),
            ListeningEvent("a", 1, 100, 30, 100, "queue"),
            ListeningEvent("b", 2, 200, 20, 40, "listen"),
        )
        val all = WrappedAggregator.aggregate(events, 300, null)
        assertEquals(2, all.totalPlays)
        assertEquals(100, all.coverageStartEpochMs)
        assertEquals(listOf(1L, 2L), all.topSongs.map { it.songId })
    }
}
