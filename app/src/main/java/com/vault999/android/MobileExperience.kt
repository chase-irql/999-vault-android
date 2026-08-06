package com.vault999.android

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vault999.android.account.AccountUiState
import com.vault999.android.auth.AccountProjection
import com.vault999.android.account.CloudLibraryUiState
import com.vault999.android.designsystem.VaultColors
import com.vault999.android.listen.ListenMode
import com.vault999.android.listen.ListenUiState
import com.vault999.android.listen.RadioUiState
import com.vault999.android.model.CanonicalSong
import com.vault999.android.model.PlaybackMode
import com.vault999.android.model.QueueItem
import com.vault999.android.model.RepeatMode
import com.vault999.android.model.SongCategory
import com.vault999.android.music.DownloadedItem
import com.vault999.android.music.LibraryUiState
import com.vault999.android.playback.PlaybackUiState
import com.vault999.android.search.SearchMode
import com.vault999.android.search.SearchResult
import com.vault999.android.search.SearchUiState

@Composable
internal fun MobileArchiveScreen(
    account: AccountUiState,
    songs: List<CanonicalSong>,
    loading: Boolean,
    offline: Boolean,
    error: String?,
    healthStatus: String?,
    totalSongs: Long?,
    categoryCounts: Map<String, Long>,
    randomSong: CanonicalSong?,
    discoveryLoading: Boolean,
    onRetry: () -> Unit,
    onAnother: () -> Unit,
    onPlay: (CanonicalSong) -> Unit,
    favoriteSongIds: Set<Long>,
    onFavorite: (Long) -> Unit,
    onFullCollection: () -> Unit,
    onAccount: () -> Unit,
    onNested: (String) -> Unit,
) {
    var category by remember { mutableStateOf<SongCategory?>(null) }
    var confirmVaultDownload by remember { mutableStateOf(false) }
    val visible = remember(songs, category) { songs.filter { category == null || it.category == category } }
    if (confirmVaultDownload) {
        AlertDialog(
            onDismissRequest = { confirmVaultDownload = false },
            title = { Text("Download the vault?") },
            text = { Text("This is a large download.") },
            confirmButton = { Button(onClick = { confirmVaultDownload = false; onFullCollection() }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirmVaultDownload = false }) { Text("Cancel") } },
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().background(VaultColors.Canvas).windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(bottom = 18.dp),
    ) {
        item {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    VaultAccountWordmark(account, onAccount, Modifier.weight(1f))
                    IconButton(onClick = { onNested("downloads") }) { Icon(Icons.Rounded.Download, "Downloads") }
                    IconButton(onClick = { onNested("settings") }) { Icon(Icons.Rounded.Settings, "Settings") }
                }
                Text("The Vault", style = MaterialTheme.typography.displaySmall)
                randomSong?.let { song ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(Brush.horizontalGradient(listOf(VaultColors.BlueBlack, VaultColors.Surface)))
                            .clickable(enabled = song.isPlayable) { onPlay(song) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VaultArtwork(song.artworkUrl, song.title, Modifier.size(112.dp))
                        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("RANDOM FIND", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
                            Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                            Text(song.artist, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilledIconButton(
                                    onClick = { onPlay(song) },
                                    enabled = song.isPlayable,
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = VaultColors.Yellow, contentColor = VaultColors.Canvas),
                                ) { Icon(Icons.Rounded.PlayArrow, "Play ${song.title}") }
                                IconButton(onClick = onAnother, enabled = !discoveryLoading) {
                                    if (discoveryLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, "Another random song")
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VaultShortcut("Browse files", Icons.Rounded.Folder, Modifier.weight(1f)) { onNested("files") }
                    VaultShortcut("999 FM", Icons.Rounded.Radio, Modifier.weight(1f)) { onNested("listen") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VaultShortcut("Wrapped", Icons.Rounded.BarChart, Modifier.weight(1f)) { onNested("wrapped") }
                    VaultShortcut("Vault ZIP", Icons.Rounded.Download, Modifier.weight(1f)) { confirmVaultDownload = true }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("CATALOG", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
                        Text("${totalSongs ?: songs.size} songs", style = MaterialTheme.typography.headlineMedium)
                    }
                    if (offline) Text("OFFLINE", color = VaultColors.Yellow, style = MaterialTheme.typography.labelLarge)
                    else healthStatus?.let { Text(it.uppercase(), color = VaultColors.Green, style = MaterialTheme.typography.labelLarge) }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(category == null, { category = null }, label = { Text("All") }) }
                    items(listOf(SongCategory.RELEASED, SongCategory.UNRELEASED, SongCategory.UNSURFACED, SongCategory.SESSION)) { value ->
                        FilterChip(category == value, { category = value }, label = { Text(if (value == SongCategory.SESSION) "Sessions" else value.name.lowercase().replaceFirstChar(Char::uppercase)) })
                    }
                }
                if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = VaultColors.Cyan, strokeWidth = 2.dp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable(onClick = onRetry)) }
            }
        }
        if (visible.isEmpty() && !loading) {
            item { CompactEmpty(Icons.Rounded.MusicNote, "Nothing here") }
        } else {
            items(visible, key = { it.id }) { song ->
                MobileSongRow(song, song.id in favoriteSongIds, { onFavorite(song.id) }) { onPlay(song) }
            }
        }
    }
}

