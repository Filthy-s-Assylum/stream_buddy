package com.streambuddy.app.core

import java.util.Locale

object Fmt {
    fun bytes(value: Long): String {
        if (value <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024 && unit < units.lastIndex) {
            amount /= 1024
            unit++
        }
        return if (unit == 0) "${amount.toLong()} ${units[unit]}"
        else String.format(Locale.US, "%.1f %s", amount, units[unit])
    }

    fun rate(bitsPerSecond: Double): String {
        if (bitsPerSecond <= 0.0 || !bitsPerSecond.isFinite()) return "—"
        return when {
            bitsPerSecond >= 1_000_000_000 -> String.format(Locale.US, "%.2f Gbps", bitsPerSecond / 1_000_000_000)
            bitsPerSecond >= 1_000_000 -> String.format(Locale.US, "%.2f Mbps", bitsPerSecond / 1_000_000)
            bitsPerSecond >= 1_000 -> String.format(Locale.US, "%.0f kbps", bitsPerSecond / 1_000)
            else -> String.format(Locale.US, "%.0f bps", bitsPerSecond)
        }
    }

    fun duration(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) return "—"
        val total = seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun percent(fraction: Double): String =
        String.format(Locale.US, "%.0f%%", (fraction.coerceIn(0.0, 1.0) * 100))

    fun oneDecimal(value: Double): String =
        String.format(Locale.US, "%.1f", value)
}
