package com.vault999.android

import com.vault999.android.model.CanonicalSong
import com.vault999.android.model.CloudLike
import com.vault999.android.model.CloudSyncState
import com.vault999.android.model.SongCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CloudSongResolverTest {
    @Test
    fun `resolves current canonical IDs and legacy public numbers`() {
        val song = song(id = 94_165, publicNumber = 1_785)
        val index = cloudSongIndex(listOf(song))

        assertEquals(song, index[94_165])
        assertEquals(song, index[1_785])
    }

    @Test
    fun `legacy public-number like matches the canonical song`() {
        val song = song(id = 94_165, publicNumber = 1_785)
        val likes = listOf(CloudLike(1_785, true, CloudSyncState.SYNCED))

        assertNotNull(likes.likeFor(song))
    }

    private fun song(id: Long, publicNumber: Long) = CanonicalSong(
        id = id,
        publicNumber = publicNumber,
        title = "Song",
        archivePath = "Compilation/Song.mp3",
        artist = "Juice WRLD",
        durationSeconds = 180,
        category = SongCategory.UNRELEASED,
        era = null,
        artworkUrl = null,
        streamUrl = "https://juicewrldapi.com/song.mp3",
    )
}
