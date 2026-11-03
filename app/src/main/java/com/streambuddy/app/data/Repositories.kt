package com.streambuddy.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class StreamRepository(
    private val dao: StreamDao,
) {
    val streams: Flow<List<StreamItem>> = dao.observeAll().map { rows -> rows.map(::toItem) }

    suspend fun add(
        title: String,
        uri: String,
        kind: StreamKind,
        mode: PlaybackMode = PlaybackMode.WATCH_NOW,
        downloadId: String? = null,
        infoHash: String? = null,
    ): Long = dao.insert(
        StreamEntity(
            title = EncryptedString(title.trim().ifBlank { "Untitled" }),
            uri = EncryptedString(uri.trim()),
            kind = kind.name,
            mode = mode.name,
            addedAt = System.currentTimeMillis(),
            downloadId = downloadId,
            infoHash = infoHash,
        ),
    )

    suspend fun find(id: Long): StreamItem? = dao.find(id)?.let(::toItem)

    suspend fun markPlayed(id: Long) = dao.markPlayed(id, System.currentTimeMillis())

    suspend fun updateCachedBytes(id: Long, bytes: Long) = dao.updateCachedBytes(id, bytes)

    suspend fun setDownloadId(id: Long, downloadId: String?) = dao.updateDownloadId(id, downloadId)

    suspend fun delete(id: Long) = dao.deleteById(id)

    private fun toItem(row: StreamEntity) = StreamItem(
        id = row.id,
        title = row.title.value,
        uri = row.uri.value,
        kind = runCatching { StreamKind.valueOf(row.kind) }.getOrDefault(StreamKind.MP4),
        mode = runCatching { PlaybackMode.valueOf(row.mode) }.getOrDefault(PlaybackMode.WATCH_NOW),
        addedAt = row.addedAt,
        lastPlayedAt = row.lastPlayedAt,
        cachedBytes = row.cachedBytes,
        downloadId = row.downloadId,
        infoHash = row.infoHash,
    )
}

class SessionRepository(
    private val dao: SessionDao,
) {
    val recent: Flow<List<PlaybackSessionEntity>> = dao.observeRecent(20)

    suspend fun insert(session: PlaybackSessionEntity): Long = dao.insert(session)

    suspend fun recent(limit: Int = 20): List<PlaybackSessionEntity> = dao.recent(limit)
}
