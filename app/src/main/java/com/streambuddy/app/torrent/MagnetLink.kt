package com.streambuddy.app.torrent

/**
 * A `magnet:` link, reduced to the parts the app actually needs.
 *
 * Magnet links carry no file data — just an info hash plus optional hints. Parsing is kept pure so
 * it can be unit-tested and so the same code can vet a link pasted into the queue, shared from
 * another app, or handed over by a deep link.
 */
data class MagnetLink(
    /** 40 lowercase hex characters (SHA-1 info hash, BEP 9). */
    val infoHashHex: String,
    /** `dn=` — the display name the publisher suggested. */
    val displayName: String?,
    /** `tr=` trackers, in the order they appeared. */
    val trackers: List<String>,
    /** `xl=` — exact total length in bytes, when the publisher included it. */
    val exactLengthBytes: Long?,
) {
    /** Short, stable identifier used in URIs and as a cache key. */
    val shortHash: String get() = infoHashHex.take(8)

    /**
     * URI the player uses for this torrent. Resolves to one file via [fileIndex]:
     * `streambuddy-torrent://<infohash>/<fileIndex>`.
     */
    fun playbackUri(fileIndex: Int): String = "$SCHEME://$infoHashHex/$fileIndex"

    companion object {
        const val SCHEME = "streambuddy-torrent"

        /** Magnet v1 info hash URN prefix (BEP 9); the hash follows it. */
        const val URN_BTIH = "urn:btih:"

        /**
         * Parses a magnet URI. Returns `null` when the string is not a magnet or has no usable
         * BitTorrent v1 info hash in either hex (40 chars) or base32 (32 chars) form.
         */
        fun parse(raw: String): MagnetLink? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("magnet:?", ignoreCase = true)) return null

            val query = trimmed.substringAfter('?', "")
            if (query.isEmpty()) return null

            var infoHash: String? = null
            var name: String? = null
            var exactLength: Long? = null
            val trackers = mutableListOf<String>()

            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val key = pair.substringBefore('=').lowercase()
                val value = decode(pair.substringAfter('=', ""))
                when (key) {
                    "xt" -> if (infoHash == null && value.startsWith(URN_BTIH, ignoreCase = true)) {
                        // `urn:btih:<hash>` — take everything after the prefix, not after the
                        // first colon (which would leave "btih:<hash>").
                        infoHash = normalizeInfoHash(value.substring(URN_BTIH.length))
                    }

                    "dn" -> if (name == null && value.isNotBlank()) name = value.trim()
                    "tr" -> if (value.startsWith("http://", true) ||
                        value.startsWith("https://", true) ||
                        value.startsWith("udp://", true)
                    ) {
                        trackers += value.trim()
                    }

                    "xl" -> exactLength = value.toLongOrNull()?.takeIf { it > 0L }
                }
            }

            val hash = infoHash ?: return null
            return MagnetLink(
                infoHashHex = hash,
                displayName = name,
                trackers = trackers.distinct(),
                exactLengthBytes = exactLength,
            )
        }

        /** `true` when [raw] looks like a magnet link, without doing the full parse. */
        fun looksLikeMagnet(raw: String): Boolean =
            raw.trim().startsWith("magnet:?", ignoreCase = true)

        /**
         * Accepts a hex (40 chars) or base32 (32 chars) info hash and returns lowercase hex.
         * Base32 is the old BEP 9 form that some indexers still emit.
         */
        fun normalizeInfoHash(candidate: String): String? {
            val value = candidate.trim()
            if (value.length == 40 && value.all { it.isHexDigit() }) return value.lowercase()
            if (value.length == 32) {
                val bytes = base32Decode(value) ?: return null
                if (bytes.size != 20) return null
                return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            }
            return null
        }

        private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        /** RFC 4648 base32 (no padding), as used by `urn:btih:` links. */
        private fun base32Decode(input: String): ByteArray? {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var buffer = 0
            var bits = 0
            val out = ArrayList<Byte>(20)
            for (rawChar in input) {
                val char = rawChar.uppercaseChar()
                val value = alphabet.indexOf(char)
                if (value < 0) return null
                buffer = (buffer shl 5) or value
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    out += ((buffer shr bits) and 0xFF).toByte()
                }
            }
            return out.toByteArray()
        }

        /** Percent-decoding for `dn=` values and tracker URLs (`+` is a space in queries). */
        private fun decode(value: String): String = try {
            java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            value
        }
    }
}
