package com.vault999.android.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "songs", indices = [Index("publicNumber", unique = true), Index("archivePath", unique = true)])
data class SongEntity(
    @PrimaryKey val id: Long,
    val publicNumber: Long,
    val title: String,
    val aliasesJson: String,
    val archivePath: String?,
    val artist: String,
    val durationSeconds: Long?,
    val category: String,
    val eraId: Long?,
    val eraName: String?,
    val artworkUrl: String?,
    val producersJson: String,
    val streamUrl: String?,
    val fetchedAtEpochMs: Long,
)

@Entity(tableName = "archive_entries", indices = [Index("parentPath"), Index("kind"), Index("name")])
data class ArchiveEntryEntity(
    @PrimaryKey val path: String,
    val parentPath: String,
    val name: String,
    val kind: String,
    val sizeBytes: Long?,
    val modifiedAtEpochMs: Long?,
    val canonicalSongId: Long?,
    val fetchedAtEpochMs: Long,
)

@Entity(tableName = "radio_cache")
data class RadioCacheEntity(
    @PrimaryKey val key: Int = 1,
    val station: String,
    val state: String,
    val live: Boolean,
    val listenerCount: Long,
    val nowTitle: String?,
    val nowArtist: String?,
    val nowAlbum: String?,
    val elapsedMs: Long?,
    val durationMs: Long?,
    val queueJson: String,
    val streamUrl: String,
    val fetchedAtEpochMs: Long,
)

@Entity(tableName = "queue_items", indices = [Index("position", unique = true)])
data class QueueItemEntity(
    @PrimaryKey val mediaId: String,
    val position: Int,
    val title: String,
    val artist: String,
    val uri: String,
    val artworkUri: String?,
    val durationMs: Long?,
    val canonicalSongId: Long?,
    val local: Boolean,
    val available: Boolean,
)

@Entity(tableName = "playback_session")
data class PlaybackSessionEntity(
    @PrimaryKey val key: Int = 1,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: String,
    val playbackMode: String,
    val historyJson: String,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "downloads", indices = [Index("stage"), Index("updatedAtEpochMs")])
data class DownloadEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val stage: String,
    val displayName: String,
    val destinationType: String,
    val destinationIdentity: String,
    val sourceJson: String,
    val bytesCompleted: Long,
    val bytesTotal: Long?,
    val validator: String?,
    val checkpointJson: String?,
    val errorCode: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "local_favorites")
data class LocalFavoriteEntity(
    @PrimaryKey val identity: String,
    val canonicalSongId: Long?,
    val archivePath: String?,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val ownership: String,
    val revision: String?,
    val accountId: String?,
    val migrationKey: String?,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "playlist_items", primaryKeys = ["playlistId", "itemIdentity"], indices = [Index("playlistId", "position", unique = true)])
data class PlaylistItemEntity(
    val playlistId: String,
    val itemIdentity: String,
    val position: Int,
    val canonicalSongId: Long?,
    val archivePath: String?,
)

@Entity(tableName = "listening_events", indices = [Index("acknowledged"), Index("playedAtEpochMs")])
data class ListeningEventEntity(
    @PrimaryKey val id: String,
    val songId: Long,
    val playedAtEpochMs: Long,
    val listenedSeconds: Long,
    val durationSeconds: Long,
    val source: String,
    val acknowledged: Boolean,
)

@Entity(tableName = "pending_mutations", indices = [Index("playlistId", "createdAtEpochMs"), Index("nextAttemptAtEpochMs")])
data class PendingMutationEntity(
    @PrimaryKey val idempotencyKey: String,
    val accountId: String,
    val playlistId: String?,
    val kind: String,
    val payloadJson: String,
    val attempt: Int,
    val nextAttemptAtEpochMs: Long,
    val createdAtEpochMs: Long,
)

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY publicNumber LIMIT :limit OFFSET :offset")
    fun observePage(limit: Int, offset: Int): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' ORDER BY publicNumber LIMIT :limit")
    fun observeSearch(query: String, limit: Int): Flow<List<SongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(songs: List<SongEntity>)

    @Query("DELETE FROM songs") suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(songs: List<SongEntity>) {
        deleteAll()
        upsertAll(songs)
    }
}

