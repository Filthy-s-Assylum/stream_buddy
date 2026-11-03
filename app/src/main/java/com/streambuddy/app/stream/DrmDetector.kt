package com.streambuddy.app.stream

/**
 * Detects DRM-protected playlists.
 *
 * Standard `AES-128` HLS encryption is **not** DRM: ExoPlayer and the cache can both handle it,
 * so those streams stay cacheable. FairPlay, Widevine, PlayReady and CENC cannot be pre-cached
 * (the segments are encrypted with keys the app is not licensed to use).
 */
object DrmDetector {

    data class Protection(val scheme: String, val description: String)

    private const val WIDEVINE = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
    private const val PLAYREADY = "urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95"
    private const val FAIRPLAY_KEYFORMAT = "com.apple.streamingkeydelivery"

    /** Returns the detected DRM system, or `null` when the playlist is not DRM protected. */
    fun detect(playlistText: String): Protection? {
        for (rawLine in playlistText.lineSequence()) {
            val line = rawLine.trim()
            val isKeyTag = line.startsWith("#EXT-X-KEY", ignoreCase = true) ||
                line.startsWith("#EXT-X-SESSION-KEY", ignoreCase = true)
            if (!isKeyTag) continue

            val attributes = HlsAttributes.parse(line.substringAfter(':', ""))
            val method = attributes["METHOD"]?.uppercase() ?: continue
            if (method == "NONE") continue

            val keyFormat = attributes["KEYFORMAT"].orEmpty()
            when {
                keyFormat.contains(FAIRPLAY_KEYFORMAT, ignoreCase = true) ->
                    return Protection("FairPlay", "Apple FairPlay Streaming")

                keyFormat.contains(WIDEVINE, ignoreCase = true) ||
                    keyFormat.contains("edef8ba9", ignoreCase = true) ->
                    return Protection("Widevine", "Google Widevine")

                keyFormat.contains(PLAYREADY, ignoreCase = true) ||
                    keyFormat.contains("9a04f079", ignoreCase = true) ->
                    return Protection("PlayReady", "Microsoft PlayReady")

                // SAMPLE-AES without a DRM key format is the pre-CMAF FairPlay variant.
                method.startsWith("SAMPLE-AES") ->
                    return Protection("FairPlay", "SAMPLE-AES encryption")
            }
            // METHOD=AES-128 with no DRM key format: ordinary HLS encryption, cacheable.
        }

        val lower = playlistText.lowercase()
        if (lower.contains(WIDEVINE) || lower.contains("edef8ba9")) {
            return Protection("Widevine", "Google Widevine")
        }
        if (lower.contains(PLAYREADY) || lower.contains("9a04f079")) {
            return Protection("PlayReady", "Microsoft PlayReady")
        }
        if (lower.contains("urn:mpeg:dash:mp4protection") || lower.contains("cenc:default_kid")) {
            return Protection("CENC", "Common Encryption (CENC)")
        }
        return null
    }
}
