package com.vault999.android.listen

import com.vault999.android.model.CanonicalSong
import com.vault999.android.model.SongCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndlessListenEngineTest {
    private val songs = (1L..20L).map { id -> song(id, if (id % 2L == 0L) SongCategory.RELEASED else SongCategory.UNRELEASED) }

    @Test
    fun `start fills eight unique lookahead items`() {
        val result = EndlessListenEngine(seed = 1).start(EndlessListenState(), ListenMode.ALL, songs)

        assertNotNull(result.selected)
        assertEquals(8, result.state.active.lookAhead.size)
        assertEquals(8, result.state.active.lookAhead.distinctBy { it.mediaId }.size)
        assertFalse(result.state.active.lookAhead.any { it.mediaId == result.selected?.mediaId })
    }

    @Test
    fun `same seed and catalog produce the same session`() {
        val first = EndlessListenEngine(seed = 42).start(EndlessListenState(), ListenMode.ALL, songs)
        val second = EndlessListenEngine(seed = 42).start(EndlessListenState(), ListenMode.ALL, songs)

        assertEquals(first, second)
    }

    @Test
    fun `back and forward walk the same generated history`() {
        val engine = EndlessListenEngine(seed = 3)
        val started = engine.start(EndlessListenState(), ListenMode.ALL, songs)
        val second = engine.next(started.state, songs)
        val third = engine.next(second.state, songs)
        val back = engine.back(third.state)
        val backAgain = engine.back(back.state)
        val forward = engine.forward(backAgain.state)

        assertEquals(second.selected, back.selected)
        assertEquals(started.selected, backAgain.selected)
        assertEquals(second.selected, forward.selected)
        assertTrue(forward.state.active.canGoForward)
    }

    @Test
    fun `recents are bounded to eight newest previous tracks`() {
        val engine = EndlessListenEngine(seed = 2)
        var state = engine.start(EndlessListenState(), ListenMode.ALL, songs).state
        repeat(12) { state = engine.next(state, songs).state }

        assertEquals(8, state.active.recents.size)
        assertEquals(8, state.active.recents.distinctBy { it.mediaId }.size)
        assertFalse(state.active.recents.any { it.mediaId == state.active.current?.mediaId })
    }

    @Test
    fun `mode histories remain isolated and restore when switching`() {
        val engine = EndlessListenEngine(seed = 7)
        val all = engine.start(EndlessListenState(), ListenMode.ALL, songs)
        val allNext = engine.next(all.state, songs)
        val released = engine.start(allNext.state, ListenMode.RELEASED, songs)
        val releasedCurrent = released.selected
        val restoredAll = engine.switchMode(released.state, ListenMode.ALL)
        val restoredReleased = engine.switchMode(restoredAll.state, ListenMode.RELEASED)

        assertEquals(allNext.selected, restoredAll.selected)
        assertEquals(releasedCurrent, restoredReleased.selected)
        assertTrue(released.state.active.history.all { item -> item.canonicalSongId!! % 2L == 0L })
    }

    @Test
    fun `refill failure keeps buffered playback and later refill recovers`() {
        val engine = EndlessListenEngine(seed = 11)
        val started = engine.start(EndlessListenState(), ListenMode.ALL, songs)
        var state = engine.refill(started.state, emptyList())

        assertNotNull(state.active.current)
        assertNotNull(state.active.refillError)
        val advanced = engine.next(state, emptyList())
        assertNotNull(advanced.selected)
        state = engine.refill(advanced.state, songs)
        assertNull(state.active.refillError)
        assertEquals(8, state.active.lookAhead.size)
    }

    @Test
    fun `unplayable catalog fails without selecting`() {
        val engine = EndlessListenEngine()
        val result = engine.start(EndlessListenState(), ListenMode.ALL, listOf(song(1).copy(streamUrl = null)))

        assertNull(result.selected)
        assertNotNull(result.state.active.refillError)
    }
}

private fun song(id: Long, category: SongCategory = SongCategory.RELEASED): CanonicalSong = CanonicalSong(
    id = id,
    publicNumber = id,
    title = "Song $id",
    archivePath = "Songs/$id.mp3",
    artist = "Juice WRLD",
    durationSeconds = 180,
    category = category,
    era = null,
    artworkUrl = null,
    streamUrl = "https://juicewrldapi.com/files/Songs/$id.mp3",
)
