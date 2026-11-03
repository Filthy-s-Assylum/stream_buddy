package com.streambuddy.app.util

import java.util.Locale

/** Small, dependency-free formatters used across the UI. */
object Format {

    /** `0 B`, `812 KB`, `14.2 MB`, `1.4 GB`. */
    fun bytes(value: Long): String {
        if (value <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var unit = 0
        while (size >= 1024.0 && unit < units.lastIndex) {
            size /= 1024.0
            unit++
        }
        return if (unit == 0) {
            "${value} B"
        } else {
            String.format(Locale.US, "%.1f %s", size, units[unit])
        }
    }

    /** `0:42`, `12:07`, `1:02:03`. */
    fun duration(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0L) / 1000L)
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** Rounded percentage of [part] in [whole], or `null` when [whole] is unknown. */
    fun percent(part: Long, whole: Long): Int? {
        if (whole <= 0L) return null
        return ((part.coerceIn(0, whole) * 100L) / whole).toInt()
    }

    /** Truncates long server messages for display in list rows / notifications. */
    fun shortMessage(message: String?, maxLength: Int = 160): String? {
        if (message.isNullOrBlank()) return null
        return if (message.length <= maxLength) message else message.take(maxLength - 1) + "…"
    }
}
