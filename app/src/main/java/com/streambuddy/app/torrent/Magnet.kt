package com.streambuddy.app.torrent

import java.net.URLDecoder
import java.util.Locale

object Magnet {

    private val INFO_HASH_HEX = Regex("^[0-9a-fA-F]{40}$")
    private val TRACKER_SCHEMES = setOf("udp", "http", "https", "ws", "wss")

    fun isMagnet(uri: String): Boolean =
        uri.trim().startsWith("magnet:", ignoreCase = true)

    fun parse(uri: String): Result<MagnetLink> = runCatching {
        val trimmed = uri.trim()
        require(trimmed.startsWith("magnet:", ignoreCase = true)) { "Not a magnet link" }
        val queryStart = trimmed.indexOf('?')
        require(queryStart >= 0 && queryStart < trimmed.length - 1) { "Magnet link has no parameters" }
        val params = parseQuery(trimmed.substring(queryStart + 1))

        var hash: String? = null
        var name: String? = null
        var length: Long? = null
        val trackers = LinkedHashSet<String>()

        for ((key, value) in params) {
            when (key.lowercase(Locale.US)) {
                "xt" -> {
                    if (hash == null && value.startsWith("urn:btih:", ignoreCase = true)) {
                        hash = normalizeHash(value.substring("urn:btih:".length))
                    }
                }
                "dn" -> if (name.isNullOrBlank()) name = value.trim().take(300).ifBlank { null }
                "xl" -> if (length == null) length = value.toLongOrNull()?.takeIf { it > 0 }
                "tr" -> {
                    val tracker = normalizeTracker(value)
                    if (tracker != null && trackers.size < 40) trackers.add(tracker)
                }
            }
        }

        val infoHash = hash ?: error("Magnet link has no supported btih info hash")
        MagnetLink(
            infoHashHex = infoHash,
            displayName = name,
            trackers = trackers.toList(),
            exactLengthBytes = length,
        )
    }

    private fun parseQuery(query: String): List<Pair<String, String>> =
        query.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val index = part.indexOf('=')
            if (index < 0) null
            else URLDecoder.decode(part.substring(0, index), "UTF-8") to
                URLDecoder.decode(part.substring(index + 1), "UTF-8")
        }

    /** btih is either 40 hex characters or 32 base32 characters. */
    private fun normalizeHash(raw: String): String {
        val value = raw.trim()
        if (INFO_HASH_HEX.matches(value)) return value.lowercase(Locale.US)
        if (value.length == 32) {
            val decoded = decodeBase32(value)
            require(decoded.size == 20) { "Invalid base32 info hash" }
            return decoded.toHex()
        }
        error("Unsupported info hash format")
    }

    private fun normalizeTracker(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.length > 512) return null
        val scheme = value.substringBefore(':', "").lowercase(Locale.US)
        if (scheme !in TRACKER_SCHEMES) return null
        if (value.contains('\n') || value.contains('\r') || value.contains('\u0000')) return null
        return value
    }

    private fun decodeBase32(value: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val output = ArrayList<Byte>(20)
        var buffer = 0
        var bits = 0
        for (character in value.uppercase(Locale.US)) {
            val index = alphabet.indexOf(character)
            require(index >= 0) { "Invalid base32 character" }
            buffer = (buffer shl 5) or index
            bits += 5
            if (bits >= 8) {
                bits -= 8
                output.add(((buffer ushr bits) and 0xff).toByte())
            }
        }
        return output.toByteArray()
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xff
            out.append(digits[value ushr 4]).append(digits[value and 0x0f])
        }
        return out.toString()
    }
}
