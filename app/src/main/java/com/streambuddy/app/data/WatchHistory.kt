package com.streambuddy.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.streambuddy.app.ai.WatchEvent

/**
 * One playback session, recorded so the on-device predictor can learn what this user actually
 * watches. Only what the model needs is stored: which stream, how far it got, and when.
 */
@Entity(tableName = "watch_events")
data class WatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val streamId: Long,
    val title: String,
    val startedAtMs: Long,
    val watchedMs: Long,
    val durationMs: Long,
    val completed: Boolean,
) {
    fun toEvent(): WatchEvent = WatchEvent(
        streamId = streamId,
        title = title,
        startedAtMs = startedAtMs,
        watchedMs = watchedMs,
        durationMs = durationMs,
        completed = completed,
    )

    companion object {
        fun from(event: WatchEvent): WatchEntity = WatchEntity(
            streamId = event.streamId,
            title = event.title,
            startedAtMs = event.startedAtMs,
            watchedMs = event.watchedMs,
            durationMs = event.durationMs,
            completed = event.completed,
        )
    }
}

@Dao
interface WatchDao {

    @Insert
    suspend fun insert(event: WatchEntity): Long

    @Query("SELECT * FROM watch_events ORDER BY startedAtMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<WatchEntity>

    @Query("SELECT COUNT(*) FROM watch_events")
    suspend fun count(): Int

    @Query("DELETE FROM watch_events WHERE startedAtMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("DELETE FROM watch_events")
    suspend fun clear()
}
