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
import androidx.room.TypeConverters
import androidx.room.Update
import com.streambuddy.app.core.security.AppCrypto
import kotlinx.coroutines.flow.Flow

/**
 * Room writes the value through [SecretConverters], which encrypts every
 * user-supplied string with AES-256-GCM before it reaches SQLite.
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

@Entity(tableName = "streams", indices = [Index("lastPlayedAt")])
data class StreamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: EncryptedString,
    val uri: EncryptedString,
    val kind: String,
    val mode: String,
    val addedAt: Long,
    val lastPlayedAt: Long? = null,
    val cachedBytes: Long = 0,
    val downloadId: String? = null,
    val infoHash: String? = null,
)

@Entity(tableName = "playback_sessions", indices = [Index("startedAt"), Index("policy")])
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

    @Query("SELECT * FROM streams WHERE id = :id")
    suspend fun find(id: Long): StreamEntity?

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
}

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
    fun observeRecent(limit: Int): Flow<List<RiskSampleEntity>>
}

@Database(
    entities = [StreamEntity::class, PlaybackSessionEntity::class, RiskSampleEntity::class, LibraryEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(SecretConverters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun streams(): StreamDao
    abstract fun sessions(): SessionDao
    abstract fun riskSamples(): RiskSampleDao
    abstract fun library(): LibraryDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDb::class.java,
                "streambuddy.db",
            )
                // The original scaffold shipped an unrelated v1 schema and was
                // never released. User content lives in encrypted fields; a
                // schema reset only removes the empty scaffold tables.
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