@Composable
private fun VaultAccountWordmark(account: AccountUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val signedIn = account.projection as? AccountProjection.SignedIn
    val avatarUrl = signedIn?.account?.avatarUrl
    val label = signedIn?.account?.displayName ?: "999 Vault"
    Row(
        modifier.clickable(onClickLabel = "Open account settings", onClick = onClick)
            .semantics { contentDescription = if (signedIn == null) "999 Vault, signed out" else "Signed in as $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = if (signedIn == null) RoundedCornerShape(8.dp) else CircleShape
        Box(Modifier.size(40.dp).clip(shape).background(VaultColors.Chrome), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (!avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            if (account.working) {
                CircularProgressIndicator(Modifier.size(22.dp), color = VaultColors.Yellow, strokeWidth = 2.dp)
            }
        }
        Text("999 VAULT", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun VaultShortcut(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(VaultColors.SurfaceRaised).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = VaultColors.Cyan)
        Text(label, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MobileSongRow(song: CanonicalSong, favorite: Boolean = false, onFavorite: (() -> Unit)? = null, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Play ${song.title}", onClick = onPlay).padding(horizontal = 18.dp, vertical = 8.dp)
            .semantics { contentDescription = "${song.title}, ${song.artist}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VaultArtwork(song.artworkUrl, song.title, Modifier.size(52.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        onFavorite?.let { toggle ->
            IconButton(onClick = toggle) {
                Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (favorite) "Remove ${song.title} from favorites" else "Favorite ${song.title}", tint = if (favorite) VaultColors.Yellow else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun MobileListenScreen(
    state: ListenUiState,
    radio: RadioUiState,
    playing: Boolean,
    onToggle: () -> Unit,
    onStart: (ListenMode) -> Unit,
    onMode: (ListenMode) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onForward: () -> Unit,
    onRefreshRadio: () -> Unit,
    onPlayRadio: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().background(VaultColors.Canvas).windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text("Listen", style = MaterialTheme.typography.displaySmall)
            LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ListenMode.entries) { mode ->
                    FilterChip(state.mode == mode, { onMode(mode) }, label = { Text(mode.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)) })
                }
            }
        }
        item {
            val current = state.current
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(Brush.verticalGradient(listOf(VaultColors.BlueBlack, VaultColors.Surface))).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (current != null) {
                    VaultArtwork(current.artworkUri, current.title, Modifier.fillMaxWidth().aspectRatio(1f))
                } else {
                    Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).background(VaultColors.Ink), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Shuffle, null, tint = VaultColors.Cyan, modifier = Modifier.size(64.dp))
                    }
                }
                Text(current?.title ?: "Endless", style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current?.artist ?: state.mode.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase), color = VaultColors.Cyan, maxLines = 1)
                if (current == null) {
                    FilledIconButton(
                        onClick = { onStart(state.mode) },
                        enabled = state.catalogReady,
                        modifier = Modifier.size(64.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = VaultColors.Yellow, contentColor = VaultColors.Canvas),
                    ) { Icon(Icons.Rounded.PlayArrow, "Start endless listen", Modifier.size(34.dp)) }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack, enabled = state.canGoBack) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                        FilledIconButton(
                            onClick = onToggle,
                            modifier = Modifier.size(64.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = VaultColors.Yellow, contentColor = VaultColors.Canvas),
                        ) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(34.dp)) }
                        IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "Next") }
                    }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
        if (state.lookAhead.isNotEmpty()) {
            item { SectionTitle("Up next", "${state.lookAhead.size}") }
            items(state.lookAhead.take(5), key = { it.mediaId }) { QueueRow(it) }
        }
        item {
            SectionTitle("999 FM", radio.station?.listenerCount?.let { "$it live" }.orEmpty())
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(VaultColors.SurfaceRaised).clickable(enabled = radio.playableStation != null, onClick = onPlayRadio).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(VaultColors.Yellow), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Radio, null, tint = VaultColors.Canvas) }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(radio.station?.nowTitle ?: "999 FM", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (radio.station?.isLive == true) "LIVE" else "Radio", color = if (radio.station?.isLive == true) VaultColors.Red else VaultColors.Cyan)
                }
                IconButton(onClick = onRefreshRadio) { if (radio.refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, "Refresh radio") }
                IconButton(onClick = onPlayRadio, enabled = radio.playableStation != null) { Icon(Icons.Rounded.PlayArrow, "Play 999 FM") }
            }
        }
    }
}

