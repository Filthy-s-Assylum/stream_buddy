package com.streambuddy.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streambuddy.app.core.Fmt
import com.streambuddy.app.data.LibraryItem
import com.streambuddy.app.data.LibraryStatus
import com.streambuddy.app.metadata.MotnRepository
import com.streambuddy.app.metadata.TmdbImages
import com.streambuddy.app.metadata.WatchOption
import com.streambuddy.app.net.NetworkGuard
import com.streambuddy.app.net.VpnRequiredMessage
import com.streambuddy.app.playback.PlayerUiState
import com.streambuddy.app.torrent.Magnet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    item: LibraryItem?,
    playerState: PlayerUiState,
    motn: MotnRepository,
    vpnBlocked: Boolean,
    onBack: () -> Unit,
    onPlay: (LibraryItem, Long?) -> Unit,
    onAttachSource: (LibraryItem, String, String?) -> Unit,
    onClearSource: (LibraryItem) -> Unit,
    onSetWatchlist: (LibraryItem, Boolean) -> Unit,
    onSetWatched: (LibraryItem, Boolean) -> Unit,
    onDelete: (LibraryItem) -> Unit,
    onDownload: (LibraryItem) -> Unit,
    onOpenPlayer: () -> Unit,
    onWatchInBrowser: (String) -> Unit,
) {
    if (item == null) {
        EmptyState("Title not found", "It may have been deleted.", "Back", onBack)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attachOpen by remember(item.id) { mutableStateOf(false) }
    var sourceText by remember(item.id) { mutableStateOf("") }
    var sourceLabel by remember(item.id) { mutableStateOf("") }
    var attachError by remember(item.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(item.id) { mutableStateOf(false) }
    var watchOptions by remember(item.id) { mutableStateOf<List<WatchOption>?>(null) }
    var watchLoading by remember(item.id) { mutableStateOf(false) }
    var watchError by remember(item.id) { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onAttachSource(item, uri.toString(), uri.lastPathSegment?.substringAfterLast('/'))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete title")
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
                    .height(210.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                RemoteImage(
                    url = TmdbImages.backdropUrl(item.backdropPath, 780)
                        ?: TmdbImages.posterUrl(item.posterPath, 780),
                    contentDescription = item.title,
                ) {
                    PosterPlaceholder(item.title, Modifier.fillMaxSize())
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
                    text = item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(item.displayYearAndType)
                        item.rating?.let { append(" · ★ ").append(Fmt.oneDecimal(it)) }
                        item.runtimeMinutes?.let { append(" · ").append(it).append(" min") }
                        item.seasons?.let { append(" · ").append(it).append(" season(s)") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.genres.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = item.genreLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (item.hasSource) onPlay(item, null) else attachOpen = true
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            if (item.hasSource) {
                                if (item.progressMs > 5_000) "Resume" else "Play"
                            } else {
                                "Attach source"
                            },
                        )
                    }
                    if (item.hasSource && item.progressMs > 5_000) {
                        OutlinedButton(onClick = { onPlay(item, 0L) }) { Text("Start over") }
                    }
                }
                if (item.progressMs > 0 && item.durationMs > 0) {
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(item.progressFraction)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "${Fmt.duration(item.progressMs / 1000.0)} of ${Fmt.duration(item.durationMs / 1000.0)} watched",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val inWatchlist = item.status == LibraryStatus.WATCHLIST
                    FilledTonalButton(
                        onClick = {
                            onSetWatchlist(item, !inWatchlist)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (inWatchlist) "In watchlist" else "Add to watchlist")
                    }
                    FilledTonalButton(
                        onClick = {
                            onSetWatched(item, item.status != LibraryStatus.WATCHED)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (item.status == LibraryStatus.WATCHED) "Watched" else "Mark watched")
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.overview.ifBlank { "No description available. Add a TMDB API key in Settings to load metadata." },
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(Modifier.height(18.dp))
                Text("Where to watch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                when {
                    vpnBlocked -> Text(
                        VpnRequiredMessage.network,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    !motn.hasKey -> Text(
                        "Add a Movie of the Night API key in Settings to see official streaming services, prices " +
                            "and deep links for ${motn.country.uppercase()}. Stream Buddy never generates or scrapes " +
                            "streams here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> {
                        OutlinedButton(
                            onClick = {
                                if (NetworkGuard.isBlocked()) {
                                    watchError = VpnRequiredMessage.network
                                    return@OutlinedButton
                                }
                                watchLoading = true
                                watchError = null
                                scope.launch {
                                    val result = motn.whereToWatch(item.title, item.mediaType)
                                    watchLoading = false
                                    result.fold(
                                        onSuccess = { watchOptions = it },
                                        onFailure = { watchError = it.message ?: "Availability is unavailable." },
                                    )
                                }
                            },
                            enabled = !watchLoading,
                        ) {
                            Text(
                                if (watchOptions == null) {
                                    "Find streaming options"
                                } else {
                                    "Refresh streaming options"
                                },
                            )
                        }
                        if (watchLoading) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        watchError?.let { message ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        watchOptions?.let { options ->
                            Spacer(Modifier.height(8.dp))
                            if (options.isEmpty()) {
                                Text(
                                    "No official streaming options were returned for ${motn.country.uppercase()}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                options.forEach { option ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                option.serviceName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Text(
                                                buildString {
                                                    append(option.typeLabel)
                                                    option.quality?.let { append(" · ").append(it.uppercase()) }
                                                    option.price?.let { append(" · ").append(it) }
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        option.link?.let { link ->
                                            TextButton(onClick = { onWatchInBrowser(link) }) {
                                                Text("Open")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text("Source", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                if (item.hasSource) {
                    Text(
                        text = item.sourceLabel ?: item.sourceUri.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.infoHash != null) {
                        Text(
                            text = "Info hash ${item.infoHash}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { attachOpen = true }) { Text("Change source") }
                        TextButton(onClick = { onClearSource(item) }) { Text("Remove source") }
                        if (!Magnet.isMagnet(item.sourceUri.orEmpty())) {
                            TextButton(onClick = { onDownload(item) }) { Text("Download first") }
                        }
                    }
                } else {
                    Text(
                        text = "No playable source attached. Add a magnet you are authorised to use, a direct https " +
                            "media link you control, or a local video from this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { attachOpen = true }) { Text("Attach magnet or link") }
                        OutlinedButton(onClick = { filePicker.launch(arrayOf("video/*", "audio/*")) }) {
                            Text("Pick a file")
                        }
                    }
                }

                if (playerState.title.isNotBlank() && playerState.itemId == item.id) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onOpenPlayer, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (playerState.error != null) {
                                "Playback problem · open player"
                            } else {
                                "Now playing · open player"
                            },
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                DisclaimerCard(
                    "Stream Buddy does not search for, supply or unlock copyrighted content, DRM or premium links. " +
                        "The source you attach must be one you own or are authorised to stream. Torrent streaming uses " +
                        "encrypted peer connections and keeps data in the app's private storage only while you watch.",
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (attachOpen) {
        AlertDialog(
            onDismissRequest = { attachOpen = false },
            title = { Text("Attach your source") },
            text = {
                Column {
                    Text(
                        "Paste a magnet (magnet:?xt=urn:btih:…) or a direct https media link / .m3u8 / .mpd you are " +
                            "allowed to use. Stream Buddy stores it encrypted on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = sourceText,
                        onValueChange = {
                            sourceText = it.take(4_000)
                            attachError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Magnet or https link") },
                        minLines = 2,
                        maxLines = 5,
                        isError = attachError != null,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sourceLabel,
                        onValueChange = { sourceLabel = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Label (optional)") },
                        singleLine = true,
                    )
                    attachError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = sourceText.trim()
                        val validation = validateSource(value)
                        if (validation != null) {
                            attachError = validation
                        } else {
                            onAttachSource(item, value, sourceLabel.trim().ifBlank { null })
                            attachOpen = false
                            sourceText = ""
                            sourceLabel = ""
                        }
                    },
                ) { Text("Attach") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { filePicker.launch(arrayOf("video/*", "audio/*")) }) {
                        Text("Pick file")
                    }
                    TextButton(onClick = { attachOpen = false }) { Text("Cancel") }
                }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${item.title}?") },
            text = { Text("This removes the title and its encrypted source from this device. Cached media is not kept.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete(item)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

/** Returns a friendly validation error, or null when the source is acceptable. */
fun validateSource(value: String): String? {
    if (value.isBlank()) return "Paste a magnet link or a media URL"
    if (Magnet.isMagnet(value)) {
        return Magnet.parse(value).exceptionOrNull()?.let {
            "That magnet is not valid. It needs a 40-character btih info hash."
        }
    }
    val uri = runCatching { java.net.URI(value) }.getOrNull()
        ?: return "That does not look like a URL"
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") {
        return "Only magnet links, http(s) media links and local files are supported"
    }
    if (uri.host.isNullOrBlank()) return "The link has no host"
    // Accept these file-hosting and video platforms:
    val host = uri.host.lowercase()
    return when {
        host == "streamtape.com" || host == "dood.co" || host == "dood.to" || host == "uptobox.com" || host == "uptobox.net" -> null
        host endsWith(".piratebay") || host == "1337x" || host.endsWith("rarbg") || host == "yts" || host == "eztv" -> null
        else -> null
    }
}
