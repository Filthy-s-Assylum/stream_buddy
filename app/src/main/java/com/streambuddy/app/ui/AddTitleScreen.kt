package com.streambuddy.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streambuddy.app.data.LibraryRepository
import com.streambuddy.app.data.MediaType
import com.streambuddy.app.data.MetadataCandidate
import com.streambuddy.app.metadata.MetadataRepository
import com.streambuddy.app.metadata.TmdbImages
import com.streambuddy.app.torrent.Magnet
import com.streambuddy.app.torrent.TorrentManager
import kotlinx.coroutines.launch

/**
 * Adds a title via:
 *  - TMDB search/trending (user-supplied API key);
 *  - a custom title (optional magnet/link/file right away);
 *  - importing an existing .torrent file into private metadata storage;
 *  - building a .torrent file from a local user-owned video.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTitleScreen(
    metadata: MetadataRepository,
    library: LibraryRepository,
    torrentManager: TorrentManager,
    onAdded: (Long) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MetadataCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var customOpen by remember { mutableStateOf(false) }
    var pendingCreateUri by remember { mutableStateOf<Uri?>(null) }
    val hasKey = metadata.hasKey

    val torrentImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busyMessage = "Importing .torrent…"
        error = null
        scope.launch {
            torrentManager.importTorrentUri(uri)
                .onSuccess { imported ->
                    runCatching {
                        library.addCustom(
                            title = imported.name.removeSuffix(".torrent").ifBlank { "Imported torrent" },
                            sourceUri = "torrent-file://${imported.file.absolutePath}",
                            sourceLabel = "Torrent file · ${imported.name}",
                            infoHash = imported.infoHash,
                            mediaType = MediaType.MOVIE,
                        )
                    }.onSuccess { id ->
                        busyMessage = null
                        onAdded(id)
                    }.onFailure { err ->
                        busyMessage = null
                        error = err.message ?: "Could not add imported torrent."
                    }
                }
                .onFailure { err ->
                    busyMessage = null
                    error = err.message ?: "That .torrent file could not be imported."
                }
        }
    }

    val videoForTorrentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingCreateUri = uri
        }
    }

    fun search() {
        if (query.isBlank()) return
        loading = true
        error = null
        scope.launch {
            val outcome = metadata.search(query)
            loading = false
            outcome.onSuccess { results = it }
                .onFailure { error = it.message ?: "Search failed" }
        }
    }

    LaunchedEffect(hasKey) {
        if (hasKey && results.isEmpty()) {
            loading = true
            metadata.trending()
                .onSuccess { if (results.isEmpty()) results = it }
                .onFailure { /* Trending is a convenience; search still works. */ }
            loading = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Add a title", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { TextButton(onClick = { customOpen = true }) { Text("Custom") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(120) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search TMDB") },
                trailingIcon = {
                    TextButton(onClick = { search() }, enabled = !loading && query.isNotBlank()) {
                        Text("Go")
                    }
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { torrentImporter.launch(arrayOf("application/x-bittorrent", "*/*")) },
                    enabled = busyMessage == null,
                ) { Text("Import .torrent") }
                OutlinedButton(
                    onClick = { videoForTorrentPicker.launch(arrayOf("video/*")) },
                    enabled = busyMessage == null,
                ) { Text("Create .torrent") }
                OutlinedButton(
                    onClick = { customOpen = true },
                    enabled = busyMessage == null,
                ) { Text("Custom title") }
            }

            busyMessage?.let { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (!hasKey) {
                DisclaimerCard(
                    "Add your own free TMDB API key in Settings to search posters and descriptions. " +
                        "You can still add a custom title, import a .torrent, or attach an authorised source without one.",
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = onOpenSettings, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Open Settings")
                }
            }

            error?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results, key = { "${it.mediaType}-${it.tmdbId}" }) { candidate ->
                    CandidateRow(
                        candidate = candidate,
                        onAdd = {
                            scope.launch {
                                runCatching { library.add(candidate) }
                                    .onSuccess(onAdded)
                                    .onFailure { error = "Could not add this title to your library." }
                            }
                        },
                    )
                }
                if (!loading && results.isEmpty() && hasKey) {
                    item {
                        Text(
                            "Search for a movie or show, or use “Custom”, “Import .torrent”, or “Create .torrent”.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (customOpen) {
        CustomTitleDialog(
            onDismiss = { customOpen = false },
            onAdd = { title, overview, mediaType, sourceUri ->
                scope.launch {
                    val cleanUri = sourceUri.trim().takeIf { it.isNotEmpty() }
                    val infoHash = cleanUri?.let {
                        runCatching { Magnet.parse(it).getOrThrow().infoHashHex }.getOrNull()
                    }
                    runCatching {
                        library.addCustom(
                            title = title,
                            sourceUri = cleanUri,
                            sourceLabel = cleanUri?.let {
                                if (Magnet.isMagnet(it)) "Magnet · $title" else "Link / File · $title"
                            },
                            infoHash = infoHash,
                            mediaType = mediaType,
                            overview = overview,
                        )
                    }.onSuccess {
                        customOpen = false
                        onAdded(it)
                    }.onFailure {
                        error = "Could not add this title."
                    }
                }
            },
        )
    }

    pendingCreateUri?.let { uri ->
        val suggestedName = remember(uri) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Personal video"
        }
        CreateTorrentDialog(
            suggestedTitle = suggestedName.substringBeforeLast('.').ifBlank { "Personal video" },
            onDismiss = { pendingCreateUri = null },
            onCreate = { title, comment, trackers, isPrivate ->
                pendingCreateUri = null
                busyMessage = "Hashing video and building .torrent…"
                error = null
                scope.launch {
                    torrentManager.createTorrentFromUri(
                        uri = uri,
                        label = title,
                        comment = comment.ifBlank { null },
                        trackers = trackers.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
                        isPrivate = isPrivate,
                    ).onSuccess { created ->
                        runCatching {
                            library.addCustom(
                                title = title,
                                sourceUri = "torrent-file://${created.file.absolutePath}",
                                sourceLabel = "Created .torrent · ${created.torrentName}",
                                infoHash = created.infoHash,
                                mediaType = MediaType.MOVIE,
                            )
                        }.onSuccess { id ->
                            busyMessage = null
                            onAdded(id)
                        }.onFailure { err ->
                            busyMessage = null
                            error = err.message ?: "Created .torrent, but failed to add title."
                        }
                    }.onFailure { err ->
                        busyMessage = null
                        error = err.message ?: "Could not create .torrent."
                    }
                }
            },
        )
    }
}