private enum class LibraryShelf(val label: String) { PLAYLISTS("Playlists"), DOWNLOADS("Downloads") }
private data class LibraryDelete(val id: String, val name: String, val cloud: Boolean)

@Composable
internal fun MobileLibraryScreen(
    state: LibraryUiState,
    cloud: CloudLibraryUiState,
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onCreateCloudPlaylist: (String, String) -> Unit,
    onDeleteCloudPlaylist: (String) -> Unit,
    onRetryCloud: () -> Unit,
    onOpenDevicePlaylist: (String) -> Unit,
    onOpenCloudPlaylist: (String) -> Unit,
    onOpenLikedSongs: () -> Unit,
    onPlayDownloaded: (DownloadedItem) -> Unit,
    onNested: (String) -> Unit,
) {
    var shelf by remember { mutableStateOf(LibraryShelf.PLAYLISTS) }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<LibraryDelete?>(null) }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${target.name}?") },
            confirmButton = {
                Button(onClick = { if (target.cloud) onDeleteCloudPlaylist(target.id) else onDeletePlaylist(target.id); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().background(VaultColors.Canvas).windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Your Library", Modifier.weight(1f), style = MaterialTheme.typography.displaySmall)
                if (shelf == LibraryShelf.PLAYLISTS) IconButton(onClick = { adding = !adding }) { Icon(Icons.Rounded.Add, "New playlist") }
                IconButton(onClick = { onNested("settings") }) { Icon(Icons.Rounded.Settings, "Settings") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibraryShelf.entries) { value -> FilterChip(shelf == value, { shelf = value }, label = { Text(value.label) }) }
                item { FilterChip(false, onOpenLikedSongs, label = { Text("Liked") }, leadingIcon = { Icon(Icons.Rounded.Favorite, null) }) }
            }
            if (adding) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it.take(80) }, Modifier.weight(1f), placeholder = { Text("Playlist name") }, singleLine = true)
                    FilledIconButton(
                        onClick = { onCreatePlaylist(name); name = ""; adding = false },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Icon(Icons.Rounded.Add, "Create playlist") }
                }
            }
        }
        when (shelf) {
            LibraryShelf.PLAYLISTS -> {
                items(state.playlists, key = { it.id }) { playlist ->
                    LibraryRow(playlist.name, "On this device", Icons.Rounded.LibraryMusic, { onOpenDevicePlaylist(playlist.id) }) {
                        if (playlist.id.startsWith("device:")) IconButton(onClick = { pendingDelete = LibraryDelete(playlist.id, playlist.name, false) }) { Icon(Icons.Rounded.Delete, "Delete ${playlist.name}") }
                    }
                }
                if (cloud.projection.cloudVisible) {
                    items(cloud.projection.cloudPlaylists, key = { it.localId ?: it.id }) { playlist ->
                        LibraryRow(playlist.name, "${playlist.songIds.size} songs", Icons.Rounded.LibraryMusic, { onOpenCloudPlaylist(playlist.localId ?: playlist.id) }) {
                            IconButton(onClick = { pendingDelete = LibraryDelete(playlist.localId ?: playlist.id, playlist.name, true) }) { Icon(Icons.Rounded.Delete, "Delete ${playlist.name}") }
                        }
                    }
                }
                if (state.playlists.isEmpty() && cloud.projection.cloudPlaylists.isEmpty()) item { CompactEmpty(Icons.Rounded.LibraryMusic, "No playlists") }
            }
            LibraryShelf.DOWNLOADS -> {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.downloads.size} downloaded", Modifier.weight(1f), color = VaultColors.Cyan)
                        IconButton(onClick = { onNested("downloads") }) { Icon(Icons.Rounded.Download, "Manage downloads") }
                    }
                }
                items(state.downloads, key = { it.id }) { item ->
                    LibraryRow(item.name, item.locationLabel, Icons.Rounded.Download, { onPlayDownloaded(item) }) {
                        IconButton(onClick = { onPlayDownloaded(item) }, enabled = item.localUri != null) { Icon(Icons.Rounded.PlayArrow, "Play ${item.name}") }
                    }
                }
                if (state.downloads.isEmpty()) item { CompactEmpty(Icons.Rounded.Download, "No downloads") }
            }
        }
    }
}

