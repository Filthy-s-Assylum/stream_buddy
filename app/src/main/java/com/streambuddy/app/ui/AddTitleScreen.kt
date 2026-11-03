package com.streambuddy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streambuddy.app.data.LibraryRepository
import com.streambuddy.app.data.MediaType
import com.streambuddy.app.data.MetadataCandidate
import com.streambuddy.app.metadata.MetadataRepository
import com.streambuddy.app.metadata.TmdbImages
import kotlinx.coroutines.launch

/**
 * Adds a title. Two honest paths:
 *
 *  - search TMDB for metadata (the user supplies the API key);
 *  - add a custom entry for a personal recording, then attach an authorised
 *    source on the detail screen.
 *
 * Stream Buddy does not search for torrents or copyrighted streams here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTitleScreen(
    metadata: MetadataRepository,
    library: LibraryRepository,
    onAdded: (Long) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MetadataCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var customOpen by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(metadata.hasKey) }

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

    LaunchedEffect(Unit) {
        if (hasKey) {
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

            if (!hasKey) {
                DisclaimerCard(
                    "Add your own free TMDB API key in Settings to search posters and descriptions. " +
                        "You can still add a custom title and attach an authorised source without one.",
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
                            "Search for a movie or show, or use “Custom” to add something TMDB does not list.",
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
            onAdd = { title, overview, mediaType ->
                scope.launch {
                    runCatching {
                        library.addCustom(
                            title = title,
                            sourceUri = null,
                            sourceLabel = null,
                            infoHash = null,
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
    onAdd: (String, String, MediaType) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var overview by remember { mutableStateOf("") }
    var mediaType by remember { mutableStateOf(MediaType.MOVIE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a custom title") },
        text = {
            Column {
                Text(
                    "Use this for a personal recording or a title TMDB does not list. You attach the source " +
                        "on the next screen.",
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
                    maxLines = 4,
                )
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
                onClick = { onAdd(title, overview, mediaType) },
                enabled = title.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
