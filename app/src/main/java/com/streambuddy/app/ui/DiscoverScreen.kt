package com.streambuddy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streambuddy.app.core.Fmt
import com.streambuddy.app.data.MetadataCandidate
import com.streambuddy.app.metadata.DiscoverRepository
import com.streambuddy.app.metadata.TmdbImages
import com.streambuddy.app.metadata.catalogTypeLabel
import kotlinx.coroutines.launch

/**
 * "Latest" catalogue backed by TMDB: what is in cinemas, what is airing this
 * week, what is coming soon, and what is trending. Metadata is free to browse;
 * a magnet is only looked up when the user opens a title and asks for one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    discover: DiscoverRepository,
    vpnBlocked: Boolean,
    onOpen: (MetadataCandidate) -> Unit,
    onOpenSettings: () -> Unit,
    onShowLibrary: (() -> Unit)? = null,
) {
    val feed by discover.feed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh(force: Boolean) {
        if (loading || !discover.hasKey) return
        loading = true
        error = null
        scope.launch {
            discover.refresh(force)
                .onFailure { error = it.message ?: "TMDB listings are unavailable." }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (discover.hasKey) refresh(force = false)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Latest", fontWeight = FontWeight.Bold)
                        Text(
                            "TMDB · in cinemas, on TV and coming soon",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refresh(force = true) }, enabled = !loading && discover.hasKey) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh listings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (onShowLibrary != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = false,
                        onClick = onShowLibrary,
                        label = { Text("My Library") },
                    )
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("Latest · TMDB") },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !discover.hasKey -> EmptyState(
                        title = "Add a TMDB key",
                        detail = "The Latest catalogue uses your own free TMDB API key. Add it once in Settings " +
                            "and Stream Buddy will remember it on this device.",
                        actionLabel = "Open Settings",
                        onAction = onOpenSettings,
                    )

                    loading && feed == null -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )

                    error != null && feed == null -> Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Could not load TMDB listings", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { refresh(force = true) }) { Text("Try again") }
                    }

                    feed == null || feed?.isEmpty == true -> EmptyState(
                        title = "No listings yet",
                        detail = "TMDB returned no rows. Pull refresh or check the key in Settings.",
                        actionLabel = "Refresh",
                        onAction = { refresh(force = true) },
                    )

                    else -> feed?.let { current ->
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                        ) {
                            if (vpnBlocked) {
                                item {
                                    Text(
                                        "Require VPN is on. Browsing TMDB is blocked until a VPN route is active.",
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            item {
                                Text(
                                    "Tap a title for details, Watch now, Download and Magnet options.",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            catalogSection("In cinemas now", "Newest movie releases", current.nowPlaying, onOpen)
                            catalogSection("Airing this week", "Series with a new episode", current.onTheAir, onOpen)
                            catalogSection("Coming soon", "Upcoming movie releases", current.upcoming, onOpen)
                            catalogSection("Trending this week", "What everyone is watching", current.trending, onOpen)
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.catalogSection(
    title: String,
    subtitle: String,
    entries: List<MetadataCandidate>,
    onOpen: (MetadataCandidate) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = "title-$title") {
        Column {
            SectionTitle(title)
            Text(
                subtitle,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item(key = "row-$title") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(entries, key = { "${it.mediaType}-${it.tmdbId}" }) { item ->
                CatalogCard(item = item, onClick = { onOpen(item) })
            }
        }
    }
}

@Composable
private fun CatalogCard(item: MetadataCandidate, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(156.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            RemoteImage(
                url = TmdbImages.posterUrl(item.posterPath, 342),
                contentDescription = item.title,
            ) {
                PosterPlaceholder(item.title, Modifier.fillMaxSize())
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC0D1117))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    item.catalogTypeLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item.rating?.let { rating ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC0D1117))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Text(
                        "★ ${Fmt.oneDecimal(rating)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFC857),
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
            text = listOfNotNull(
                item.year?.toString(),
                item.releaseDate?.takeIf { it.length >= 10 }?.substring(5),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.overview.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = item.overview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
