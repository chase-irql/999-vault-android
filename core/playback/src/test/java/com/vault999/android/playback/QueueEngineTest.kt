package com.vault999.android.playback

import com.vault999.android.model.QueueItem
import com.vault999.android.model.QueueSnapshot
import com.vault999.android.model.RepeatMode
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}

