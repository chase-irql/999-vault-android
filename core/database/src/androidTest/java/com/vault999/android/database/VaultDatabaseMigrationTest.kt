package com.vault999.android.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VaultDatabase::class.java,
    )

    @Test
    fun migratesVersionOneThroughCurrentWithoutLosingLocalListeningHistory() {
        helper.createDatabase(NAME, 1).apply {
            execSQL(
                "INSERT INTO listening_events (id, songId, playedAtEpochMs, listenedSeconds, durationSeconds, source, acknowledged) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("00000000-0000-4000-8000-000000000321", 9L, 1_000L, 30L, 120L, "catalog", 0),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(NAME, 4, true, *VaultDatabase.ALL_MIGRATIONS)
        database.query("SELECT accountId, acknowledged FROM listening_events").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(true, cursor.isNull(0))
            assertEquals(0, cursor.getInt(1))
        }
        database.close()
    }

    private companion object { const val NAME = "vault-migration-test" }
}
