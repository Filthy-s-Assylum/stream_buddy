package com.streambuddy.app.data

import android.content.Context
import androidx.room.ColumnInfo
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
import androidx.room.TypeConverter
import androidx.room.Update
import com.streambuddy.app.core.security.AppCrypto
import com.streambuddy.app.stream.CacheState
import com.streambuddy.app.util.Format
import kotlinx.coroutines.flow.Flow

/** 
 * Room writes the value through [SecretConverters], which encrypts every
 * user-supplied string with AES-256-GCM before it reaches SQLite.
 *
 * The `streams` table itself stores plain [String] titles/URLs so that both the
 * pre-cache engine and the library layer read the same columns; the playback
 * session and library tables below keep the encrypted representation.
 */ 
data class EncryptedString(val value: String)

class SecretConverters {
    @TypeConverter
    fun encrypt(value: EncryptedString?): String? =
        value?.let { AppCrypto.encrypt(it.value) }

    @TypeConverter
    fun decrypt(value: String?): EncryptedString? =
        value?.let { EncryptedString(AppCrypto.decryptOrNull(it) ?: "") }
}

/** 
 * One entry of the pre-play queue.
 *
 * The schema is the union of both histories: the pre-cache engine drives
 * [state]/[prefetchMillis] and the progress columns, while the library and
 * download layer drives [mode]/[lastPlayedAt]/[downloadId]/[infoHash].
 * [state] and [kind] are stored as enum names (Room has no built-in enum support).
 */ 
@Entity(tableName = "streams", indices = [Index("lastPlayedAt")])
data class StreamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    /** The stream URL, or a `magnet:` link for torrent entries. */
    val url: String,
    val kind: String = com.streambuddy.app.stream.StreamKind.UNKNOWN.name,
    val mode: String = com.streambuddy.app.ui.PlaybackMode.WATCH_NOW.name,
    val state: String = com.streambuddy.app.cache.CacheState.QUEUED.name,
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long? = null,
    val cachedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val cachedMillis: Long = 0L,
    val totalMillis: Long = 0L,
    val prefetchMillis: Long = 0L,
    val error: String? = null,
    val downloadId: String? = null,
    val infoHash: String? = null,
) {
    val streamKind: com.streambuddy.app.stream.StreamKind
        get() = com.streambuddy.app.stream.StreamKind.fromName(kind)

    val cacheState: com.streambuddy.app.cache.CacheState get() = 
        com.streambuddy.app.cache.CacheState.fromName(state)

    /** Percentage of the requested pre-play window (or whole file) that is cached. */
    val progressPercent: Int?
        get() = when {
            totalMillis > 0L -> Format.percent(cachedMillis, totalMillis)
            totalBytes > 0L -> Format.percent(cachedBytes, totalBytes)
            else -> null
        }

    /** `2:00 of 12:34 cached`, `48.2 MB cached`, ... */
    val progressDetail: String
        get() = when {
            totalMillis > 0L -> "${Format.duration(cachedMillis)} of ${Format.duration(totalMillis)} cached"
            cachedBytes > 0L -> "${Format.bytes(cachedBytes)} cached"
            else -> "Nothing cached yet"
        }
}

/@Entity(tableName = "playback_sessions", indices = [Index("startedAt"), Index("policy")])
data class PlaybackSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: EncryptedString,
    val sourceKind: String,
    val mode: String,
    val policy: String,
    val startedAt: Long,
    val endedAt: Long,
    val playbackMs: Long,
    val stallCount: Int,
    val stallMs: Long,
    val avgThroughputBps: Long,
    val avgBitrateBps: Long,
    val bytesLoaded: Long,
    val riskSamples: Int,
    val baselineTruePositive: Int,
    val baselineFalsePositive: Int,
    val baselineTrueNegative: Int,
    val baselineFalseNegative: Int,
    val modelTruePositive: Int,
    val modelFalsePositive: Int,
    val modelTrueNegative: Int,
    val modelFalseNegative: Int,
    val modelVersion: Int,
)

