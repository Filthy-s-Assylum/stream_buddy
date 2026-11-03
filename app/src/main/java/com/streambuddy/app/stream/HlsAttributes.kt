package com.streambuddy.app.stream

/**
 * Parser for HLS attribute lists, e.g.
 * `BANDWIDTH=1280000,CODECS="avc1.4d401e,mp4a.40.2",RESOLUTION=1280x720`.
 *
 * Quoted values may contain commas, so a naive `split(",")` is not enough.
 */
object HlsAttributes {

    fun parse(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var index = 0
        val length = raw.length

        while (index < length) {
            while (index < length && (raw[index] == ',' || raw[index] == ' ')) index++
            if (index >= length) break

            val keyStart = index
            while (index < length && raw[index] != '=' && raw[index] != ',') index++
            val key = raw.substring(keyStart, index).trim().uppercase()

            if (index >= length || raw[index] == ',') {
                if (key.isNotEmpty()) result[key] = ""
                continue
            }

            index++ // consume '='

            val value = if (index < length && raw[index] == '"') {
                index++
                val valueStart = index
                while (index < length && raw[index] != '"') index++
                val text = raw.substring(valueStart, index)
                if (index < length) index++ // consume closing quote
                text
            } else {
                val valueStart = index
                while (index < length && raw[index] != ',') index++
                raw.substring(valueStart, index).trim()
            }

            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }
}
