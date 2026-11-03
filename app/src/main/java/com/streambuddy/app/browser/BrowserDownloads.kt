package com.streambuddy.app.browser

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import com.streambuddy.app.security.ScanReport
import com.streambuddy.app.security.SecurityCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Downloads started by the built-in browser. Everything is written to
 * app-private storage, size-capped, scanned and (if malicious) quarantined.
 * Nothing is written to shared storage and nothing is executed.
 */
object BrowserDownloads {

    const val MAX_TORRENT_BYTES = 20L * 1024L * 1024L
    const val MAX_FILE_BYTES = 4L * 1024L * 1024L * 1024L

    data class Result(
        val file: File?,
        val report: ScanReport?,
        val torrent: Boolean,
        val error: String?,
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            // Never let a third-party redirect downgrade an HTTPS page to
            // cleartext. This protects both the request and the payload.
            if (request.url.isHttps && !response.request.url.isHttps) {
                response.close()
                throw IOException("Blocked an HTTPS-to-HTTP redirect")
            }
            response
        }
        .addInterceptor(com.streambuddy.app.net.NetworkGuard.interceptor())
        .build()

    fun torrentDirectory(context: Context): File =
        File(context.filesDir, "torrents/meta").apply { mkdirs() }

    fun captureDirectory(context: Context): File =
        File(context.filesDir, "browser").apply { mkdirs() }

    suspend fun download(
        context: Context,
        security: SecurityCenter,
        url: String,
        mimeType: String?,
        contentDisposition: String?,
        contentLength: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
            ?: return@withContext Result(null, null, false, "Invalid download link.")
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            return@withContext Result(null, null, false, "Only http(s) downloads are allowed.")
        }
        val guessed = runCatching {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        }.getOrNull().orEmpty()
        val name = sanitizeName(
            guessed.ifBlank {
                uri.lastPathSegment ?: "download"
            },
        )
        val isTorrent = mimeType?.contains("bittorrent", ignoreCase = true) == true ||
            name.endsWith(".torrent", ignoreCase = true) ||
            uri.path?.endsWith(".torrent", ignoreCase = true) == true
        val limit = if (isTorrent) MAX_TORRENT_BYTES else MAX_FILE_BYTES
        if (contentLength > limit) {
            return@withContext Result(
                null,
                null,
                isTorrent,
                "That file is larger than the ${if (isTorrent) "20 MB torrent" else "4 GB browser"} limit.",
            )
        }

        val directory = if (isTorrent) torrentDirectory(context) else captureDirectory(context)
        directory.mkdirs()
        if (directory.usableSpace < 64L * 1024L * 1024L) {
            return@withContext Result(null, null, isTorrent, "Not enough free space for this download.")
        }
        val extension = if (isTorrent) ".torrent" else name.substringAfterLast('.', "").let {
            if (it.isBlank()) extensionForMime(mimeType) else ".${it.lowercase(Locale.US)}"
        }
        val base = if (isTorrent) name.removeSuffix(".torrent") else name.substringBeforeLast('.', name)
        val unique = "${System.currentTimeMillis()}-${base.take(80)}$extension"
        val temporary = File(directory, "$unique.part")
        val target = File(directory, unique)

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result(
                        null,
                        null,
                        isTorrent,
                        "The server returned HTTP ${response.code}.",
                    )
                }
                val body = response.body ?: return@withContext Result(
                    null,
                    null,
                    isTorrent,
                    "The server returned an empty response.",
                )
                val declared = body.contentLength()
                if (declared > limit) {
                    return@withContext Result(
                        null,
                        null,
                        isTorrent,
                        "That download exceeds the size limit.",
                    )
                }
                temporary.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > limit) {
                                throw IOException("Download exceeded the size limit")
                            }
                            output.write(buffer, 0, read)
                            onProgress(total, declared.coerceAtLeast(0L))
                        }
                        output.flush()
                    }
                }
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } catch (throwable: Throwable) {
            runCatching { temporary.delete() }
            return@withContext Result(
                null,
                null,
                isTorrent,
                throwable.message ?: "The download failed.",
            )
        }

        if (isTorrent) {
            return@withContext Result(target, null, true, null)
        }

        val report = security.scanForPlayback(target, "Browser · ${target.name}")
        if (report?.blocked == true) {
            return@withContext Result(
                null,
                report,
                false,
                "Blocked by the malware scanner and moved to quarantine: ${report.summary}",
            )
        }
        Result(target, report, false, null)
    }

    private fun sanitizeName(raw: String): String {
        val cleaned = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .trimStart('.')
            .take(120)
        return cleaned.ifBlank { "download" }
    }

    private fun extensionForMime(mimeType: String?): String {
        val mime = mimeType?.substringBefore(';')?.trim().orEmpty()
        if (mime.isBlank()) return ".bin"
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { ".$it" } ?: ".bin"
    }

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) StreamBuddyBrowser/1.0"
}
