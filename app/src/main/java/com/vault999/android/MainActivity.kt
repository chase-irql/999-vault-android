package com.vault999.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vault999.android.catalog.CatalogViewModel
import com.vault999.android.archive.ArchiveViewModel
import com.vault999.android.search.SearchMode
import com.vault999.android.search.SearchUiState
import com.vault999.android.search.SearchViewModel
import com.vault999.android.designsystem.VaultColors
import com.vault999.android.designsystem.VaultTheme
import com.vault999.android.designsystem.VaultTrackRow
import com.vault999.android.designsystem.VaultWordmark
import com.vault999.android.model.CanonicalSong
import com.vault999.android.model.ArchiveKind
import com.vault999.android.model.QueueItem
import com.vault999.android.playback.PlaybackController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VaultTheme { VaultApp() } }
    }
}

private data class TopDestination(val route: String, val label: String, val icon: ImageVector)

private val topDestinations = listOf(
    TopDestination("archive", "Archive", Icons.Rounded.Folder),
    TopDestination("listen", "Listen", Icons.Rounded.Radio),
    TopDestination("music", "My Music", Icons.Rounded.LibraryMusic),
    TopDestination("search", "Search", Icons.Rounded.Search),
)

@Composable
fun VaultApp() {
    val context = LocalContext.current
    val graph = remember(context.applicationContext) { (context.applicationContext as VaultApplication).graph }
    val fixtures = remember { fixtureSongs() }
    val catalogViewModel: CatalogViewModel = viewModel(factory = CatalogViewModel.factory(graph.catalogRepository, fixtures))
    val catalogState by catalogViewModel.state.collectAsStateWithLifecycle()
    val archiveViewModel: ArchiveViewModel = viewModel(factory = ArchiveViewModel.factory(graph.archiveRepository))
    val archiveState by archiveViewModel.state.collectAsStateWithLifecycle()
    val searchViewModel: SearchViewModel = viewModel(factory = SearchViewModel.factory(graph.searchRepository))
    val searchState by searchViewModel.state.collectAsStateWithLifecycle()
    val playbackController = remember(context.applicationContext) { PlaybackController(context.applicationContext) }
    val playbackState by playbackController.state.collectAsStateWithLifecycle()
    DisposableEffect(playbackController) {
        playbackController.connect()
        onDispose { playbackController.close() }
    }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    var currentSong by remember { mutableStateOf<CanonicalSong?>(null) }
    val playSong: (CanonicalSong) -> Unit = { song ->
        song.streamUrl?.let { uri ->
            currentSong = song
            playbackController.play(
                listOf(
                    QueueItem(
                        mediaId = "song:${song.id}",
                        title = song.title,
                        artist = song.artist,
                        uri = uri,
                        artworkUri = song.artworkUrl,
                        durationMs = song.durationSeconds?.times(1000),
                        canonicalSongId = song.id,
                    ),
                ),
            )
        }
    }
    val topLevel = topDestinations.any { target -> destination?.hierarchy?.any { it.route == target.route } == true }

    Scaffold(
        containerColor = VaultColors.Canvas,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column(Modifier.background(VaultColors.Chrome).windowInsetsPadding(WindowInsets.navigationBars)) {
                currentSong?.let { song ->
                    MiniPlayer(song = song, playing = playbackState.playing, onToggle = playbackController::toggle, onOpen = { navController.navigate("now-playing") })
                }
                if (topLevel) {
                    NavigationBar(containerColor = VaultColors.Chrome) {
                        topDestinations.forEach { target ->
                            val selected = destination?.hierarchy?.any { it.route == target.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(target.route) {
                                        popUpTo("archive") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(target.icon, contentDescription = null) },
                                label = { Text(target.label) },
                                modifier = Modifier.semantics { contentDescription = "${target.label} tab${if (selected) ", selected" else ""}" },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController = navController, startDestination = "archive", modifier = Modifier.padding(padding)) {
            composable("archive") {
                ArchiveScreen(
                    songs = catalogState.songs,
                    loading = catalogState.loading,
                    offline = catalogState.offline,
                    error = catalogState.error,
                    onRetry = catalogViewModel::refresh,
                    onPlay = playSong,
                    onNested = navController::navigate,
                )
            }
            composable("listen") { ListenScreen(songs = catalogState.songs, onPlay = playSong) }
            composable("music") { MyMusicScreen(onNested = navController::navigate) }
            composable("search") {
                SearchScreen(
                    state = searchState,
                    onQuery = searchViewModel::setQuery,
                    onMode = searchViewModel::setMode,
                    onPlay = playSong,
                )
            }
            composable("downloads") { NestedScreen("Downloads", "Durable transfers, stages, speed, ETA and recovery", Icons.Rounded.Download, navController::popBackStack) }
            composable("files") {
                ArchiveFilesScreen(
                    state = archiveState,
                    onOpenFolder = archiveViewModel::openFolder,
                    onRetry = archiveViewModel::refresh,
                    onBack = { if (archiveState.path.isBlank()) navController.popBackStack() else archiveViewModel.up() },
                )
            }
            composable("settings") { NestedScreen("Settings", "Storage, network, appearance, accessibility and account", Icons.Rounded.Settings, navController::popBackStack) }
            composable("credits") { CreditsScreen(navController::popBackStack) }
            composable("wrapped") { NestedScreen("Wrapped", "Honest all-time, 30-day and 7-day listening coverage", Icons.Rounded.Favorite, navController::popBackStack) }
            composable("now-playing") {
                NowPlayingScreen(currentSong = currentSong, playing = playbackState.playing, onToggle = playbackController::toggle, onBack = navController::popBackStack)
            }
        }
    }
}

@Composable
private fun ArchiveScreen(
    songs: List<CanonicalSong>,
    loading: Boolean,
    offline: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onPlay: (CanonicalSong) -> Unit,
    onNested: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    VaultWordmark(Modifier.weight(1f))
                    IconButton(onClick = { onNested("settings") }) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
                    IconButton(onClick = { onNested("downloads") }) { Icon(Icons.Rounded.Download, contentDescription = "Downloads") }
                }
                Text("THE ARCHIVE, IN YOUR POCKET", style = MaterialTheme.typography.displaySmall)
                Text("Browse the canonical catalog and the original file tree. Local and cloud ownership always stay explicit.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("4", "CATEGORIES", Modifier.weight(1f))
                    MetricCard(if (songs.isEmpty()) "OFFLINE" else "LIVE", "CATALOG", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onNested("wrapped") }, modifier = Modifier.weight(1f)) { Text("Wrapped") }
                    Button(onClick = { onNested("credits") }, modifier = Modifier.weight(1f)) { Text("Credits") }
                }
                Button(onClick = { onNested("files") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Folder, contentDescription = null)
                    Text(" Browse archive files")
                }
                Text("CATALOG", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
                if (loading) Text("Refreshing the live catalog…", color = VaultColors.Cyan)
                if (offline && songs.isNotEmpty()) Text("Offline · showing saved catalog", color = VaultColors.Yellow)
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }
        }
        if (songs.isEmpty() && !loading) {
            item { EmptyCatalog() }
        } else {
            items(songs, key = { it.id }) { song ->
                VaultTrackRow(song.publicNumber, song.title, "${song.artist} · ${song.era?.name ?: "Unknown era"}", song.category.name.replace('_', ' '), { onPlay(song) })
                HorizontalDivider(color = VaultColors.SurfaceRaised)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveFilesScreen(
    state: com.vault999.android.archive.ArchiveUiState,
    onOpenFolder: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (state.path.isBlank()) "Archive Library" else state.path.substringAfterLast('/')) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultColors.Chrome),
        )
        if (state.offline && state.items.isNotEmpty()) Text("Offline · showing saved file index", color = VaultColors.Yellow, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (state.loading && state.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = VaultColors.Cyan) }
        } else if (state.error != null && state.items.isEmpty()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(state.error, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry) { Text("Retry") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.items, key = { it.path }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().height(64.dp)
                            .clickable(enabled = entry.kind == ArchiveKind.DIRECTORY, onClickLabel = if (entry.kind == ArchiveKind.DIRECTORY) "Open ${entry.name}" else null) {
                                if (entry.kind == ArchiveKind.DIRECTORY) onOpenFolder(entry.path)
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (entry.kind == ArchiveKind.DIRECTORY) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile, contentDescription = null, tint = if (entry.kind == ArchiveKind.DIRECTORY) VaultColors.Yellow else VaultColors.Cyan)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(entry.kind.name.lowercase().replaceFirstChar(Char::uppercase), entry.sizeBytes?.let(::formatBytes)).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = VaultColors.SurfaceRaised)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GiB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MiB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun MetricCard(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.background(VaultColors.SurfaceRaised).padding(16.dp)) {
        Text(value, color = VaultColors.Yellow, style = MaterialTheme.typography.headlineMedium)
        Text(label, color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun EmptyCatalog() {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.Home, contentDescription = null, tint = VaultColors.Cyan)
        Text("Catalog unavailable", style = MaterialTheme.typography.titleLarge)
        Text("Connect to refresh the live archive. Downloads and device library remain available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ListenScreen(songs: List<CanonicalSong>, onPlay: (CanonicalSong) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        VaultWordmark()
        Text("Listen", style = MaterialTheme.typography.displaySmall)
        Text("Eight tracks ahead. Eight recent tracks behind. No immediate repeats.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { songs.firstOrNull()?.let(onPlay) }, enabled = songs.isNotEmpty()) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Text(" Start endless listen")
        }
        Text("999 FM", color = VaultColors.Cyan, style = MaterialTheme.typography.titleLarge)
        Text("Station status and listener count will remain visibly offline when the live endpoint cannot be reached.")
    }
}

@Composable
private fun MyMusicScreen(onNested: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        VaultWordmark()
        Text("My Music", style = MaterialTheme.typography.displaySmall)
        Text("On this device", color = VaultColors.Cyan, style = MaterialTheme.typography.titleLarge)
        Text("Downloads, favorites, playlists, queue and listening history stay here when you sign out.")
        Text("Cloud library", color = VaultColors.Cyan, style = MaterialTheme.typography.titleLarge)
        Text("Sign in optionally to add synced likes, playlists and acknowledged listening events.")
        Button(onClick = { onNested("downloads") }) { Icon(Icons.Rounded.Download, null); Text(" Downloads") }
    }
}

@Composable
private fun SearchScreen(state: SearchUiState, onQuery: (String) -> Unit, onMode: (SearchMode) -> Unit, onPlay: (CanonicalSong) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VaultWordmark()
                Text("Search", style = MaterialTheme.typography.displaySmall)
                Text("Songs · Lyrics", color = VaultColors.Cyan)
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(if (state.mode == SearchMode.LYRICS) "Search lyrics" else "Search songs, artists, producers") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.mode == SearchMode.SONGS, onClick = { onMode(SearchMode.SONGS) }, label = { Text("Songs") })
                    FilterChip(selected = state.mode == SearchMode.LYRICS, onClick = { onMode(SearchMode.LYRICS) }, label = { Text("Lyrics") })
                }
                if (state.loading) CircularProgressIndicator(color = VaultColors.Cyan)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.query.length < 2) Text("Enter at least two characters. Lyrics are always rendered as plain text.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.loading && state.query.length >= 2 && state.results.isEmpty() && state.error == null) Text("No matching ${state.mode.name.lowercase()} found.")
            }
        }
        items(state.results, key = { "${state.mode}:${it.song.id}" }) { result ->
            VaultTrackRow(result.song.publicNumber, result.song.title, result.excerpt ?: result.song.artist, result.song.category.name.replace('_', ' '), { onPlay(result.song) })
            HorizontalDivider(color = VaultColors.SurfaceRaised)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NestedScreen(title: String, message: String, icon: ImageVector, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultColors.Chrome))
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, tint = VaultColors.Cyan)
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreditsScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Credits") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultColors.Chrome))
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            VaultWordmark()
            Text("999 Vault is an independent project. It is not affiliated with, endorsed by, sponsored by, or operated by the JuiceWRLDAPI team.")
            Text("Special thanks to JuiceWRLDAPI.com for maintaining the archive, metadata, media endpoints and public API.", color = VaultColors.Cyan)
            Text("Music, artwork, names and trademarks belong to their respective artists, creators and rights holders.")
        }
    }
}

@Composable
private fun MiniPlayer(song: CanonicalSong, playing: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(68.dp).background(VaultColors.SurfaceRaised).semantics { contentDescription = "Mini player, ${song.title}, ${if (playing) "playing" else "paused"}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            Column {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(song.artist, color = VaultColors.Cyan, maxLines = 1)
            }
        }
        IconButton(onClick = onOpen) { Icon(Icons.Rounded.MoreVert, "Open Now Playing") }
        FilledIconButton(onClick = onToggle) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play") }
        Spacer(Modifier.padding(end = 6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlayingScreen(currentSong: CanonicalSong?, playing: Boolean, onToggle: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Now Playing") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultColors.Chrome))
        Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f).background(VaultColors.SurfaceRaised), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, contentDescription = "Cover artwork unavailable", tint = VaultColors.Cyan, modifier = Modifier.fillMaxSize(.35f))
            }
            Text(currentSong?.title ?: "Nothing playing", style = MaterialTheme.typography.headlineMedium)
            Text(currentSong?.artist ?: "Choose a track from the archive", color = VaultColors.Cyan)
            FilledIconButton(onClick = onToggle, enabled = currentSong != null) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play") }
        }
    }
}
