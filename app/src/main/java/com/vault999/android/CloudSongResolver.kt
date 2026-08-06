package com.vault999.android

import com.vault999.android.model.CanonicalSong
import com.vault999.android.model.CloudLike

/** Resolves both current canonical IDs and legacy public catalogue numbers. */
internal fun cloudSongIndex(catalog: List<CanonicalSong>): Map<Long, CanonicalSong> = buildMap(catalog.size * 2) {
    catalog.forEach { song -> put(song.id, song) }
    catalog.forEach { song -> putIfAbsent(song.publicNumber, song) }
}

internal fun List<CloudLike>.likeFor(song: CanonicalSong): CloudLike? =
    firstOrNull { like -> like.liked && (like.songId == song.id || like.songId == song.publicNumber) }
