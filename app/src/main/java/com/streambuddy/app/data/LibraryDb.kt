package com.streambuddy.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Library metadata and the user's own playable source. Text fields are
 * encrypted by [SecretConverters]; poster/backdrop are TMDB image paths, not
 * user content.
 */
@Entity(
    tableName = "library",
    indices = [
        Index("status"),
        Index("mediaType"),
        Index("lastWatchedAt"),
        Index(value = ["tmdbId", "mediaType"], unique = true),
    ],
)
data class LibraryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tmdbId: Int? = null,
    val mediaType: String,
    val title: EncryptedString,
    val overview: EncryptedString,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val year: Int? = null,
    val genres: EncryptedString,
    val network: EncryptedString? = null,
    val rating: Double? = null,
    val runtimeMinutes: Int? = null,
    val seasons: Int? = null,
    val status: String,
    val sourceUri: EncryptedString? = null,
    val sourceLabel: EncryptedString? = null,
    val infoHash: String? = null,
    val progressMs: Long = 0,
    val durationMs: Long = 0,
    val addedAt: Long,
    val updatedAt: Long,
    val lastWatchedAt: Long? = null,
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM library WHERE id = :id")
    suspend fun find(id: Long): LibraryEntity?

    @Query("SELECT * FROM library WHERE tmdbId = :tmdbId AND mediaType = :mediaType LIMIT 1")
    suspend fun findByTmdb(tmdbId: Int, mediaType: String): LibraryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: LibraryEntity): Long

    @Update
    suspend fun update(item: LibraryEntity)

    @Query("DELETE FROM library WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE library SET status = :status, updatedAt = :at WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, at: Long)

    @Query(
        "UPDATE library SET progressMs = :positionMs, durationMs = :durationMs, " +
            "lastWatchedAt = :at, updatedAt = :at, " +
            "status = CASE WHEN :completed = 1 THEN 'WATCHED' ELSE 'WATCHING' END WHERE id = :id",
    )
    suspend fun updateProgress(id: Long, positionMs: Long, durationMs: Long, at: Long, completed: Boolean)

    @Query(
        "UPDATE library SET sourceUri = :sourceUri, sourceLabel = :sourceLabel, " +
            "infoHash = :infoHash, updatedAt = :at WHERE id = :id",
    )
    suspend fun setSource(
        id: Long,
        sourceUri: EncryptedString?,
        sourceLabel: EncryptedString?,
        infoHash: String?,
        at: Long,
    )

    @Query("UPDATE library SET durationMs = :durationMs, updatedAt = :at WHERE id = :id")
    suspend fun setDuration(id: Long, durationMs: Long, at: Long)

    @Query("SELECT COUNT(*) FROM library WHERE status = :status")
    suspend fun countStatus(status: String): Int
}

class LibraryRepository(private val dao: LibraryDao) {

    val items: Flow<List<LibraryItem>> = dao.observeAll().map { rows -> rows.map(::toItem) }

    suspend fun find(id: Long): LibraryItem? = dao.find(id)?.let(::toItem)

    suspend fun findByTmdb(tmdbId: Int, mediaType: MediaType): LibraryItem? =
        dao.findByTmdb(tmdbId, mediaType.name)?.let(::toItem)

    suspend fun add(candidate: MetadataCandidate): Long {
        val existing = dao.findByTmdb(candidate.tmdbId, candidate.mediaType.name)
        if (existing != null) return existing.id
        val item = candidate.toLibraryItem()
        return dao.insert(toEntity(item))
    }