@Composable
private fun LibraryRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(VaultColors.SurfaceRaised), contentAlignment = Alignment.Center) { Icon(icon, null, tint = VaultColors.Cyan) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

@Composable
internal fun MobileSearchScreen(state: SearchUiState, onQuery: (String) -> Unit, onMode: (SearchMode) -> Unit, onResult: (SearchResult) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(VaultColors.Canvas).windowInsetsPadding(WindowInsets.statusBars), contentPadding = PaddingValues(bottom = 18.dp)) {
        item {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Search", style = MaterialTheme.typography.displaySmall)
                OutlinedTextField(
                    state.query,
                    onQuery,
                    Modifier.fillMaxWidth(),
                    placeholder = { Text(if (state.mode == SearchMode.LYRICS) "Lyrics" else "What do you want to play?") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(state.mode == SearchMode.SONGS, { onMode(SearchMode.SONGS) }, label = { Text("Songs") })
                    FilterChip(state.mode == SearchMode.LYRICS, { onMode(SearchMode.LYRICS) }, label = { Text("Lyrics") })
                }
                if (state.loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = VaultColors.Cyan)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        items(state.results, key = { "${state.mode}:${it.song.id}" }) { result ->
            if (state.mode == SearchMode.LYRICS && !result.excerpt.isNullOrBlank()) {
                Row(Modifier.fillMaxWidth().clickable { onResult(result) }.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    VaultArtwork(result.song.artworkUrl, result.song.title, Modifier.size(52.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(result.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(result.excerpt, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                MobileSongRow(result.song) { onResult(result) }
            }
        }
        if (state.query.length >= 2 && !state.loading && state.results.isEmpty() && state.error == null) item { CompactEmpty(Icons.Rounded.Search, "No results") }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun MobileWrappedScreen(state: LibraryUiState, catalog: List<CanonicalSong>, onPlay: (CanonicalSong) -> Unit, onBrowse: () -> Unit, onBack: () -> Unit) {
    var period by remember { mutableStateOf(0) }
    val summary = when (period) { 1 -> state.thirtyDays; 2 -> state.sevenDays; else -> state.allTime }
    val songs = remember(catalog) { catalog.associateBy { it.id } }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Wrapped") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultColors.Chrome),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("YOUR LISTENING", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
                Text("Your time in the WRLD.", style = MaterialTheme.typography.displaySmall)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All time", "30 days", "7 days").forEachIndexed { index, label -> FilterChip(period == index, { period = index }, label = { Text(label) }) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard(summary.totalPlays.toString(), "PLAYS", Modifier.weight(1f))
                    StatCard(summary.distinctSongs.toString(), "SONGS", Modifier.weight(1f))
                    StatCard("${summary.listenedSeconds / 60}m", "TIME", Modifier.weight(1f))
                }
            }
            item { SectionTitle("Top songs", "") }
            items(summary.topSongs.take(10)) { top ->
                val song = songs[top.songId]
                Row(Modifier.fillMaxWidth().clickable(enabled = song?.isPlayable == true) { song?.let(onPlay) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    VaultArtwork(song?.artworkUrl, song?.title ?: "Song", Modifier.size(48.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(song?.title ?: "Saved song", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        song?.artist?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                    }
                    Text("${top.plays}×", color = VaultColors.Cyan)
                }
            }
            if (summary.topSongs.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.BarChart, null, tint = VaultColors.Cyan, modifier = Modifier.size(40.dp))
                    Text("No listening yet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
                    Button(onClick = onBrowse) { Text("Listen") }
                }
            }
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(VaultColors.SurfaceRaised).padding(12.dp)) {
        Text(value, color = VaultColors.Yellow, style = MaterialTheme.typography.headlineMedium)
        Text(label, color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun MobileMiniPlayer(item: QueueItem, playing: Boolean, positionMs: Long, durationMs: Long?, onToggle: () -> Unit, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp))
            .background(VaultColors.SurfaceRaised).clickable(onClickLabel = "Open Now Playing", onClick = onOpen)
            .semantics { contentDescription = "Mini player, ${item.title}, ${if (playing) "playing" else "paused"}" },
    ) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            VaultArtwork(item.artworkUri, item.title, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play") }
        }
        val progress = durationMs?.takeIf { it > 0 }?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
        Box(Modifier.fillMaxWidth().height(2.dp).background(VaultColors.Ink)) {
            Box(Modifier.fillMaxWidth(progress).height(2.dp).background(VaultColors.Yellow))
        }
    }
}

@Composable
internal fun MobileNowPlayingScreen(
    state: PlaybackUiState,
    favorite: Boolean,
    canDownload: Boolean,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: (Boolean) -> Unit,
    onRepeat: (RepeatMode) -> Unit,
    onRetry: () -> Unit,
    onFavorite: () -> Unit,
    onLyrics: () -> Unit,
    onDownload: () -> Unit,
    onOpenQueue: () -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(VaultColors.BlueBlack, VaultColors.Canvas, VaultColors.Chrome))).windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.KeyboardArrowDown, "Back") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PLAYING FROM ${playbackSource(state.playbackMode)}", style = MaterialTheme.typography.labelLarge, color = VaultColors.Cyan)
                    Text("999 Vault", style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = onOpenQueue, enabled = state.queue.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue") }
            }
        }
        item { VaultArtwork(state.currentItem?.artworkUri, state.title, Modifier.fillMaxWidth().aspectRatio(1f)) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.title.ifBlank { "Nothing playing" }, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(state.artist.ifBlank { "999 Vault" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = onFavorite, enabled = state.currentItem?.canonicalSongId != null) {
                    Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (favorite) "Unlike" else "Like", tint = if (favorite) VaultColors.Yellow else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        item {
            val duration = state.durationMs?.takeIf { it > 0 }
            if (duration != null) {
                Slider(
                    value = state.positionMs.coerceIn(0, duration).toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback position" },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatTime(state.positionMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(duration), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
            } else if (state.playbackMode == PlaybackMode.RADIO) {
                Text("LIVE", color = VaultColors.Red, style = MaterialTheme.typography.labelLarge)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onShuffle(!state.shuffle) }) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (state.shuffle) VaultColors.Yellow else MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = onPrevious) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(38.dp)) }
                FilledIconButton(
                    onClick = onToggle,
                    enabled = state.currentItem != null,
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = VaultColors.Canvas),
                ) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(38.dp)) }
                IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(38.dp)) }
                IconButton(onClick = { onRepeat(if (state.repeatMode == RepeatMode.OFF) RepeatMode.ALL else if (state.repeatMode == RepeatMode.ALL) RepeatMode.ONE else RepeatMode.OFF) }) {
                    Icon(if (state.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat ${state.repeatMode.name.lowercase()}", tint = if (state.repeatMode == RepeatMode.OFF) MaterialTheme.colorScheme.onSurface else VaultColors.Yellow)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                PlayerAction(Icons.Rounded.Download, "Download", onDownload, canDownload)
                PlayerAction(Icons.AutoMirrored.Rounded.Article, "Lyrics", onLyrics, state.currentItem?.canonicalSongId != null)
                PlayerAction(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", onOpenQueue, state.queue.isNotEmpty())
            }
        }
        state.error?.let {
            item { Button(onClick = onRetry) { Text("Retry") } }
        }
    }
}

@Composable
private fun PlayerAction(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
}

private fun playbackSource(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.CATALOG -> "THE ARCHIVE"
    PlaybackMode.LISTEN -> "ENDLESS"
    PlaybackMode.RADIO -> "999 FM"
    PlaybackMode.EXPLICIT_QUEUE -> "YOUR LIBRARY"
}

private fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun QueueRow(item: QueueItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        VaultArtwork(item.artworkUri, item.title, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(title: String, detail: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        if (detail.isNotBlank()) Text(detail, color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun CompactEmpty(icon: ImageVector, title: String) {
    Column(Modifier.fillMaxWidth().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = VaultColors.Cyan, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun VaultArtwork(url: String?, title: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(VaultColors.SurfaceRaised, VaultColors.BlueBlack))), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = VaultColors.Cyan.copy(alpha = .55f), modifier = Modifier.fillMaxSize(.24f))
        if (!url.isNullOrBlank()) {
            AsyncImage(url, "Artwork for $title", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