@Composable
private fun CandidateRow(candidate: MetadataCandidate, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAdd)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(width = 62.dp, height = 92.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            RemoteImage(
                url = TmdbImages.posterUrl(candidate.posterPath, 185),
                contentDescription = candidate.title,
            ) {
                PosterPlaceholder(candidate.title, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = candidate.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    candidate.year?.toString(),
                    candidate.mediaType.label,
                    candidate.rating?.let { "★ ${"%.1f".format(it)}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (candidate.overview.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = candidate.overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = false, onClick = onAdd, label = { Text("Add") })
    }
}

@Composable
private fun CustomTitleDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, MediaType, String) -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var overview by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var sourceError by remember { mutableStateOf<String?>(null) }
    var mediaType by remember { mutableStateOf(MediaType.MOVIE) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            source = uri.toString()
            sourceError = null
            if (title.isBlank()) {
                val name = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
                if (!name.isNullOrBlank()) {
                    title = name.substringBeforeLast('.').take(300)
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a custom title") },
        text = {
            Column {
                Text(
                    "Use this for a personal recording or a title TMDB does not list. Optionally paste a magnet, " +
                        "https stream URL, or pick a local file now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(300) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Title") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = overview,
                    onValueChange = { overview = it.take(4_000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Description (optional)") },
                    minLines = 2,
                    maxLines = 3,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = source,
                    onValueChange = {
                        source = it.take(4_000)
                        sourceError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Magnet, link, or file URI (optional)") },
                    singleLine = true,
                    isError = sourceError != null,
                )
                sourceError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(10.dp))
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
                onClick = {
                    val trimmedSource = source.trim()
                    if (trimmedSource.isNotEmpty()) {
                        val err = validateSource(trimmedSource)
                        if (err != null) {
                            sourceError = err
                            return@TextButton
                        }
                    }
                    onAdd(title, overview, mediaType, trimmedSource)
                },
                enabled = title.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { filePicker.launch(arrayOf("video/*", "audio/*")) }) {
                    Text("Pick file")
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun CreateTorrentDialog(
    suggestedTitle: String,
    onDismiss: () -> Unit,
    onCreate: (title: String, comment: String, trackers: String, isPrivate: Boolean) -> Unit,
) {
    var title by remember(suggestedTitle) { mutableStateOf(suggestedTitle.take(300)) }
    var comment by remember { mutableStateOf("") }
    var trackers by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create .torrent from video") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Builds a .torrent in private app storage from a video you own. Only add trackers you are " +
                        "authorised to publish to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(300) },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(200) },
                    label = { Text("Comment (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = trackers,
                    onValueChange = { trackers = it.take(2_000) },
                    label = { Text("Tracker URLs (optional, one per line)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Private torrent (disable DHT)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title.trim().ifBlank { "Personal video" }, comment.trim(), trackers, isPrivate) },
                enabled = title.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