@Dao
interface ArchiveDao {
    @Query("SELECT * FROM archive_entries WHERE parentPath = :parent ORDER BY CASE kind WHEN 'DIRECTORY' THEN 0 ELSE 1 END, name COLLATE NOCASE")
    fun observeFolder(parent: String): Flow<List<ArchiveEntryEntity>>

    @Query("SELECT * FROM archive_entries WHERE name LIKE '%' || :query || '%' OR path LIKE '%' || :query || '%' ORDER BY name COLLATE NOCASE LIMIT :limit")
    fun observeSearch(query: String, limit: Int = 200): Flow<List<ArchiveEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<ArchiveEntryEntity>)
    @Query("DELETE FROM archive_entries") suspend fun clear()

    @Transaction
    suspend fun replaceAll(items: List<ArchiveEntryEntity>) {
        clear()
        upsertAll(items)
    }

    @Query("SELECT * FROM radio_cache WHERE `key` = 1") fun observeRadio(): Flow<RadioCacheEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertRadio(status: RadioCacheEntity)
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_items ORDER BY position") suspend fun items(): List<QueueItemEntity>
    @Query("SELECT * FROM playback_session WHERE `key` = 1") suspend fun session(): PlaybackSessionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertItems(items: List<QueueItemEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSession(session: PlaybackSessionEntity)
    @Query("DELETE FROM queue_items") suspend fun clearItems()

    @Transaction
    suspend fun replace(items: List<QueueItemEntity>, session: PlaybackSessionEntity) {
        clearItems()
        insertItems(items)
        insertSession(session)
    }
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY updatedAtEpochMs DESC") fun observeAll(): Flow<List<DownloadEntity>>
    @Query("SELECT * FROM downloads WHERE id = :id") suspend fun get(id: String): DownloadEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(job: DownloadEntity)
    @Query("UPDATE downloads SET stage = :stage, updatedAtEpochMs = :now WHERE id = :id") suspend fun updateStage(id: String, stage: String, now: Long)
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM local_favorites ORDER BY createdAtEpochMs DESC") fun observeFavorites(): Flow<List<LocalFavoriteEntity>>
    @Query("SELECT * FROM playlists WHERE accountId IS NULL OR accountId = :accountId ORDER BY updatedAtEpochMs DESC") fun observePlaylists(accountId: String?): Flow<List<PlaylistEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFavorite(favorite: LocalFavoriteEntity)
    @Query("DELETE FROM local_favorites WHERE identity = :identity") suspend fun deleteFavorite(identity: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPlaylist(playlist: PlaylistEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertItems(items: List<PlaylistItemEntity>)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: String)
    @Query("DELETE FROM playlist_items WHERE playlistId = :id") suspend fun deletePlaylistItems(id: String)
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM pending_mutations WHERE nextAttemptAtEpochMs <= :now ORDER BY createdAtEpochMs LIMIT :limit") suspend fun readyMutations(now: Long, limit: Int = 50): List<PendingMutationEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMutation(mutation: PendingMutationEntity)
    @Query("DELETE FROM pending_mutations WHERE idempotencyKey = :key") suspend fun acknowledgeMutation(key: String)
    @Query("SELECT * FROM listening_events WHERE acknowledged = 0 ORDER BY playedAtEpochMs LIMIT :limit") suspend fun pendingEvents(limit: Int = 500): List<ListeningEventEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEvents(events: List<ListeningEventEntity>)
    @Query("UPDATE listening_events SET acknowledged = 1 WHERE id IN (:ids)") suspend fun acknowledgeEvents(ids: List<String>)
}

@Database(
    entities = [
        SongEntity::class,
        ArchiveEntryEntity::class,
        RadioCacheEntity::class,
        QueueItemEntity::class,
        PlaybackSessionEntity::class,
        DownloadEntity::class,
        LocalFavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        ListeningEventEntity::class,
        PendingMutationEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun songs(): SongDao
    abstract fun archive(): ArchiveDao
    abstract fun queue(): QueueDao
    abstract fun downloads(): DownloadDao
    abstract fun library(): LibraryDao
    abstract fun sync(): SyncDao

    companion object {
        fun create(context: Context): VaultDatabase = Room.databaseBuilder(context, VaultDatabase::class.java, "vault.db").build()
    }
}
