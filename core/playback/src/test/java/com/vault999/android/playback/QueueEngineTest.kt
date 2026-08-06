package com.vault999.android.playback

import com.vault999.android.model.QueueItem
import com.vault999.android.model.QueueSnapshot
import com.vault999.android.model.RepeatMode
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueEngineTest {
    private val items = (1..4).map { QueueItem("m$it", "Track $it", "Artist", "https://juicewrldapi.com/$it") }

    @Test fun `repeat one applies only to natural completion`() {
        val state = QueueSnapshot(items, 1, repeatMode = RepeatMode.ONE)
        assertEquals(1, QueueEngine().advance(state, AdvanceCause.NATURAL_COMPLETION).snapshot.currentIndex)
        assertEquals(2, QueueEngine().advance(state, AdvanceCause.EXPLICIT_NEXT).snapshot.currentIndex)
    }

    @Test fun `shuffle avoids immediate repeat and records history`() {
        val result = QueueEngine(Random(9)).advance(QueueSnapshot(items, 2, shuffle = true), AdvanceCause.EXPLICIT_NEXT)
        assertNotEquals(2, result.snapshot.currentIndex)
        assertEquals(listOf("m3"), result.snapshot.historyMediaIds)
    }

    @Test fun `catalog next uses full canonical ordering`() {
        assertEquals(0, catalogNextIndex(3, 4, false))
        assertNotEquals(2, catalogNextIndex(2, 4, true, Random(2)))
    }

    @Test fun `sequential navigation skips unavailable media in both directions`() {
        val unavailable = items[1].copy(available = false)
        val state = QueueSnapshot(listOf(items[0], unavailable, items[2]), 0)
        assertEquals(2, QueueEngine().advance(state, AdvanceCause.EXPLICIT_NEXT).snapshot.currentIndex)

        val backwards = QueueEngine().advance(state.copy(currentIndex = 2), AdvanceCause.EXPLICIT_PREVIOUS)
        assertEquals(0, backwards.snapshot.currentIndex)
    }

    @Test fun `shuffle with no available alternative terminates safely`() {
        val state = QueueSnapshot(
            listOf(items[0], items[1].copy(available = false)),
            currentIndex = 0,
            shuffle = true,
        )
        val transition = QueueEngine(Random(4)).advance(state, AdvanceCause.EXPLICIT_NEXT)
        assertNull(transition.selected)
        assertEquals(0, transition.snapshot.currentIndex)
    }

    @Test fun `repeat all permits the sole playable shuffled item`() {
        val state = QueueSnapshot(
            listOf(items[0], items[1].copy(available = false)),
            currentIndex = 0,
            shuffle = true,
            repeatMode = RepeatMode.ALL,
        )
        assertEquals(items[0], QueueEngine(Random(4)).advance(state, AdvanceCause.EXPLICIT_NEXT).selected)
    }

    @Test fun `playable queue maps requested index after filtering`() {
        val prepared = preparePlayableQueue(
            listOf(items[0].copy(available = false), items[1], items[2]),
            requestedStartIndex = 2,
        )
        assertEquals(listOf(items[1], items[2]), prepared.items)
        assertEquals(1, prepared.startIndex)
        assertEquals(-1, preparePlayableQueue(listOf(items[0].copy(available = false)), 0).startIndex)

        val unavailableSelection = preparePlayableQueue(
            listOf(items[0], items[1].copy(available = false), items[2]),
            requestedStartIndex = 1,
        )
        assertEquals(1, unavailableSelection.startIndex)
    }

    @Test fun `toggle pauses intent while buffering or play when ready`() {
        assertTrue(shouldPauseOnToggle(playWhenReady = true, buffering = false))
        assertTrue(shouldPauseOnToggle(playWhenReady = false, buffering = true))
        assertEquals(false, shouldPauseOnToggle(playWhenReady = false, buffering = false))
    }
}