@Entity(tableName = "risk_samples", indices = [Index("sessionId"), Index("createdAt")])
data class RiskSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val createdAt: Long,
    @ColumnInfo(name = "feature_buffer") val bufferFeature: Double,
    @ColumnInfo(name = "feature_deficit") val deficitFeature: Double,
    @ColumnInfo(name = "feature_trend") val trendFeature: Double,
    @ColumnInfo(name = "feature_stalls") val stallFeature: Double,
    @ColumnInfo(name = "feature_peer") val peerFeature: Double,
    @ColumnInfo(name = "feature_jitter") val jitterFeature: Double,
    val baselineRisk: Double,
    val modelRisk: Double,
    val modelVersion: Int,
    val acted: Boolean,
    val label: Int?,
)

@Dao
interface StreamDao {
    @Query("SELECT * FROM streams ORDER BY COALESCE(lastPlayedAt, addedAt) DESC")
    fun observeAll(): Flow<List<StreamEntity>>

    /** One-shot read for the predictor, which ranks on a worker thread. */
    @Query("SELECT * FROM streams ORDER BY addedAt DESC")
    suspend fun allOnce(): List<StreamEntity>

    @Query("SELECT * FROM streams WHERE id = :id")
    suspend fun find(id: Long): StreamEntity?

    @Query("SELECT * FROM streams WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): StreamEntity?

    @Insert
    suspend fun insert(stream: StreamEntity): Long

    @Update
    suspend fun update(stream: StreamEntity)

    @Query("DELETE FROM streams WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE streams SET lastPlayedAt = :at WHERE id = :id")
    suspend fun markPlayed(id: Long, at: Long)

    @Query("UPDATE streams SET cachedBytes = :bytes WHERE id = :id")
    suspend fun updateCachedBytes(id: Long, bytes: Long)

    @Query("UPDATE streams SET downloadId = :downloadId WHERE id = :id")
    suspend fun updateDownloadId(id: Long, downloadId: String?)

    @Query(
        "UPDATE streams SET state = :state, error = :error, updatedAt = :now WHERE id = :id"
    )
    suspend fun updateState(id: Long, state: String, error: String?, now: Long)

    @Query(
        """UPDATE streams SET
            state = :state,
            cachedBytes = :cachedBytes,
            totalBytes = :totalBytes,
            cachedMillis = :cachedMillis,
            totalMillis = :totalMillis,
            prefetchMillis = :prefetchMillis,
            error = :error,
            updatedAt = :now
        WHERE id = :id"""
    )
    suspend fun updateProgress(
        id: Long,
        state: String,
        cachedBytes: Long,
        totalBytes: Long,
        cachedMillis: Long,
        totalMillis: Long,
        prefetchMillis: Long,
        error: String?,
        now: Long
    )

    @Query(
        "UPDATE streams SET prefetchMillis = :prefetchMillis, state = :state, error = NULL, updatedAt = :now WHERE id = :id"
    )
    suspend fun resetForCaching(id: Long, prefetchMillis: Long, state: String, now: Long)
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: PlaybackSessionEntity): Long

    @Query("SELECT * FROM playback_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PlaybackSessionEntity>>

    @Query("SELECT * FROM playback_sessions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PlaybackSessionEntity>

    @Query("SELECT * FROM playback_sessions WHERE startedAt >= :since ORDER BY startedAt DESC")
    suspend fun since(since: Long): List<PlaybackSessionEntity>
}

@Dao
interface RiskSampleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(samples: List<RiskSampleEntity>)

    @Query("SELECT COUNT(*) FROM risk_samples WHERE label IS NOT NULL")
    suspend fun labelledCount(): Int

    @Query("SELECT * FROM risk_samples ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RiskSampleEntity>}
)

/** 
 * Pre-1.0 union of both histories: [StreamEntity]/[SessionDao]/[RiskSampleDao]/[LibraryDao]
 * from the library and optimizer side, [WatchDao] from the pre-cache predictor side.
 *
 * There is no shipped version to migrate from, so a fresh database is acceptable.
 */ 
@Database(
    entities = [
        StreamEntity::class,
        PlaybackSessionEntity::class,
        RiskSampleEntity::class,
        com.streambuddy.app.data.LibraryEntity::class,
        com.streambuddy.app.data.WatchEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(SecretConverters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun streams(): StreamDao
    abstract fun sessions(): SessionDao
    abstract fun riskSamples(): RiskSampleDao
    abstract fun library(): com.streambuddy.app.data.LibraryDao
    abstract fun watchHistory(): com.streambuddy.app.data.WatchDao

    companion object {
        private const val DB_NAME = "streambuddy.db"

        @Volatile
        private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDb::class.java,
                DB_NAME,
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}