    /**
     * Adds a title that has no TMDB match, for example a personal recording or
     * a magnet the user shared into Stream Buddy. Metadata stays minimal and
     * the user supplies the source.
     */
    suspend fun addCustom(
        title: String,
        sourceUri: String?,
        sourceLabel: String?,
        infoHash: String?,
        mediaType: MediaType = MediaType.MOVIE,
        overview: String = "",
    ): Long {
        val now = System.currentTimeMillis()
        val item = LibraryItem(
            id = 0,
            tmdbId = null,
            mediaType = mediaType,
            title = title.trim().ifBlank { "Untitled" }.take(300),
            overview = overview.take(4_000),
            posterPath = null,
            backdropPath = null,
            year = null,
            genres = emptyList(),
            network = null,
            rating = null,
            runtimeMinutes = null,
            seasons = null,
            status = LibraryStatus.WATCHLIST,
            sourceUri = sourceUri?.trim()?.takeIf { it.isNotEmpty() },
            sourceLabel = sourceLabel?.trim()?.takeIf { it.isNotEmpty() },
            infoHash = infoHash,
            progressMs = 0,
            durationMs = 0,
            addedAt = now,
            updatedAt = now,
            lastWatchedAt = null,
        )
        return dao.insert(toEntity(item))
    }

    suspend fun update(item: LibraryItem) = dao.update(toEntity(item))

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun setStatus(id: Long, status: LibraryStatus) =
        dao.setStatus(id, status.name, System.currentTimeMillis())

    suspend fun attachSource(id: Long, sourceUri: String, label: String?, infoHash: String?) =
        dao.setSource(
            id = id,
            sourceUri = sourceUri.takeIf { it.isNotBlank() }?.let(::EncryptedString),
            sourceLabel = label?.takeIf { it.isNotBlank() }?.let(::EncryptedString),
            infoHash = infoHash,
            at = System.currentTimeMillis(),
        )

    suspend fun clearSource(id: Long) =
        dao.setSource(id, null, null, null, System.currentTimeMillis())

    suspend fun updateProgress(id: Long, positionMs: Long, durationMs: Long, completed: Boolean) =
        dao.updateProgress(id, positionMs, durationMs, System.currentTimeMillis(), completed)

    suspend fun setDuration(id: Long, durationMs: Long) =
        dao.setDuration(id, durationMs, System.currentTimeMillis())

    fun toEntity(item: LibraryItem): LibraryEntity = LibraryEntity(
        id = item.id,
        tmdbId = item.tmdbId,
        mediaType = item.mediaType.name,
        title = EncryptedString(item.title),
        overview = EncryptedString(item.overview),
        posterPath = item.posterPath,
        backdropPath = item.backdropPath,
        year = item.year,
        genres = EncryptedString(item.genres.joinToString(GENRE_SEPARATOR)),
        network = item.network?.let(::EncryptedString),
        rating = item.rating,
        runtimeMinutes = item.runtimeMinutes,
        seasons = item.seasons,
        status = item.status.name,
        sourceUri = item.sourceUri?.let(::EncryptedString),
        sourceLabel = item.sourceLabel?.let(::EncryptedString),
        infoHash = item.infoHash,
        progressMs = item.progressMs,
        durationMs = item.durationMs,
        addedAt = item.addedAt,
        updatedAt = item.updatedAt,
        lastWatchedAt = item.lastWatchedAt,
    )

    private fun toItem(row: LibraryEntity): LibraryItem = LibraryItem(
        id = row.id,
        tmdbId = row.tmdbId,
        mediaType = runCatching { MediaType.valueOf(row.mediaType) }.getOrDefault(MediaType.MOVIE),
        title = row.title.value,
        overview = row.overview.value,
        posterPath = row.posterPath,
        backdropPath = row.backdropPath,
        year = row.year,
        genres = row.genres.value.split(GENRE_SEPARATOR).filter { it.isNotBlank() },
        network = row.network?.value?.takeIf { it.isNotBlank() },
        rating = row.rating,
        runtimeMinutes = row.runtimeMinutes,
        seasons = row.seasons,
        status = runCatching { LibraryStatus.valueOf(row.status) }.getOrDefault(LibraryStatus.WATCHLIST),
        sourceUri = row.sourceUri?.value?.takeIf { it.isNotBlank() },
        sourceLabel = row.sourceLabel?.value?.takeIf { it.isNotBlank() },
        infoHash = row.infoHash,
        progressMs = row.progressMs,
        durationMs = row.durationMs,
        addedAt = row.addedAt,
        updatedAt = row.updatedAt,
        lastWatchedAt = row.lastWatchedAt,
    )

    private companion object {
        const val GENRE_SEPARATOR = "|"
    }
}
