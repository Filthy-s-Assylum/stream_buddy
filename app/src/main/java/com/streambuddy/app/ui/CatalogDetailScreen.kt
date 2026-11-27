package com.streambuddy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streambuddy.app.core.Fmt
import com.streambuddy.app.data.MetadataCandidate
import com.streambuddy.app.metadata.DiscoverRepository
import com.streambuddy.app.metadata.TmdbImages
import com.streambuddy.app.metadata.TorrentRelease
import com.streambuddy.app.metadata.catalogTypeLabel
import com.streambuddy.app.net.VpnRequiredMessage
import com.streambuddy.app.torrent.TorrentDownloadState
import com.streambuddy.app.torrent.TorrentDownloadStatus
import kotlinx.coroutines.launch

/**
 * Full TMDB details for one catalogue title, plus the three explicit actions:
 * Watch now (resolve a magnet and start playback), Download (save the largest
 * playable file) and Magnet (inspect/copy a release without playing it).
 *
 * The app still does not crawl or auto-play anything: a magnet is resolved only
 * after one of these buttons is pressed, and the VPN gate is checked first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogDetailScreen(
    item: MetadataCandidate,
    discover: DiscoverRepository,
    inLibrary: Boolean,
    vpnBlocked: Boolean,
    download: TorrentDownloadState,
    onBack: () -> Unit,
    onWatchNow: (MetadataCandidate, TorrentRelease) -> Unit,
    onDownload: (MetadataCandidate, TorrentRelease) -> Unit,
    onPlayDownload: (MetadataCandidate, String) -> Unit,
    onAddToLibrary: (MetadataCandidate) -> Unit,
    onOpenBrowser: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var details by remember(item.tmdbId, item.mediaType) { mutableStateOf(item) }
    var releases by remember(item.tmdbId, item.mediaType) { mutableStateOf<List<TorrentRelease>?>(null) }
    var resolving by remember(item.tmdbId, item.mediaType) { mutableStateOf(false) }
    var error by remember(item.tmdbId, item.mediaType) { mutableStateOf<String?>(null) }
    var showReleases by remember(item.tmdbId, item.mediaType) { mutableStateOf(false) }
    var copied by remember(item.tmdbId, item.mediaType) { mutableStateOf(false) }

    LaunchedEffect(item.tmdbId, item.mediaType) {
        discover.details(item).onSuccess { enriched ->
            details = enriched.copy(imdbId = enriched.imdbId ?: item.imdbId)
        }
    }

    fun resolve(onReady: (List<TorrentRelease>) -> Unit) {
        if (vpnBlocked) {
            error = VpnRequiredMessage.network
            return
        }
        if (resolving) return
        val cached = releases
        if (cached != null) {
            if (cached.isEmpty()) error = "No magnet was found for this title." else onReady(cached)
            return
        }
        resolving = true
        error = null
        scope.launch {
            val result = discover.releases(details)
            resolving = false
            result.fold(
                onSuccess = { found ->
                    releases = found
                    if (found.isEmpty()) {
                        error = "No magnet was found for this title. Try the browser to pick your own source."
                    } else {
                        onReady(found)
                    }
                },
                onFailure = { error = it.message ?: "The magnet lookup failed." },
            )
        }
    }

    fun watchNow() = resolve { found ->
        found.firstOrNull()?.let { onWatchNow(details, it) }
    }

    fun downloadNow() = resolve { found ->
        found.firstOrNull()?.let { onDownload(details, it) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(details.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                RemoteImage(
                    url = TmdbImages.backdropUrl(details.backdropPath, 780)
                        ?: TmdbImages.posterUrl(details.posterPath, 780),
                    contentDescription = details.title,
                ) {
                    PosterPlaceholder(details.title, Modifier.fillMaxSize())
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                            ),
                        ),
                )
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = details.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(details.catalogTypeLabel())
                        details.year?.let { append(" · ").append(it) }
                        details.releaseDate?.let { append(" · ").append(it) }
                        details.rating?.let { append(" · ★ ").append(Fmt.oneDecimal(it)) }
                        details.runtimeMinutes?.let { append(" · ").append(it).append(" min") }
                        details.seasons?.let { append(" · ").append(it).append(" season(s)") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (details.genres.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = details.genres.joinToString(", "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                details.network?.let { network ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = network,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { watchNow() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Watch now")
                    }
                    OutlinedButton(onClick = { downloadNow() }, modifier = Modifier.weight(1f)) {
                        Text("Download")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (vpnBlocked) {
                                error = VpnRequiredMessage.network
                            } else {
                                resolve { showReleases = true }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Magnet links") }
                    FilledTonalButton(
                        onClick = { onAddToLibrary(details) },
                        modifier = Modifier.weight(1f),
                        enabled = !inLibrary,
                    ) { Text(if (inLibrary) "In your library" else "Add to library") }
                }

                if (resolving) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Finding the best magnet for this title…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                error?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { onOpenBrowser(discover.browseUrl(details)) }) {
                        Text("Search in the built-in browser")
                    }
                }

                if (download.title == details.title &&
                    download.status != TorrentDownloadStatus.IDLE
                ) {
                    Spacer(Modifier.height(12.dp))
                    TorrentDownloadCard(
                        download = download,
                        onCancel = onCancelDownload,
                        onPlay = { path -> onPlayDownload(details, path) },
                    )
                }

                Spacer(Modifier.height(18.dp))
                Text("Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = details.overview.ifBlank { "TMDB has no description for this title yet." },
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(Modifier.height(18.dp))
                Text("Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                LabeledValue("Type", details.catalogTypeLabel())
                LabeledValue("Released", details.releaseDate ?: details.year?.toString() ?: "—")
                LabeledValue("Rating", details.rating?.let { "${Fmt.oneDecimal(it)} / 10" } ?: "—")
                LabeledValue("Genres", details.genres.joinToString(", ").ifBlank { "—" })
                LabeledValue("Runtime", details.runtimeMinutes?.let { "$it min" } ?: "—")
                if (details.mediaType == com.streambuddy.app.data.MediaType.TV) {
                    LabeledValue("Seasons", details.seasons?.toString() ?: "—")
                }
                LabeledValue("TMDB", "https://www.themoviedb.org/${if (details.mediaType == com.streambuddy.app.data.MediaType.MOVIE) "movie" else "tv"}/${details.tmdbId}")

                Spacer(Modifier.height(18.dp))
                DisclaimerCard(
                    "Descriptions and artwork come from TMDB. Watch now and Download look up a magnet on a public " +
                        "index only after you press the button. Stream Buddy does not verify that you have the right " +
                        "to stream a title; only use sources you are authorised to use.",
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showReleases) {
        val options = releases.orEmpty()
        AlertDialog(
            onDismissRequest = { showReleases = false },
            title = { Text("Magnet links · ${details.title}") },
            text = {
                if (options.isEmpty()) {
                    Text("No releases were returned. Try the browser search instead.")
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "Pick a release. Higher seeds usually start faster; 1080p is a good phone/TV balance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        options.forEach { release ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(
                                    release.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${release.provider} · ${release.detailLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = {
                                            showReleases = false
                                            onWatchNow(details, release)
                                        },
                                    ) { Text("Watch") }
                                    TextButton(
                                        onClick = {
                                            showReleases = false
                                            onDownload(details, release)
                                        },
                                    ) { Text("Download") }
                                    TextButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(release.magnet))
                                            copied = true
                                        },
                                    ) { Text("Copy") }
                                }
                                HorizontalDivider()
                            }
                        }
                        if (copied) {
                            Text(
                                "Magnet copied to the clipboard.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val best = options.firstOrNull()
                        if (best != null) {
                            clipboard.setText(AnnotatedString(best.magnet))
                            copied = true
                        }
                    },
                    enabled = options.isNotEmpty(),
                ) { Text("Copy best") }
            },
            dismissButton = {
                TextButton(onClick = { showReleases = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun TorrentDownloadCard(
    download: TorrentDownloadState,
    onCancel: () -> Unit,
    onPlay: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        when (download.status) {
            TorrentDownloadStatus.PREPARING -> Text(
                "Fetching torrent metadata…",
                style = MaterialTheme.typography.bodyMedium,
            )

            TorrentDownloadStatus.DOWNLOADING -> {
                Text(
                    download.fileName.ifBlank { "Downloading" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { download.progress.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(Fmt.percent(download.progress))
                        append(" · ").append(Fmt.bytes(download.downloadedBytes))
                        append(" / ").append(Fmt.bytes(download.totalBytes))
                        if (download.rateBps > 0) append(" · ").append(Fmt.rate(download.rateBps.toDouble()))
                        if (download.peers > 0) append(" · ").append(download.peers).append(" peers")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onCancel) { Text("Cancel download") }
            }

            TorrentDownloadStatus.COMPLETED -> {
                Text("Download complete", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                download.filePath?.let { path ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { onPlay(path) }) { Text("Play downloaded file") }
                }
            }

            TorrentDownloadStatus.FAILED -> {
                Text("Download failed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                download.error?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }

            TorrentDownloadStatus.CANCELLED -> Text(
                "Download cancelled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TorrentDownloadStatus.IDLE -> Unit
        }
    }
}
