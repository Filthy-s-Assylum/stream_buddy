package com.streambuddy.app.torrent

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.annotation.RequiresApi
import android.os.StatFs
import android.util.DisplayMetrics
import java.io.File

/**
 * Reads what the phone can actually decode and show, so [QualitySelector] can choose a file that
 * matches it instead of trusting the torrent's filename.
 */
object DeviceCapabilities {

    @Volatile
    private var cached: QualitySelector.DeviceProfile? = null

    @Volatile
    private var cachedHevc: Boolean? = null

    @Volatile
    private var cachedHdr: Boolean? = null

    fun profile(context: Context): QualitySelector.DeviceProfile =
        cached ?: build(context).also { cached = it }

    private fun build(context: Context): QualitySelector.DeviceProfile {
        val metrics: DisplayMetrics = context.resources.displayMetrics
        val (decodeWidth, decodeHeight) = maxDecoderSize()
        return QualitySelector.DeviceProfile(
            screenWidthPx = metrics.widthPixels,
            screenHeightPx = metrics.heightPixels,
            maxDecodeWidth = decodeWidth,
            maxDecodeHeight = decodeHeight,
            supportsHevc = supportsHevc(),
            supportsHdr = supportsHdr(context),
            availableStorageBytes = availableStorage(context),
        )
    }

    /** Largest video dimensions any hardware decoder advertises; `(0, 0)` when unknown. */
    private fun maxDecoderSize(): Pair<Int, Int> {
        var width = 0
        var height = 0
        try {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecs.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (!type.startsWith("video/")) continue
                    val capabilities = try {
                        info.getCapabilitiesForType(type).videoCapabilities
                    } catch (_: IllegalArgumentException) {
                        null
                    } ?: continue
                    val supportedWidth = capabilities.supportedWidths.upper
                    val supportedHeight = capabilities.supportedHeights.upper
                    if (supportedWidth > width) width = supportedWidth
                    if (supportedHeight > height) height = supportedHeight
                }
            }
        } catch (_: Exception) {
            // A vendor codec that misbehaves lists nothing; treat it as "unknown".
        }
        return width to height
    }

    /** HEVC playback is common on Android 5+, but not universal in the low end. */
    fun supportsHevc(): Boolean = cachedHevc ?: detectHevc().also { cachedHevc = it }

    private fun detectHevc(): Boolean {
        var supported = false
        try {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecs.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                        supported = true
                    }
                }
            }
        } catch (_: Exception) {
            // No decodable codec list: assume HEVC is unsupported and let the selector pick H.264.
        }
        return supported
    }

    /** True when the panel reports HDR10 or HLG support. */
    @Suppress("DEPRECATION") // Display.getHdrCapabilities has no non-deprecated replacement below API 30.
    fun supportsHdr(context: Context): Boolean = cachedHdr ?: run {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { hdrFromDisplay(context) }.getOrDefault(false)
        } else {
            false
        }
        cachedHdr = result
        result
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @Suppress("DEPRECATION")
    private fun hdrFromDisplay(context: Context): Boolean {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return false
        return windowManager.defaultDisplay.hdrCapabilities.supportedHdrTypes.any { type ->
            type == Display.HdrCapabilities.HDR_TYPE_HDR10 ||
                type == Display.HdrCapabilities.HDR_TYPE_HLG
        }
    }

    /** Free bytes on the volume torrents are written to. */
    private fun availableStorage(context: Context): Long = runCatching {
        val dir: File = context.getExternalFilesDir(null) ?: context.filesDir
        StatFs(dir.absolutePath).availableBytes
    }.getOrDefault(Long.MAX_VALUE / 2)
}
