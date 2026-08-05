package com.vault999.android.model

import org.junit.Assert.assertFalse
import org.junit.Test

class ModelsTest {
    @Test fun `path-only canonical record without stream is not playable`() {
        val song = CanonicalSong(1, 1, "Known", emptyList(), "Compilation/Known.mp3", "Artist", null, SongCategory.UNKNOWN, null, null, emptyList(), null)
        assertFalse(song.isPlayable)
    }

    @Test(expected = IllegalArgumentException::class) fun `canonical IDs must be positive`() {
        CanonicalSong(0, 1, "Bad", emptyList(), null, "Artist", null, SongCategory.UNKNOWN, null, null, emptyList(), null)
    }
}

