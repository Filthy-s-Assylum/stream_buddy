package com.streambuddy.app.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class ScrapedKind {
    MAGNET,
    TORRENT,
    HLS,
    DASH,
    MEDIA,
    SUBTITLE;

    val label: String
        get() = when (this) {
            MAGNET -> "Magnet"
            TORRENT -> "Torrent"
            HLS -> "HLS stream"
            DASH -> "DASH stream"
            MEDIA -> "Media file"
            SUBTITLE -> "Subtitle"
        }
}

data class ScrapedLink(
    val url: String,
    val kind: ScrapedKind,
    val label: String,
)

/**
 * Extracts links from a page the user explicitly opened in the built-in
 * browser. It never crawls, never follows links to other pages and never
 * searches an index, so it cannot discover content on its own.
 *
 * Two sources are supported:
 *
 *  - the live DOM, via [domScript], which also catches links rendered by
 *    JavaScript;
 *  - a single HTTP fetch of the current page, used as a fallback when the DOM
 *    cannot be read.
 *
 * Only magnet links, .torrent files, stream manifests, media files and
 * subtitles are returned. Ordinary navigation links are dropped.
 */
object PageLinkScraper {

    const val MAX_LINKS = 100
    const val MAX_BYTES = 3L * 1024L * 1024L

    private val magnetPattern = Regex("""magnet:\?[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
    private val attributePattern = Regex(
        """(?:href|src|data-src|data-href|data-file|data-url|data-video|data-magnet)\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val absolutePattern = Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)

