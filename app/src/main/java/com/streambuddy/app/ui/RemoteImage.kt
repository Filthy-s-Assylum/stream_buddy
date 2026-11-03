package com.streambuddy.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Minimal, allowlisted image loader. No third-party image library is needed and
 * no arbitrary URL can be fetched:
 *
 *  - HTTPS only;
 *  - host must be image.tmdb.org;
 *  - path must start with /t/p/;
 *  - redirects are disabled;
 *  - responses are capped at [MAX_IMAGE_BYTES];
 *  - decoded bitmaps are sampled to the requested width;
 *  - memory and disk caches use a hash of the URL, never the URL itself.
 */
object PosterLoader {

    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val DISK_BUDGET_BYTES = 128L * 1024 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(com.streambuddy.app.net.NetworkGuard.interceptor())
        .build()

    private val memory = object : LruCache<String, Bitmap>(memoryBudget()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cached(url: String): Bitmap? = memory.get(url)

    suspend fun load(context: Context, url: String, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        memory.get(url)?.let { return@withContext it }
        val validated = validate(url) ?: return@withContext null
        val file = diskFile(context, validated)
        val bitmap = if (file.isFile) {
            decode(file, targetWidthPx)
        } else {
            download(validated)?.let { bytes ->
                writeAtomically(file, bytes)
                decode(bytes, targetWidthPx)
            }
        }
        if (bitmap != null) memory.put(url, bitmap)
        bitmap
    }

    private fun validate(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https") return null
        if (!parsed.host.equals("image.tmdb.org", ignoreCase = true)) return null
        if (!parsed.encodedPath.startsWith("/t/p/")) return null
        if (parsed.encodedPath.contains("..")) return null
        if (parsed.encodedPath.length > 220) return null
        return parsed.toString()
    }

    private fun download(url: String): ByteArray? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", "StreamBuddy/1.0 (Android)").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val type = response.header("Content-Type").orEmpty()
            if (!type.startsWith("image/")) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_IMAGE_BYTES) return null
            val bytes = body.bytes()
            if (bytes.size > MAX_IMAGE_BYTES) null else bytes
        }
    }.getOrNull()

    private fun decode(file: File, targetWidthPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        BitmapFactory.decodeFile(file.absolutePath, options(bounds, targetWidthPx))
    }.getOrNull()

    private fun decode(bytes: ByteArray, targetWidthPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options(bounds, targetWidthPx))
    }.getOrNull()

    private fun options(bounds: BitmapFactory.Options, targetWidthPx: Int): BitmapFactory.Options {
        var sample = 1
        val width = bounds.outWidth.coerceAtLeast(1)
        while (width / (sample * 2) >= targetWidthPx.coerceAtLeast(128)) sample *= 2
        return BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) temp.delete()
            trimDiskCache(file.parentFile)
        }
    }

    private fun trimDiskCache(directory: File?) {
        val files = directory?.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_BUDGET_BYTES) return
        files.filter { it.isFile && it.name != ".nomedia" }
            .sortedBy { it.lastModified() }
            .forEach { old ->
                if (total <= DISK_BUDGET_BYTES) return
                total -= old.length()
                old.delete()
            }
    }

    private fun diskFile(context: Context, url: String): File {
        val directory = File(context.cacheDir, "posters").apply { mkdirs() }
        File(directory, ".nomedia").takeIf { !it.exists() }?.writeText("")
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(directory, name)
    }

    private fun memoryBudget(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return (max / 8).coerceIn(8L * 1024 * 1024, 64L * 1024 * 1024).toInt()
    }
}

@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf(url?.let(PosterLoader::cached)) }
    LaunchedEffect(url) {
        if (url != null && bitmap == null) {
            bitmap = PosterLoader.load(context, url, 420)
        }
    }
    Box(modifier) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else {
            placeholder()
        }
    }
}
