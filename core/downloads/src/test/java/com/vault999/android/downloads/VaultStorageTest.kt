package com.vault999.android.downloads

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `app storage truncates to checkpoint and atomically promotes partial file`() {
        val storage = AppSpecificVaultStorage(temporary.newFolder("vault"))
        val partial = VaultPath.of("Compilation/song.part")
        storage.openSink(partial).use { it.write(byteArrayOf(1, 2, 3, 4, 5)) }
        storage.openSink(partial, 3).use { it.write(byteArrayOf(9, 8)) }
        val final = VaultPath.of("Compilation/song.mp3")
        storage.move(partial, final)

        assertFalse(storage.inspect(partial).exists)
        assertEquals(5L, storage.inspect(final).size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 9, 8), storage.openSource(final).use { it.readBytes() })
    }

    @Test fun `vault paths reject absolute and traversal forms`() {
        listOf("../escape", "/absolute", "C:\\absolute", "folder\\..\\escape").forEach { value ->
            val rejected = runCatching { VaultPath.of(value) }.isFailure
            assertTrue("Expected rejection for $value", rejected)
        }
    }
}