    private val streamExtensions = setOf("m3u8")
    private val dashExtensions = setOf("mpd")
    private val subtitleExtensions = setOf("srt", "vtt", "ass", "ssa", "sub", "ttml")
    private val mediaExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "flv", "ts", "m2ts", "mts", "ogv",
        "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma",
    )

    /**
     * JavaScript evaluated inside the current page. It returns a JSON array of
     * URLs and is bounded so a hostile page cannot flood the UI.
     */
    val domScript: String = """
        (function () {
          var out = [];
          function add(value) {
            if (typeof value === 'string' && value.length > 0 && value.length < 4096 && out.length < 400) {
              out.push(value);
            }
          }
          var nodes = document.querySelectorAll(
            'a[href], video[src], audio[src], source[src], iframe[src], [data-magnet], [data-src], [data-file], [data-video]'
          );
          for (var i = 0; i < nodes.length && out.length < 400; i++) {
            var node = nodes[i];
            add(node.href);
            add(node.src);
            add(node.getAttribute && node.getAttribute('data-magnet'));
            add(node.getAttribute && node.getAttribute('data-src'));
            add(node.getAttribute && node.getAttribute('data-file'));
            add(node.getAttribute && node.getAttribute('data-video'));
          }
          var html = document.documentElement ? document.documentElement.innerHTML : '';
          var magnets = html.match(/magnet:\?[^"'<>\\\s]+/gi) || [];
          for (var j = 0; j < magnets.length && j < 50; j++) add(magnets[j]);
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /**
     * Classifies raw links collected from the DOM or from page HTML. This is a
     * pure function so it can be unit-tested without Android.
     */
    fun classify(rawLinks: Iterable<String>, baseUrl: String?): List<ScrapedLink> {
        val base = baseUrl?.let { it.toHttpUrlOrNull() }
        val results = LinkedHashMap<String, ScrapedLink>()
        for (raw in rawLinks) {
            if (results.size >= MAX_LINKS) break
            val value = raw.trim().replace("&amp;", "&")
            if (value.isEmpty()) continue
            if (value.startsWith("magnet:", ignoreCase = true)) {
                if (results.size >= MAX_LINKS) break
                if (value !in results) {
                    results[value] = ScrapedLink(value, ScrapedKind.MAGNET, "Magnet link")
                }
                continue
            }
            val resolved = resolve(value, base) ?: continue
            val kind = kindFor(resolved) ?: continue
            if (results.size >= MAX_LINKS) break
            val key = resolved.toString()
            if (key !in results) {
                results[key] = ScrapedLink(key, kind, labelFor(resolved, kind))
            }
        }
        return results.values.toList()
    }

    /** Extracts candidates from a single HTML document. Used by [scrape]. */
    fun extractFromHtml(html: String, baseUrl: String?): List<ScrapedLink> {
        val links = ArrayList<String>(128)
        magnetPattern.findAll(html).forEach { links += it.value }
        attributePattern.findAll(html).forEach { links += it.groupValues[1] }
        absolutePattern.findAll(html).forEach { links += it.value }
        return classify(links, baseUrl)
    }

    /**
     * Fetches exactly one page and extracts candidates. No cookies are sent,
     * so it cannot reuse a signed-in session, and the response is size-capped.
     */
    suspend fun scrape(pageUrl: String): Result<List<ScrapedLink>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = pageUrl.trim().toHttpUrlOrNull()
                ?: throw IOException("That is not a valid web address.")
            if (url.scheme != "https" && url.scheme != "http") {
                throw IOException("Only http(s) pages can be scanned.")
            }
            if (isLoopback(url.host)) {
                throw IOException("Local device addresses cannot be scanned.")
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.8")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("The page returned HTTP ${response.code}.")
                }
                val body = response.body ?: throw IOException("The page was empty.")
                val declared = body.contentLength()
                if (declared > MAX_BYTES) {
                    throw IOException("The page is too large to scan.")
                }
                val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.US)
                if (contentType.isNotEmpty() &&
                    "html" !in contentType &&
                    "text/plain" !in contentType
                ) {
                    throw IOException("That link is not an HTML page.")
                }
                val html = body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    val output = StringBuilder()
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_BYTES) throw IOException("The page is too large to scan.")
                        output.append(String(buffer, 0, read, Charsets.UTF_8))
                    }
                    output.toString()
                }
                extractFromHtml(html, response.request.url.toString())
            }
        }
    }

    // ---------------------------------------------------------------------

    private fun resolve(raw: String, base: HttpUrl?): HttpUrl? {
        if (raw.startsWith("javascript:", true) || raw.startsWith("mailto:", true) ||
            raw.startsWith("tel:", true) || raw.startsWith("data:", true) ||
            raw.startsWith("#")
        ) {
            return null
        }
        val direct = raw.toHttpUrlOrNull()
        if (direct != null) {
            return direct.takeIf { it.scheme == "https" || it.scheme == "http" }
        }
        val relative = runCatching { base?.resolve(raw) }.getOrNull() ?: return null
        return relative.takeIf { it.scheme == "https" || it.scheme == "http" }
    }

    private fun kindFor(url: HttpUrl): ScrapedKind? {
        val path = url.encodedPath.lowercase(Locale.US)
        val extension = path.substringAfterLast('.', "")
        return when {
            extension == "torrent" -> ScrapedKind.TORRENT
            extension in streamExtensions -> ScrapedKind.HLS
            extension in dashExtensions -> ScrapedKind.DASH
            extension in subtitleExtensions -> ScrapedKind.SUBTITLE
            extension in mediaExtensions -> ScrapedKind.MEDIA
            // Many sites serve torrents from a script path without an
            // extension, e.g. /download.php?id=123 or /torrent/456.
            "torrent" in path -> ScrapedKind.TORRENT
            else -> null
        }
    }

    private fun labelFor(url: HttpUrl, kind: ScrapedKind): String {
        val name = url.pathSegments.lastOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.take(120)
        return if (name.isNullOrBlank()) kind.label else "$name · ${kind.label}"
    }

    private fun isLoopback(host: String): Boolean {
        val normalized = host.lowercase(Locale.US).trim('[', ']')
        if (normalized == "localhost" || normalized == "::1" || normalized.endsWith(".localhost")) {
            return true
        }
        if (normalized == "0.0.0.0" || normalized == "169.254.169.254") return true
        val parts = normalized.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            val first = parts[0].toIntOrNull() ?: return false
            return first == 127
        }
        return false
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (request.url.isHttps && !response.request.url.isHttps) {
                response.close()
                throw IOException("Blocked an HTTPS-to-HTTP redirect while scanning.")
            }
            response
        }
        .addInterceptor(com.streambuddy.app.net.NetworkGuard.interceptor())
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) StreamBuddyBrowser/1.0"
}
