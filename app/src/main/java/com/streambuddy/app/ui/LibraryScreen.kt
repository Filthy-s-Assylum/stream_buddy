package com.streambuddy.app.ui

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streambuddy.app.data.LibraryFilters
import com.streambuddy.app.data.LibraryItem
import com.streambuddy.app.data.LibraryQuery
import com.streambuddy.app.data.LibrarySort
import com.streambuddy.app.data.LibraryStatus
import com.streambuddy.app.data.MediaType
import com.streambuddy.app.metadata.TmdbImages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    items: List<LibraryItem>,
    onOpen: (LibraryItem) -> Unit,
    onPlay: (LibraryItem) -> Unit,
    onAdd: () -> Unit,
    vpnWarning: String? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
) {
    var query by remember { mutableStateOf(LibraryQuery()) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    val filtered = remember(items, query) { LibraryFilters.apply(items, query) }
    val continueWatching = remember(items) { LibraryFilters.continueWatching(items) }
    val genres = remember(items) { LibraryFilters.genres(items) }
    val networks = remember(items) { LibraryFilters.networks(items) }
    val showingEverything = query.text.isBlank() && query.mediaType == null &&
        query.genre == null && query.network == null && query.status == null

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Stream Buddy", fontWeight = FontWeight.Bold)
                        Text(
                            "Your library · your sources",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    Box {
                        TextButton(onClick = { sortMenuOpen = true }) {
                            Text(sortLabel(query.sort))
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            LibrarySort.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text(sortLabel(sort)) },
                                    onClick = {
                                        query = query.copy(sort = sort)
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Add a title")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(contentPadding),
        ) {
            if (vpnWarning != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = vpnWarning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            OutlinedTextField(
                value = query.text,
                onValueChange = { query = query.copy(text = it.take(120)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search your library") },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = query.mediaType == null,
                    onClick = { query = query.copy(mediaType = null) },
                    label = { Text("All") },
                )
                MediaType.entries.forEach { type ->
                    FilterChip(
                        selected = query.mediaType == type,
                        onClick = { query = query.copy(mediaType = if (query.mediaType == type) null else type) },
                        label = { Text(if (type == MediaType.MOVIE) "Movies" else "TV") },
                    )
                }
                LibraryStatus.entries.forEach { status ->
                    FilterChip(
                        selected = query.status == status,
                        onClick = { query = query.copy(status = if (query.status == status) null else status) },
                        label = { Text(status.label) },
                    )
                }
            }

            if (genres.isNotEmpty() || networks.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    genres.forEach { genre ->
                        FilterChip(
                            selected = query.genre == genre,
                            onClick = { query = query.copy(genre = if (query.genre == genre) null else genre) },
                            label = { Text(genre) },
                        )
                    }
                    networks.forEach { network ->
                        AssistChip(
                            onClick = { query = query.copy(network = if (query.network == network) null else network) },
                            label = { Text(network, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }

            if (items.isEmpty()) {
                EmptyState(
                    title = "Your library is empty",
                    body = "Add a movie or show from TMDB, then attach your own authorised magnet, download link or local file. " +
                        "Stream Buddy never searches for or supplies copyrighted content.",
                    actionLabel = "Add a title",
                    onAction = onAdd,
                )
            } else if (filtered.isEmpty()) {
                EmptyState(
                    title = "Nothing matches",
                    body = "Try clearing the search text or filters.",
                    actionLabel = "Clear filters",
                    onAction = { query = LibraryQuery() },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(132.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 96.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (showingEverything && continueWatching.isNotEmpty()) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Column {
                                SectionTitle("Continue watching")
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                                ) {
                                    items(continueWatching, key = { "cw-${it.id}" }) { item ->
                                        ContinueWatchingCard(item, onClick = { onOpen(item) })
                                    }
                                }
                                Spacer(Modifier.height(5.dp))
                            }
                        }
                    }
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (showingEverything) "All titles" else "Results",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${filtered.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(filtered, key = { it.id }) { item ->
                        PosterCard(item = item, onClick = { onOpen(item) }, onPlay = { onPlay(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(item: LibraryItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(148.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            RemoteImage(
                url = TmdbImages.backdropUrl(item.backdropPath, 300)
                    ?: TmdbImages.posterUrl(item.posterPath, 342),
                contentDescription = item.title,
            ) {
                PosterPlaceholder(item.title, Modifier.fillMaxSize())
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xCC000000)),
                        ),
                    ),
            )
            Text(
                text = "${(item.progressFraction * item.durationMs / 60_000).toInt()} min watched",
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ProgressLine(item.progressFraction, Modifier.padding(top = 3.dp))
    }
}

@Composable
fun PosterCard(item: LibraryItem, onClick: () -> Unit, onPlay: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            RemoteImage(
                url = TmdbImages.posterUrl(item.posterPath, 342),
                contentDescription = item.title,
            ) {
                PosterPlaceholder(item.title, Modifier.fillMaxSize())
            }
            if (!item.hasSource) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC0D1117))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Text("No source", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFC857))
                }
            }
            if (item.status == LibraryStatus.WATCHLIST) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC0D1117))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Text("Watchlist", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (item.progressFraction > 0.01) {
                ProgressLine(
                    item.progressFraction,
                    Modifier.align(Alignment.BottomCenter),
                )
            }
            if (onPlay != null && item.hasSource) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play ${item.title}",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.displayYearAndType,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PosterPlaceholder(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(Color(0xFF1B2735), Color(0xFF090A0F)),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.take(2).uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            color = Color(0xFF4CC9F0),
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun sortLabel(sort: LibrarySort): String = when (sort) {
    LibrarySort.RECENTLY_ADDED -> "Recently added"
    LibrarySort.RECENTLY_WATCHED -> "Recently watched"
    LibrarySort.TITLE -> "Title A–Z"
    LibrarySort.YEAR -> "Year"
    LibrarySort.RATING -> "Rating"
}
