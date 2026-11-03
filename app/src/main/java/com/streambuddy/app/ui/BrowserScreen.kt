package com.streambuddy.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.streambuddy.app.browser.BrowserDownloads
import com.streambuddy.app.browser.PageLinkScraper
import com.streambuddy.app.browser.ScrapedKind
import com.streambuddy.app.browser.ScrapedLink
import com.streambuddy.app.core.Fmt
import com.streambuddy.app.data.MediaType
import com.streambuddy.app.net.NetworkGuard
import com.streambuddy.app.net.VpnRequiredMessage
import com.streambuddy.app.security.ScanVerdict
import com.streambuddy.app.security.SecurityCenter
import com.streambuddy.app.torrent.Magnet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * A small, self-contained browser for pages the user chooses to visit.
 *
 * It deliberately ships with no content index, search suggestions or
 * preloaded piracy sites. Magnet links are intercepted and require explicit
 * confirmation; .torrent and media downloads are written to app-private
 * storage, size-capped, scanned and quarantined when malicious.
 *
 * "Find links on this page" is a deliberate, per-page action: it reads links
 * from the page the user already opened. It never crawls, follows links or
 * searches an index.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    security: SecurityCenter,
    vpnBlocked: Boolean,
    initialUrl: String? = null,
    onInitialUrlConsumed: () -> Unit = {},
    onAddSource: (title: String, uri: String, label: String?, infoHash: String?, mediaType: MediaType) -> Unit,
    onAddTorrent: (title: String, file: java.io.File) -> Unit,
    onAddLocalMedia: (title: String, file: java.io.File) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnAddSource by rememberUpdatedState(onAddSource)
    val currentOnAddTorrent by rememberUpdatedState(onAddTorrent)
    val currentOnAddLocalMedia by rememberUpdatedState(onAddLocalMedia)

    var address by remember { mutableStateOf("") }
    var pageProgress by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableLongStateOf(0L) }
    var downloadTotal by remember { mutableLongStateOf(0L) }
    var lastProgressUpdate by remember { mutableLongStateOf(0L) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    var scraping by remember { mutableStateOf(false) }
    var scraped by remember { mutableStateOf<List<ScrapedLink>?>(null) }
    var scrapeMessage by remember { mutableStateOf<String?>(null) }

    var pendingMagnet by remember { mutableStateOf<String?>(null) }
    var pendingStream by remember { mutableStateOf<ScrapedLink?>(null) }
    var pendingTorrent by remember { mutableStateOf<BrowserDownloads.Result?>(null) }
    var pendingMedia by remember { mutableStateOf<BrowserDownloads.Result?>(null) }

    // Declared before the WebView so the client and download listener can call
    // it without forward references.
    fun startDownload(
        url: String,
        mimeType: String?,
        contentDisposition: String?,
        contentLength: Long,
    ) {
        if (downloading != null) return
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            status = "Only http(s) downloads are allowed."
            return
        }
        if (NetworkGuard.isBlocked()) {
            status = VpnRequiredMessage.network
            return
        }
        downloading = "Preparing download…"
        downloadProgress = 0L
        downloadTotal = contentLength.coerceAtLeast(0L)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                BrowserDownloads.download(
                    context = context,
                    security = security,
                    url = url,
                    mimeType = mimeType,
                    contentDisposition = contentDisposition,
                    contentLength = contentLength,
                    onProgress = { done, total ->
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 250 || done == total) {
                            lastProgressUpdate = now
                            scope.launch {
                                downloadProgress = done
                                downloadTotal = total
                            }
                        }
                    },
                )
            }
            downloading = null
            when {
                result.error != null -> status = result.error
                result.torrent && result.file != null -> pendingTorrent = result
                result.file == null -> status = "The download could not be read."
                isPlayable(result.file.name) -> pendingMedia = result
                else -> {
                    result.file.delete()
                    status = "Downloaded ${result.file.name}, but it is not playable media, so it was removed."
                }
            }
        }
    }

    fun intercept(raw: String): Boolean {
        val url = raw.trim()
        return when {
            url.startsWith("magnet:", ignoreCase = true) -> {
                pendingMagnet = url
                true
            }
            url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true) -> {
                if (NetworkGuard.isBlocked()) {
                    status = VpnRequiredMessage.network
                    true
                } else {
                    false
                }
            }
            else -> {
                status = "Blocked an unsupported link scheme."
                true
            }
        }
    }

    val webView = remember {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setGeolocationEnabled(false)
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                safeBrowsingEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                builtInZoomControls = true
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            WebView.setWebContentsDebuggingEnabled(false)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    intercept(request.url.toString())

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    intercept(url)

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url != null && url != HOME_URL) address = url
                    loading = true
                    pageError = null
                    canGoBack = view.canGoBack()
                    canGoForward = view.canGoForward()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false
                    if (url != null && url != HOME_URL) address = url
                    canGoBack = view.canGoBack()
                    canGoForward = view.canGoForward()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        loading = false
                        pageError = "The page could not be loaded (${error.errorCode}). Check the address and your connection."
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    // Fail closed for every page subresource, including
                    // scripts, images and XHR, if the VPN route disappears.
                    if (NetworkGuard.isBlocked()) {
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                    return null
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    pageProgress = newProgress
                }

                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                    // The browser never needs camera, microphone or MIDI.
                    request.deny()
                }
            }

            setDownloadListener(
                DownloadListener { url, _ , contentDisposition, mimeType, contentLength ->
                    startDownload(url, mimeType, contentDisposition, contentLength)
                },
            )

            loadUrl(HOME_URL)
        }
    }

    fun scrapeCurrentPage() {
        if (scraping) return
        if (NetworkGuard.isBlocked()) {
            scrapeMessage = VpnRequiredMessage.network
            return
        }
        val target = webView.url?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: address.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        if (target == null) {
            scrapeMessage = "Open a web page first, then find its links."
            return
        }
        scraping = true
        scrapeMessage = null
        webView.evaluateJavascript(PageLinkScraper.domScript) { raw ->
            val domLinks = parseEvaluatedArray(raw)
            scope.launch {
                val fromDom = withContext(Dispatchers.Default) {
                    PageLinkScraper.classify(domLinks, target)
                }
                val relevant = fromDom.filter { it.kind != ScrapedKind.SUBTITLE }
                if (relevant.isNotEmpty()) {
                    scraped = relevant
                    scraping = false
                } else {
                    val result = withContext(Dispatchers.IO) { PageLinkScraper.scrape(target) }
                    scraping = false
                    result.fold(
                        onSuccess = { links ->
                            val filtered = links.filter { it.kind != ScrapedKind.SUBTITLE }
                            if (filtered.isEmpty()) {
                                scrapeMessage = "No magnet, torrent or media links were found on this page."
                            } else {
                                scraped = filtered
                            }
                        },
                        onFailure = { error ->
                            scrapeMessage = error.message ?: "This page could not be scanned."
                        },
                    )
                }
            }
        }
    }

    fun navigate(input: String) {
        val value = input.trim()
        if (value.isBlank()) return
        val target = when {
            value.startsWith("magnet:", ignoreCase = true) -> {
                pendingMagnet = value
                return
            }
            value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true) -> value
            !value.contains(' ') && value.contains('.') -> "https://$value"
            else -> "https://duckduckgo.com/?q=" + android.net.Uri.encode(value)
        }
        pageError = null
        address = target
        if (NetworkGuard.isBlocked()) {
            status = VpnRequiredMessage.network
            return
        }
        webView.loadUrl(target)
    }

    LaunchedEffect(initialUrl) {
        val target = initialUrl?.trim().orEmpty()
        if (target.isNotEmpty()) {
            if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
                if (NetworkGuard.isBlocked()) {
                    status = VpnRequiredMessage.network
                } else {
                    address = target
                    webView.loadUrl(target)
                }
            }
            onInitialUrlConsumed()
        }
    }

    LaunchedEffect(vpnBlocked) {
        if (vpnBlocked) {
            runCatching { webView.stopLoading() }
            if (webView.url != HOME_URL) webView.loadUrl(HOME_URL)
            status = VpnRequiredMessage.network
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }
    }

    BackHandler(enabled = webView.canGoBack()) { webView.goBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = { if (webView.canGoBack()) webView.goBack() }, enabled = canGoBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            IconButton(onClick = { if (webView.canGoForward()) webView.goForward() }, enabled = canGoForward) {
                Icon(Icons.Filled.ArrowForward, contentDescription = "Forward")
            }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.take(2_048) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search or enter address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { navigate(address) }),
            )
            IconButton(onClick = { webView.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload")
            }
            IconButton(onClick = { webView.loadUrl(HOME_URL) }) {
                Icon(Icons.Filled.Home, contentDescription = "Browser home")
            }
        }

        if (pageProgress in 1..99) {
            LinearProgressIndicator(
                progress = { pageProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        pageError?.let { message ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { webView.reload() }) { Text("Retry") }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = {
                    (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                    webView
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (loading && pageProgress < 100) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
            }
            if (vpnBlocked) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("VPN required", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        VpnRequiredMessage.network,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onOpenSettings) { Text("Open settings") }
                }
            }
        }

        if (downloading != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(downloading.orEmpty(), style = MaterialTheme.typography.bodySmall)
                if (downloadTotal > 0) {
                    LinearProgressIndicator(
                        progress = { (downloadProgress.toDouble() / downloadTotal).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${Fmt.bytes(downloadProgress)} / ${Fmt.bytes(downloadTotal)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (scraping) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                Text("Reading links from this page…", style = MaterialTheme.typography.bodySmall)
            }
        }

        (scrapeMessage ?: status)?.let { message ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    if (scrapeMessage == message) scrapeMessage = null else status = null
                }) { Text("Dismiss") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { scrapeCurrentPage() }, enabled = !scraping) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.height(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Find links on this page")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenSettings) { Text("Security") }
        }
        Text(
            "Only links from the page you opened are read. No index or crawl is built in.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    scraped?.let { links ->
        AlertDialog(
            onDismissRequest = { scraped = null },
            title = { Text("Links on this page") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "Pick a link to add. Only continue if you are authorised to use the content. " +
                            "Stream Buddy never searches for torrents.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    links.forEach { link ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                "${link.kind.label} · ${link.label}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                link.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            when (link.kind) {
                                ScrapedKind.MAGNET -> TextButton(onClick = {
                                    pendingMagnet = link.url
                                    scraped = null
                                }) { Text("Add magnet") }

                                ScrapedKind.TORRENT -> TextButton(onClick = {
                                    scraped = null
                                    startDownload(link.url, "application/x-bittorrent", null, 0L)
                                }) { Text("Download torrent") }

                                ScrapedKind.HLS,
                                ScrapedKind.DASH,
                                ScrapedKind.MEDIA,
                                -> TextButton(onClick = {
                                    pendingStream = link
                                    scraped = null
                                }) { Text("Add stream") }

                                ScrapedKind.SUBTITLE -> Unit
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { scraped = null }) { Text("Close") } },
        )
    }

    pendingMagnet?.let { magnet ->
        val suggested = remember(magnet) {
            runCatching { Magnet.parse(magnet).getOrThrow() }.getOrNull()?.displayName
                ?: "Magnet link"
        }
        var title by remember(magnet) { mutableStateOf(suggested.take(300)) }
        var mediaType by remember(magnet) { mutableStateOf(MediaType.MOVIE) }
        val hash = remember(magnet) {
            runCatching { Magnet.parse(magnet).getOrThrow().infoHashHex }.getOrNull()
        }
        AlertDialog(
            onDismissRequest = { pendingMagnet = null },
            title = { Text("Add this magnet?") },
            text = {
                Column {
                    Text(
                        "The magnet will be attached to a library title. Only continue if you are authorised " +
                            "to stream this content. Stream Buddy never searches for torrents.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(300) },
                        label = { Text("Title") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MediaType.entries.forEach { type ->
                            FilterChip(
                                selected = mediaType == type,
                                onClick = { mediaType = type },
                                label = { Text(if (type == MediaType.MOVIE) "Movie" else "TV") },
                            )
                        }
                    }
                    if (hash == null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "This magnet has no valid v1 info hash.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = hash != null && title.isNotBlank(),
                    onClick = {
                        currentOnAddSource(title.trim(), magnet, "Magnet · $title", hash, mediaType)
                        pendingMagnet = null
                        status = "Magnet added to your library."
                    },
                ) { Text("Add to library") }
            },
            dismissButton = { TextButton(onClick = { pendingMagnet = null }) { Text("Cancel") } },
        )
    }

    pendingStream?.let { link ->
        var title by remember(link.url) {
            mutableStateOf(link.url.substringBefore('?').substringAfterLast('/').take(300).ifBlank { "Web stream" })
        }
        var mediaType by remember(link.url) { mutableStateOf(MediaType.MOVIE) }
        AlertDialog(
            onDismissRequest = { pendingStream = null },
            title = { Text("Add this stream?") },
            text = {
                Column {
                    Text(
                        "Attach ${link.kind.label.lowercase(Locale.US)} to a library title. Only continue if you " +
                            "are authorised to use it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        link.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(300) },
                        label = { Text("Title") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MediaType.entries.forEach { type ->
                            FilterChip(
                                selected = mediaType == type,
                                onClick = { mediaType = type },
                                label = { Text(if (type == MediaType.MOVIE) "Movie" else "TV") },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank(),
                    onClick = {
                        currentOnAddSource(title.trim(), link.url, "Web · ${link.kind.label}", null, mediaType)
                        pendingStream = null
                        status = "Stream added to your library."
                    },
                ) { Text("Add to library") }
            },
            dismissButton = { TextButton(onClick = { pendingStream = null }) { Text("Cancel") } },
        )
    }

    pendingTorrent?.let { result ->
        val file = result.file
        if (file == null) {
            pendingTorrent = null
        } else {
            var title by remember(file) {
                mutableStateOf(file.name.removeSuffix(".torrent").take(300))
            }
            AlertDialog(
                onDismissRequest = { pendingTorrent = null },
                title = { Text("Add this .torrent?") },
                text = {
                    Column {
                        Text(
                            "The .torrent metadata was downloaded to private app storage. Only continue if you " +
                                "are authorised to stream this content.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(file.name, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it.take(300) },
                            label = { Text("Title") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = title.isNotBlank(),
                        onClick = {
                            currentOnAddTorrent(title.trim(), file)
                            pendingTorrent = null
                            status = ".torrent added to your library."
                        },
                    ) { Text("Add to library") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        file.delete()
                        pendingTorrent = null
                    }) { Text("Discard") }
                },
            )
        }
    }

    pendingMedia?.let { result ->
        val file = result.file
        if (file == null) {
            pendingMedia = null
        } else {
            AlertDialog(
                onDismissRequest = { pendingMedia = null },
                title = { Text("Add downloaded media?") },
                text = {
                    Column {
                        Text(file.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${Fmt.bytes(file.length())} · ${result.report?.verdict?.name?.lowercase(Locale.US) ?: "scanned"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        result.report?.let { report ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                report.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (report.verdict == ScanVerdict.SUSPICIOUS) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        currentOnAddLocalMedia(file.name.substringBeforeLast('.'), file)
                        pendingMedia = null
                        status = "Media added to your library."
                    }) { Text("Add to library") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        file.delete()
                        pendingMedia = null
                    }) { Text("Discard") }
                },
            )
        }
    }
}

private fun parseEvaluatedArray(raw: String?): List<String> {
    if (raw.isNullOrBlank() || raw == "null") return emptyList()
    return runCatching {
        val value = JSONTokener(raw).nextValue()
        val array = when (value) {
            is String -> JSONArray(value)
            is JSONArray -> value
            else -> JSONArray()
        }
        (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())
}

private fun isPlayable(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
    return extension in setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "mp3", "m4a", "aac", "flac",
        "ogg", "opus", "wav", "srt", "vtt", "ass", "ssa",
    )
}

private const val HOME_URL = "about:blank"
