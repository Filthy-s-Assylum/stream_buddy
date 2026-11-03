package com.streambuddy.app.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.File
import java.security.MessageDigest
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

    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sb-downloads").apply { isDaemon = true }
    }
    private val mainExecutor = Executor { runnable -> Handler(Looper.getMainLooper()).post(runnable) }

    @Volatile
    private var downloadCache: SimpleCache? = null

    @Volatile
    private var manager: DownloadManager? = null

    @Synchronized
    fun cache(context: Context): SimpleCache {
        downloadCache?.let { return it }
        val directory = File(context.applicationContext.filesDir, "downloads")
        if (!directory.exists()) directory.mkdirs()
        return SimpleCache(
            directory,
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { downloadCache = it }
    }

    @Synchronized
    fun manager(context: Context): DownloadManager {
        manager?.let { return it }
        val created = DownloadManager(
            context.applicationContext,
            StandaloneDatabaseProvider(context.applicationContext),
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

    fun upstreamFactory(): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent("StreamBuddy/1.0 (Android)")
        .setConnectTimeoutMs(20_000)
        .setReadTimeoutMs(30_000)
        // Keep media redirects on the same scheme so an HTTPS source cannot be
        // silently downgraded to cleartext by a third-party redirect.
        .setAllowCrossProtocolRedirects(false)

    fun setWifiOnly(context: Context, wifiOnly: Boolean) {
        manager(context).requirements = requirements(wifiOnly)
    }

    fun enqueue(
        context: Context,
        uri: String,
        mimeType: String,
        lastModified: Long? = null,
    ): String {
        val id = downloadId(uri)
        val request = DownloadRequest.Builder(id, android.net.Uri.parse(uri))
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

    fun pauseAll(context: Context) {
        SbDownloadService.sendPauseDownloads(context, true)
    }

    fun resumeAll(context: Context) {
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

    fun mimeFor(kind: String): String = when (kind.uppercase()) {
        "HLS" -> "application/x-mpegURL"
        "DASH" -> "application/dash+xml"
        "MP4" -> "video/mp4"
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
    }
}
