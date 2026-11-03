package com.streambuddy.app.stream

import java.net.URI

/**
 * Validation/normalisation for user-entered stream URLs.
 *
 * Kept free of Android APIs so the rules are unit-testable on the JVM.
 */
object StreamUrl {

    private val SCHEME_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    sealed interface Result {
        data class Valid(val url: String, val kind: StreamKind) : Result
        data class Invalid(val message: String) : Result
    }

    /**
     * Trims the input, adds `https://` when the user omitted the scheme, then validates it as an
     * absolute http(s) URL with a host.
     */
    fun validate(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.Invalid("Enter a stream URL")
        if (trimmed.startsWith("magnet:?", ignoreCase = true)) {
            // Handled by the torrent queue before this point; here it only means "wrong field".
            return Result.Invalid("Magnet links are added with the torrent box")
        }

        val withScheme = if (SCHEME_PATTERN.containsMatchIn(trimmed)) trimmed else "https://$trimmed"

        val uri = try {
            URI(withScheme)
        } catch (_: Exception) {
            return Result.Invalid("That doesn't look like a valid URL")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.Invalid("Only http:// and https:// URLs are supported")
        }
        if (uri.host.isNullOrBlank()) {
            return Result.Invalid("The URL is missing a host name")
        }
        return Result.Valid(uri.toString(), StreamKind.fromUrl(uri.toString()))
    }

    /** Returns the normalised URL, or `null` when [raw] is not a usable http(s) URL. */
    fun normalizeOrNull(raw: String): String? = (validate(raw) as? Result.Valid)?.url

    /**
     * A readable fallback title: the last path segment without its extension, else the host.
     * e.g. `https://cdn.example.com/movies/big_buck_bunny/master.m3u8` -> `master`.
     */
    fun suggestTitle(raw: String): String {
        val url = normalizeOrNull(raw) ?: return ""
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return ""
        }
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        val last = segments.lastOrNull()
            ?.substringBeforeLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
        return last ?: uri.host.orEmpty()
    }
}
