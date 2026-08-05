package com.vault999.android

import com.vault999.android.model.CanonicalSong
import com.vault999.android.model.Era
import com.vault999.android.model.SongCategory

internal fun fixtureSongs(): List<CanonicalSong> = listOf(
    CanonicalSong(1, 1, "Archive Signal", listOf("Signal"), "Compilation/Fixture/Archive Signal.mp3", "Juice WRLD", 188, SongCategory.UNRELEASED, Era(1, "999 Era"), null, listOf("Fixture producer"), "https://juicewrldapi.com/juicewrld/files/download/?path=Compilation%2FFixture%2FArchive%20Signal.mp3"),
    CanonicalSong(2, 2, "Midnight Session", emptyList(), "Compilation/Fixture/Midnight Session.mp3", "Juice WRLD", 214, SongCategory.SESSION, Era(2, "Sessions"), null, emptyList(), "https://juicewrldapi.com/juicewrld/files/download/?path=Compilation%2FFixture%2FMidnight%20Session.mp3"),
    CanonicalSong(3, 3, "Unsurfaced Note", emptyList(), null, "Juice WRLD", null, SongCategory.UNSURFACED, null, null, emptyList(), null),
)
