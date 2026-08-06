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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    val bytesPerSecond: Long?,
    val etaSeconds: Long?,
    val currentItem: String?,
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

@Entity(tableName = "listening_events", indices = [Index("acknowledged"), Index("playedAtEpochMs"), Index("accountId")])
data class ListeningEventEntity(
    @PrimaryKey val id: String,
    val songId: Long,
    val playedAtEpochMs: Long,
    val listenedSeconds: Long,
    val durationSeconds: Long,
    val source: String,
    val acknowledged: Boolean,
    val accountId: String? = null,
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

@Entity(tableName = "cloud_library_state")
data class CloudLibraryStateEntity(
    @PrimaryKey val accountId: String,
    val likesRevision: String,
    val fetchedAtEpochMs: Long,
)

@Entity(tableName = "cloud_likes", primaryKeys = ["accountId", "songId"], indices = [Index("accountId", "syncState")])
data class CloudLikeEntity(
    val accountId: String,
    val songId: Long,
    val liked: Boolean,
    val syncState: String,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "cloud_playlists",
    indices = [Index(value = ["accountId", "cloudId"], unique = true), Index("accountId", "syncState")],
)
data class CloudPlaylistEntity(
    @PrimaryKey val localId: String,
    val accountId: String,
    val cloudId: String?,
    val clientMigrationId: String?,
    val name: String,
    val description: String,
    val coverUrl: String?,
    val revision: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val syncState: String,
    val deleted: Boolean,
)

@Entity(tableName = "cloud_playlist_songs", primaryKeys = ["playlistLocalId", "songId"], indices = [Index("playlistLocalId", "position", unique = true)])
data class CloudPlaylistSongEntity(
    val playlistLocalId: String,
    val songId: Long,
    val position: Int,
)

@Entity(
    tableName = "cloud_mutations",
    indices = [Index("accountId", "nextAttemptAtEpochMs"), Index("subjectKey", "createdAtEpochMs")],
)
data class CloudMutationEntity(
    @PrimaryKey val idempotencyKey: String,
    val accountId: String,
    val playlistLocalId: String?,
    val subjectKey: String,
    val operation: String,
    val payloadJson: String,
    val attempt: Int,
    val nextAttemptAtEpochMs: Long,
    val createdAtEpochMs: Long,
    val errorCode: String?,
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
    @Query("SELECT * FROM local_favorites ORDER BY createdAtEpochMs DESC") suspend fun favorites(): List<LocalFavoriteEntity>
    @Query("SELECT * FROM playlists WHERE accountId IS NULL OR accountId = :accountId ORDER BY updatedAtEpochMs DESC") fun observePlaylists(accountId: String?): Flow<List<PlaylistEntity>>
    @Query("SELECT * FROM playlists WHERE accountId IS NULL ORDER BY updatedAtEpochMs DESC") suspend fun devicePlaylists(): List<PlaylistEntity>
    @Query("SELECT * FROM playlists WHERE id = :id") fun observePlaylist(id: String): Flow<PlaylistEntity?>
    @Query("SELECT * FROM playlists WHERE id = :id") suspend fun playlist(id: String): PlaylistEntity?
    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position") fun observePlaylistItems(playlistId: String): Flow<List<PlaylistItemEntity>>
    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position") suspend fun playlistItems(playlistId: String): List<PlaylistItemEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM local_favorites WHERE identity = :identity)") suspend fun isFavorite(identity: String): Boolean
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFavorite(favorite: LocalFavoriteEntity)
    @Query("DELETE FROM local_favorites WHERE identity = :identity") suspend fun deleteFavorite(identity: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPlaylist(playlist: PlaylistEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertItems(items: List<PlaylistItemEntity>)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: String)
    @Query("DELETE FROM playlist_items WHERE playlistId = :id") suspend fun deletePlaylistItems(id: String)

    @Transaction
    suspend fun replacePlaylistItems(id: String, items: List<PlaylistItemEntity>) {
        deletePlaylistItems(id)
        if (items.isNotEmpty()) upsertItems(items)
    }
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM pending_mutations WHERE nextAttemptAtEpochMs <= :now ORDER BY createdAtEpochMs LIMIT :limit") suspend fun readyMutations(now: Long, limit: Int = 50): List<PendingMutationEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMutation(mutation: PendingMutationEntity)
    @Query("DELETE FROM pending_mutations WHERE idempotencyKey = :key") suspend fun acknowledgeMutation(key: String)
    @Query("SELECT * FROM listening_events WHERE accountId IS NULL AND acknowledged = 0 ORDER BY playedAtEpochMs LIMIT :limit") suspend fun pendingEvents(limit: Int = 500): List<ListeningEventEntity>
    @Query("SELECT * FROM listening_events WHERE accountId IS NULL OR accountId = :accountId ORDER BY playedAtEpochMs DESC") fun observeEvents(accountId: String?): Flow<List<ListeningEventEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEvents(events: List<ListeningEventEntity>)
    @Query("UPDATE listening_events SET acknowledged = 1 WHERE id IN (:ids)") suspend fun acknowledgeEvents(ids: List<String>)
}

@Dao
interface CloudLibraryDao {
    @Query("SELECT * FROM cloud_library_state WHERE accountId = :accountId") suspend fun state(accountId: String): CloudLibraryStateEntity?
    @Query("SELECT * FROM cloud_likes WHERE accountId = :accountId AND liked = 1 ORDER BY songId") suspend fun likes(accountId: String): List<CloudLikeEntity>
    @Query("SELECT * FROM cloud_playlists WHERE accountId = :accountId AND deleted = 0 ORDER BY updatedAtEpochMs DESC") suspend fun playlists(accountId: String): List<CloudPlaylistEntity>
    @Query("SELECT * FROM cloud_playlists WHERE localId = :localId") suspend fun playlist(localId: String): CloudPlaylistEntity?
    @Query("SELECT * FROM cloud_playlists WHERE accountId = :accountId AND cloudId = :cloudId") suspend fun playlistByCloudId(accountId: String, cloudId: String): CloudPlaylistEntity?
    @Query("SELECT * FROM cloud_playlist_songs WHERE playlistLocalId = :localId ORDER BY position") suspend fun playlistSongs(localId: String): List<CloudPlaylistSongEntity>
    @Query("SELECT * FROM cloud_mutations WHERE idempotencyKey = :key") suspend fun mutation(key: String): CloudMutationEntity?
    @Query("SELECT * FROM cloud_mutations WHERE accountId = :accountId AND subjectKey = :subjectKey ORDER BY createdAtEpochMs, idempotencyKey")
    suspend fun subjectMutations(accountId: String, subjectKey: String): List<CloudMutationEntity>
    @Query("SELECT COUNT(*) FROM cloud_mutations WHERE accountId = :accountId") suspend fun mutationCount(accountId: String): Int
    @Query("SELECT * FROM cloud_mutations WHERE accountId = :accountId AND nextAttemptAtEpochMs <= :now ORDER BY createdAtEpochMs, idempotencyKey LIMIT :limit")
    suspend fun readyMutations(accountId: String, now: Long, limit: Int = 50): List<CloudMutationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertState(state: CloudLibraryStateEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertLike(like: CloudLikeEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRemoteLikes(likes: List<CloudLikeEntity>)
    @Query("DELETE FROM cloud_likes WHERE accountId = :accountId AND syncState = 'SYNCED'") suspend fun deleteSyncedLikes(accountId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPlaylist(playlist: CloudPlaylistEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRemotePlaylists(playlists: List<CloudPlaylistEntity>)
    @Query("DELETE FROM cloud_playlists WHERE accountId = :accountId AND syncState = 'SYNCED'") suspend fun deleteSyncedPlaylists(accountId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPlaylistSongs(songs: List<CloudPlaylistSongEntity>)
    @Query("DELETE FROM cloud_playlist_songs WHERE playlistLocalId = :localId") suspend fun deletePlaylistSongs(localId: String)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMutation(mutation: CloudMutationEntity)
    @Query("DELETE FROM cloud_mutations WHERE idempotencyKey = :key") suspend fun acknowledgeMutation(key: String)
    @Query("SELECT COUNT(*) FROM cloud_mutations WHERE accountId = :accountId AND subjectKey = :subjectKey") suspend fun pendingSubjectCount(accountId: String, subjectKey: String): Int
    @Query("UPDATE cloud_mutations SET attempt = :attempt, nextAttemptAtEpochMs = :nextAttempt, errorCode = :errorCode WHERE idempotencyKey = :key")
    suspend fun rescheduleMutation(key: String, attempt: Int, nextAttempt: Long, errorCode: String)
    @Query("UPDATE cloud_mutations SET payloadJson = :payloadJson WHERE idempotencyKey = :key")
    suspend fun updateMutationPayload(key: String, payloadJson: String)
    @Query("UPDATE cloud_likes SET syncState = :state, updatedAtEpochMs = :now WHERE accountId = :accountId AND songId = :songId")
    suspend fun updateLikeState(accountId: String, songId: Long, state: String, now: Long)
    @Query("UPDATE cloud_playlists SET syncState = :state, revision = :revision, updatedAtEpochMs = :now WHERE localId = :localId")
    suspend fun updatePlaylistState(localId: String, state: String, revision: String?, now: Long)

    @Transaction
    suspend fun enqueueLike(mutation: CloudMutationEntity, like: CloudLikeEntity) {
        insertOrValidateMutation(mutation)
        upsertLike(like)
    }

    @Transaction
    suspend fun enqueuePlaylist(
        mutation: CloudMutationEntity,
        playlist: CloudPlaylistEntity,
        songs: List<CloudPlaylistSongEntity>,
    ) {
        insertOrValidateMutation(mutation)
        upsertPlaylist(playlist)
        replacePlaylistSongs(playlist.localId, songs)
    }

    @Transaction
    suspend fun replaceMutation(previousKey: String, replacement: CloudMutationEntity) {
        acknowledgeMutation(previousKey)
        insertOrValidateMutation(replacement)
    }

    @Transaction
    suspend fun insertOrValidateMutation(entity: CloudMutationEntity) {
        val existing = mutation(entity.idempotencyKey)
        if (existing != null) {
            require(existing == entity) { "Idempotency key reused for a different cloud operation" }
            return
        }
        require(mutationCount(entity.accountId) < 2_000) { "Cloud mutation queue is full" }
        insertMutation(entity)
    }

    @Transaction
    suspend fun replaceLikesSnapshot(state: CloudLibraryStateEntity, likes: List<CloudLikeEntity>) {
        deleteSyncedLikes(state.accountId)
        insertRemoteLikes(likes)
        upsertState(state)
    }

    @Transaction
    suspend fun replacePlaylistsSnapshot(accountId: String, playlists: List<CloudPlaylistEntity>) {
        deleteSyncedPlaylists(accountId)
        insertRemotePlaylists(playlists)
    }

    @Transaction
    suspend fun replacePlaylistSongs(localId: String, songs: List<CloudPlaylistSongEntity>) {
        deletePlaylistSongs(localId)
        upsertPlaylistSongs(songs)
    }
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
        CloudLibraryStateEntity::class,
        CloudLikeEntity::class,
        CloudPlaylistEntity::class,
        CloudPlaylistSongEntity::class,
        CloudMutationEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun songs(): SongDao
    abstract fun archive(): ArchiveDao
    abstract fun queue(): QueueDao
    abstract fun downloads(): DownloadDao
    abstract fun library(): LibraryDao
    abstract fun sync(): SyncDao
    abstract fun cloudLibrary(): CloudLibraryDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE downloads ADD COLUMN bytesPerSecond INTEGER")
                database.execSQL("ALTER TABLE downloads ADD COLUMN etaSeconds INTEGER")
                database.execSQL("ALTER TABLE downloads ADD COLUMN currentItem TEXT")
            }
        }


        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `cloud_library_state` (`accountId` TEXT NOT NULL, `likesRevision` TEXT NOT NULL, `fetchedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`accountId`))")
                database.execSQL("CREATE TABLE IF NOT EXISTS `cloud_likes` (`accountId` TEXT NOT NULL, `songId` INTEGER NOT NULL, `liked` INTEGER NOT NULL, `syncState` TEXT NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `songId`))")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_likes_accountId_syncState` ON `cloud_likes` (`accountId`, `syncState`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `cloud_playlists` (`localId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `cloudId` TEXT, `clientMigrationId` TEXT, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `coverUrl` TEXT, `revision` TEXT, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `syncState` TEXT NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`localId`))")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cloud_playlists_accountId_cloudId` ON `cloud_playlists` (`accountId`, `cloudId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_playlists_accountId_syncState` ON `cloud_playlists` (`accountId`, `syncState`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `cloud_playlist_songs` (`playlistLocalId` TEXT NOT NULL, `songId` INTEGER NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`playlistLocalId`, `songId`))")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cloud_playlist_songs_playlistLocalId_position` ON `cloud_playlist_songs` (`playlistLocalId`, `position`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `cloud_mutations` (`idempotencyKey` TEXT NOT NULL, `accountId` TEXT NOT NULL, `playlistLocalId` TEXT, `subjectKey` TEXT NOT NULL, `operation` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `attempt` INTEGER NOT NULL, `nextAttemptAtEpochMs` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `errorCode` TEXT, PRIMARY KEY(`idempotencyKey`))")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_mutations_accountId_nextAttemptAtEpochMs` ON `cloud_mutations` (`accountId`, `nextAttemptAtEpochMs`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_mutations_subjectKey_createdAtEpochMs` ON `cloud_mutations` (`subjectKey`, `createdAtEpochMs`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE listening_events ADD COLUMN accountId TEXT")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_events_accountId` ON `listening_events` (`accountId`)")
            }
        }
        internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun create(context: Context): VaultDatabase = Room.databaseBuilder(context, VaultDatabase::class.java, "vault.db")
            .addMigrations(*ALL_MIGRATIONS)
            .build()
    }
}
