package com.streambuddy.app.download

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import com.streambuddy.app.net.Http
import com.streambuddy.app.net.NetworkGuard
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Offline downloads live in their own immutable cache. They are never touched
 * by the playback LRU, so "download first" and "keep offline" are durable.
 */
@OptIn(UnstableApi::class)
object Downloads {

    const val CHANNEL_ID = "sb_downloads"
    const val FOREGROUND_NOTIFICATION_ID = 4102
    const val FOREGROUND_UPDATE_MS = 1_000L
    const val STOP_REASON_USER = 1

    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sb-downloads").apply { isDaemon = true }
    }

    @Volatile
    private var dbProvider: StandaloneDatabaseProvider? = null

    @Volatile
    private var downloadCache: SimpleCache? = null

    @Volatile
    private var manager: DownloadManager? = null

    @Synchronized
    private fun databaseProvider(context: Context): StandaloneDatabaseProvider {
        dbProvider?.let { return it }
        return StandaloneDatabaseProvider(context.applicationContext).also { dbProvider = it }
    }

    @Synchronized
    fun cache(context: Context): SimpleCache {
        downloadCache?.let { return it }
        val directory = File(context.applicationContext.filesDir, "downloads")
        if (!directory.exists()) directory.mkdirs()
        return SimpleCache(
            directory,
            NoOpCacheEvictor(),
            databaseProvider(context),
        ).also { downloadCache = it }
    }

    @Synchronized
    fun manager(context: Context): DownloadManager {
        manager?.let { return it }
        val created = DownloadManager(
            context.applicationContext,
            databaseProvider(context),
            cache(context),
            upstreamFactory(),
            executor,
        )
        created.maxParallelDownloads = 3
        created.minRetryCount = 3
        created.requirements = requirements(wifiOnly = true)
        manager = created
        return created
    }

    fun upstreamFactory(): HttpDataSource.Factory = OkHttpDataSource.Factory(Http.mediaClient)
        .setUserAgent("StreamBuddy/1.0 (Android)")

    fun setWifiOnly(context: Context, wifiOnly: Boolean) {
        manager(context).requirements = requirements(wifiOnly)
    }

    fun isHttpUri(uri: String): Boolean {
        val scheme = runCatching { Uri.parse(uri.trim()).scheme?.lowercase(Locale.US) }.getOrNull()
        return scheme == "http" || scheme == "https"
    }

    fun enqueue(
        context: Context,
        uri: String,
        mimeType: String,
        lastModified: Long? = null,
    ): String {
        require(isHttpUri(uri)) { "Only http(s) links can be downloaded offline." }
        NetworkGuard.check()
        val id = downloadId(uri)
        val request = DownloadRequest.Builder(id, Uri.parse(uri))
            .setMimeType(mimeType)
            .setCustomCacheKey(uri)
            .setData(if (lastModified != null) lastModified.toString().toByteArray() else null)
            .build()
        SbDownloadService.sendAddDownload(context, request, true)
        return id
    }

    fun remove(context: Context, id: String) {
        SbDownloadService.sendRemoveDownload(context, id, true)
    }

    fun pause(context: Context, id: String) {
        SbDownloadService.sendSetStopReason(context, id, STOP_REASON_USER, true)
    }

    fun resume(context: Context, id: String) {
        NetworkGuard.check()
        SbDownloadService.sendSetStopReason(context, id, Download.STOP_REASON_NONE, true)
    }

    fun retry(context: Context, download: Download) {
        NetworkGuard.check()
        val request = DownloadRequest.Builder(download.request.id, download.request.uri)
            .setMimeType(download.request.mimeType)
            .setCustomCacheKey(download.request.customCacheKey ?: download.request.uri.toString())
            .setData(download.request.data)
            .build()
        SbDownloadService.sendAddDownload(context, request, true)
    }

    fun pauseAll(context: Context) {
        SbDownloadService.sendPauseDownloads(context, true)
    }

    fun resumeAll(context: Context) {
        NetworkGuard.check()
        SbDownloadService.sendResumeDownloads(context, true)
    }

    fun snapshot(context: Context): List<Download> = runCatching {
        manager(context).downloadIndex.getDownloads().use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.download)
            }
        }
    }.getOrDefault(emptyList())

    fun completed(context: Context, uri: String): Download? =
        snapshot(context).firstOrNull {
            it.request.uri.toString() == uri && it.state == Download.STATE_COMPLETED
        }

    fun downloadId(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun mimeForUri(uri: String): String {
        val path = runCatching { Uri.parse(uri).path }.getOrNull().orEmpty().lowercase(Locale.US)
        return when {
            path.endsWith(".m3u8") || path.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") || path.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> mimeFor(path.substringAfterLast('.', "mp4"))
        }
    }

    fun mimeFor(kind: String): String = when (kind.trim().uppercase(Locale.US)) {
        "HLS", "M3U8", "M3U" -> MimeTypes.APPLICATION_M3U8
        "DASH", "MPD" -> MimeTypes.APPLICATION_MPD
        "MP4", "M4V", "MOV" -> MimeTypes.VIDEO_MP4
        "MKV" -> MimeTypes.VIDEO_MATROSKA
        "WEBM" -> MimeTypes.VIDEO_WEBM
        else -> "application/octet-stream"
    }

    private fun requirements(wifiOnly: Boolean): Requirements =
        Requirements(
            Requirements.NETWORK or if (wifiOnly) Requirements.NETWORK_UNMETERED else 0,
        )

    /** Only used by tests and process teardown. */
    @Synchronized
    fun release() {
        runCatching { manager?.release() }
        manager = null
        runCatching { downloadCache?.release() }
        downloadCache = null
        dbProvider = null
    }
}
