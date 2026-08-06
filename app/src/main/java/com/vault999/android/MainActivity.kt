package com.vault999.android

import android.Manifest
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import com.vault999.android.catalog.CatalogViewModel
import com.vault999.android.archive.ArchiveViewModel
import com.vault999.android.search.SearchMode
import com.vault999.android.search.SearchResult
import com.vault999.android.search.SearchUiState
import com.vault999.android.search.SearchViewModel
import com.vault999.android.designsystem.VaultColors
import com.vault999.android.designsystem.VaultTheme
import com.vault999.android.designsystem.VaultTrackRow
import com.vault999.android.designsystem.VaultWordmark
import com.vault999.android.model.CanonicalSong
import com.vault999.android.model.ArchiveEntry
import com.vault999.android.model.ArchiveKind
import com.vault999.android.model.QueueItem
import com.vault999.android.model.SongCategory
import com.vault999.android.playback.PlaybackController
import com.vault999.android.viewer.ArchiveViewerViewModel
import com.vault999.android.music.LibraryUiState
import com.vault999.android.music.LibraryViewModel
import com.vault999.android.listen.ListenMode
import com.vault999.android.listen.ListenUiState
import com.vault999.android.listen.ListenViewModel
import com.vault999.android.listen.RadioUiState
import com.vault999.android.listen.RadioViewModel
import com.vault999.android.downloads.DownloadViewModel
import com.vault999.android.model.DownloadJob
import com.vault999.android.model.DownloadStage
import com.vault999.android.model.RepeatMode
import com.vault999.android.model.PlaybackMode
import com.vault999.android.model.QueueSnapshot
import com.vault999.android.playback.PlaybackUiState
import com.vault999.android.settings.SettingsViewModel
import com.vault999.android.preferences.NetworkPolicy
import com.vault999.android.preferences.VaultSettings
import com.vault999.android.account.AccountUiState
import com.vault999.android.account.AccountViewModel
import com.vault999.android.account.CloudLibraryUiState
import com.vault999.android.account.CloudLibraryViewModel
import com.vault999.android.account.CloudSyncScheduler
import com.vault999.android.auth.AccountProjection
import coil3.compose.AsyncImage
import androidx.media3.common.Player
import androidx.media3.ui.compose.PlayerSurface
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyQaOrientation(intent)
        val qaFontScale = intent.qaFontScale()
        setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(currentDensity.density, qaFontScale ?: currentDensity.fontScale),
            ) {
                VaultTheme { VaultApp() }
            }
        }
        reportFullyDrawn()
        consumeAuthIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeAuthIntent(intent)
    }

    private fun consumeAuthIntent(intent: Intent?) {
        val uri = intent?.dataString ?: return
        lifecycleScope.launch { (application as VaultApplication).graph.accountRepository.consumeCallback(uri) }
    }

    private fun applyQaOrientation(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        requestedOrientation = when (intent?.getStringExtra(QA_ORIENTATION_EXTRA)) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun Intent?.qaFontScale(): Float? {
        if (!BuildConfig.DEBUG || this == null || !hasExtra(QA_FONT_SCALE_EXTRA)) return null
        return getFloatExtra(QA_FONT_SCALE_EXTRA, 1f).takeIf { it in 1f..2f }
    }

    private companion object {
        const val QA_ORIENTATION_EXTRA = "com.vault999.android.qa.ORIENTATION"
        const val QA_FONT_SCALE_EXTRA = "com.vault999.android.qa.FONT_SCALE"
    }
}

private data class TopDestination(val route: String, val label: String, val icon: ImageVector)

private val topDestinations = listOf(
    TopDestination("archive", "Archive", Icons.Rounded.Home),
    TopDestination("listen", "Listen", Icons.Rounded.Radio),
    TopDestination("music", "Library", Icons.Rounded.LibraryMusic),
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
    val viewerViewModel: ArchiveViewerViewModel = viewModel(factory = ArchiveViewerViewModel.factory(graph.archiveViewerRepository))
    val viewerState by viewerViewModel.state.collectAsStateWithLifecycle()
    val libraryViewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(graph.libraryRepository))
    val libraryState by libraryViewModel.state.collectAsStateWithLifecycle()
    val selectedDevicePlaylist by libraryViewModel.selectedPlaylist.collectAsStateWithLifecycle()
    val listenViewModel: ListenViewModel = viewModel(
        factory = ListenViewModel.factory(if (fixtures.isNotEmpty()) flowOf(fixtures) else graph.catalogRepository.observeFirstPage()),
    )
    val listenState by listenViewModel.state.collectAsStateWithLifecycle()
    val radioViewModel: RadioViewModel = viewModel(factory = RadioViewModel.factory(graph.radioRepository))
    val radioState by radioViewModel.state.collectAsStateWithLifecycle()
    val downloadViewModel: DownloadViewModel = viewModel(factory = DownloadViewModel.factory(graph.downloadRepository))
    val downloadJobs by downloadViewModel.jobs.collectAsStateWithLifecycle()
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(graph.preferences))
    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
    val accountViewModel: AccountViewModel = viewModel(factory = AccountViewModel.factory(graph.accountRepository))
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(accountState.projection) {
        graph.libraryRepository.setActiveAccountId((accountState.projection as? AccountProjection.SignedIn)?.account?.id)
    }
    val cloudLibraryViewModel: CloudLibraryViewModel = viewModel(
        factory = CloudLibraryViewModel.factory(
            graph.accountRepository,
            graph.cloudLibraryRepository,
            remember(context.applicationContext) { CloudSyncScheduler(context.applicationContext) },
        ),
    )
    val cloudLibraryState by cloudLibraryViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(accountState.browserUrl) {
        val url = accountState.browserUrl ?: return@LaunchedEffect
        runCatching { CustomTabsIntent.Builder().build().launchUrl(context, android.net.Uri.parse(url)) }
        accountViewModel.browserOpened()
    }
    val playbackController = remember(context.applicationContext) { PlaybackController(context.applicationContext) }
    val playbackState by playbackController.state.collectAsStateWithLifecycle()
    val videoPlayer by playbackController.videoPlayer.collectAsStateWithLifecycle()
    DisposableEffect(playbackController) {
        playbackController.connect()
        onDispose { playbackController.close() }
    }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    var selectedLyrics by remember { mutableStateOf<SearchResult?>(null) }
    var pendingLyricsSongId by remember { mutableStateOf<Long?>(null) }
    var selectedCloudPlaylistId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(searchState.results, pendingLyricsSongId) {
        val id = pendingLyricsSongId ?: return@LaunchedEffect
        searchState.results.firstOrNull { it.song.id == id }?.let { result ->
            selectedLyrics = result
            pendingLyricsSongId = null
        }
    }
    val playSong: (CanonicalSong) -> Unit = { song ->
        song.streamUrl?.let { uri ->
            val canonicalQueue = catalogState.songs.asSequence()
                .filter(CanonicalSong::isPlayable)
                .sortedWith(compareBy<CanonicalSong> { it.publicNumber }.thenBy { it.id })
                .map { candidate ->
                    QueueItem(
                        mediaId = "song:${candidate.id}",
                        title = candidate.title,
                        artist = candidate.artist,
                        uri = requireNotNull(candidate.streamUrl),
                        artworkUri = candidate.artworkUrl,
                        durationMs = candidate.durationSeconds?.times(1000),
                        canonicalSongId = candidate.id,
                    )
                }.toList()
            val index = canonicalQueue.indexOfFirst { it.canonicalSongId == song.id }
            playbackController.setQueue(
                QueueSnapshot(
                    items = canonicalQueue.ifEmpty {
                        listOf(QueueItem(
                        mediaId = "song:${song.id}",
                        title = song.title,
                        artist = song.artist,
                        uri = uri,
                        artworkUri = song.artworkUrl,
                        durationMs = song.durationSeconds?.times(1000),
                        canonicalSongId = song.id,
                        ))
                    },
                    currentIndex = index.coerceAtLeast(0),
                    playbackMode = PlaybackMode.CATALOG,
                ),
            )
        }
    }
    LaunchedEffect(listenState.playRequest?.mediaId) {
        val selected = listenState.playRequest ?: return@LaunchedEffect
        playbackController.setQueue(
            QueueSnapshot(
                items = listOf(selected) + listenState.lookAhead.filterNot { it.mediaId == selected.mediaId },
                currentIndex = 0,
                playbackMode = PlaybackMode.LISTEN,
            ),
        )
        listenViewModel.consumePlayRequest()
    }
    val openArchiveEntry: (ArchiveEntry) -> Unit = { entry ->
        when (entry.kind) {
            ArchiveKind.AUDIO, ArchiveKind.LOSSLESS, ArchiveKind.VIDEO -> {
                playbackController.play(
                    listOf(
                        QueueItem(
                            mediaId = "archive:${entry.path}",
                            title = entry.name.substringBeforeLast('.'),
                            artist = "999 Vault Archive",
                            uri = graph.archiveApi.fileDownloadUrl(entry.path),
                        ),
                    ),
                )
                if (entry.kind == ArchiveKind.VIDEO) {
                    viewerViewModel.open(entry)
                    navController.navigate("viewer")
                }
            }
            ArchiveKind.ARTWORK, ArchiveKind.TEXT, ArchiveKind.OTHER -> {
                viewerViewModel.open(entry)
                navController.navigate("viewer")
            }
            ArchiveKind.DIRECTORY -> archiveViewModel.openFolder(entry.path)
        }
    }
    val topLevel = topDestinations.any { target -> destination?.hierarchy?.any { it.route == target.route } == true }

    Scaffold(
        containerColor = VaultColors.Canvas,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column(Modifier.background(VaultColors.Chrome).windowInsetsPadding(WindowInsets.navigationBars)) {
                if (topLevel) {
                    playbackState.currentItem?.let { item ->
                        MobileMiniPlayer(item = item, playing = playbackState.playing, positionMs = playbackState.positionMs, durationMs = playbackState.durationMs, onToggle = playbackController::toggle, onOpen = { navController.navigate("now-playing") })
                    }
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
                                alwaysShowLabel = false,
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
                MobileArchiveScreen(
                    songs = catalogState.songs,
                    loading = catalogState.loading,
                    offline = catalogState.offline,
                    error = catalogState.error,
                    healthStatus = catalogState.healthStatus,
                    totalSongs = catalogState.totalSongs,
                    categoryCounts = catalogState.categoryCounts,
                    randomSong = catalogState.randomSong,
                    discoveryLoading = catalogState.discoveryLoading,
                    onRetry = catalogViewModel::refresh,
                    onAnother = catalogViewModel::discover,
                    onPlay = playSong,
                    favoriteSongIds = libraryState.favorites.mapNotNull { it.canonicalSongId }.toSet(),
                    onFavorite = libraryViewModel::toggleFavorite,
                    onFullCollection = downloadViewModel::enqueueFullCollection,
                    onNested = navController::navigate,
                )
            }
            composable("listen") {
                MobileListenScreen(
                    state = listenState,
                    radio = radioState,
                    playing = playbackState.playing,
                    onToggle = playbackController::toggle,
                    onStart = listenViewModel::start,
                    onMode = listenViewModel::switchMode,
                    onBack = listenViewModel::back,
                    onNext = listenViewModel::next,
                    onForward = listenViewModel::forward,
                    onRefreshRadio = radioViewModel::refresh,
                    onPlayRadio = { radioState.playableStation?.let { playbackController.setQueue(QueueSnapshot(items = listOf(it), currentIndex = 0, playbackMode = PlaybackMode.RADIO)) } },
                )
            }
            composable("music") {
                MobileLibraryScreen(
                    state = libraryState,
                    cloud = cloudLibraryState,
                    catalog = catalogState.songs,
                    onCreatePlaylist = libraryViewModel::createPlaylist,
                    onDeletePlaylist = libraryViewModel::deletePlaylist,
                    onCreateCloudPlaylist = cloudLibraryViewModel::createPlaylist,
                    onDeleteCloudPlaylist = cloudLibraryViewModel::deletePlaylist,
                    onRetryCloud = cloudLibraryViewModel::retrySync,
                    onOpenDevicePlaylist = { id -> libraryViewModel.selectPlaylist(id); navController.navigate("playlist") },
                    onOpenCloudPlaylist = { id -> selectedCloudPlaylistId = id; navController.navigate("cloud-playlist") },
                    onPlayDownloaded = { item ->
                        item.localUri?.let { uri ->
                            playbackController.play(listOf(QueueItem("download:${item.id}", item.name, "On this device", uri, local = true)))
                        }
                    },
                    onPlaySong = playSong,
                    onNested = navController::navigate,
                )
            }
            composable("search") {
                MobileSearchScreen(
                    state = searchState,
                    onQuery = searchViewModel::setQuery,
                    onMode = searchViewModel::setMode,
                    onResult = { result ->
                        if (searchState.mode == SearchMode.LYRICS) {
                            selectedLyrics = result
                            navController.navigate("lyrics")
                        } else {
                            playSong(result.song)
                        }
                    },
                )
            }
            composable("downloads") {
                DownloadsScreen(
                    jobs = downloadJobs,
                    onPause = downloadViewModel::pause,
                    onResume = downloadViewModel::resume,
                    onCancel = downloadViewModel::cancel,
                    onBrowse = { navController.popBackStack(); navController.navigate("files") },
                    onBack = navController::popBackStack,
                )
            }
            composable("files") {
                ArchiveFilesScreen(
                    state = archiveState,
                    onOpenFolder = archiveViewModel::openFolder,
                    onOpenFile = openArchiveEntry,
                    onDownload = { entry -> downloadViewModel.enqueue(entry, graph.archiveApi.fileDownloadUrl(entry.path)) },
                    onDownloadSelection = downloadViewModel::enqueueSelection,
                    onRetry = archiveViewModel::refresh,
                    onBack = { if (archiveState.path.isBlank()) navController.popBackStack() else archiveViewModel.up() },
                )
            }
            composable("settings") {
                SettingsScreen(
                    state = settingsState,
                    onTree = settingsViewModel::setTree,
                    onNetwork = settingsViewModel::setNetwork,
                    onConcurrency = settingsViewModel::setConcurrency,
                    onReducedMotion = settingsViewModel::setReducedMotion,
                    account = accountState,
                    onSignIn = accountViewModel::signIn,
                    onLogout = accountViewModel::logout,
                    onCredits = { navController.navigate("credits") },
                    onBack = navController::popBackStack,
                )
            }
            composable("credits") { CreditsScreen(navController::popBackStack) }
            composable("wrapped") { MobileWrappedScreen(libraryState, catalogState.songs, playSong, { navController.popBackStack(); navController.navigate("listen") }, navController::popBackStack) }
            composable("lyrics") { LyricsScreen(selectedLyrics, navController::popBackStack, playSong) }
            composable("viewer") {
                ArchiveViewerScreen(
                    viewerState,
                    videoPlayer,
                    onDownload = { viewerState.entry?.let { downloadViewModel.enqueue(it, graph.archiveApi.fileDownloadUrl(it.path)) } },
                    onBack = navController::popBackStack,
                )
            }
            composable("now-playing") {
                val currentSong = playbackState.currentItem?.canonicalSongId?.let { id -> catalogState.songs.firstOrNull { it.id == id } }
                val cloudLike = currentSong?.id?.let { id -> cloudLibraryState.projection.cloudLikes.firstOrNull { it.songId == id } }
                MobileNowPlayingScreen(
                    state = playbackState,
                    favorite = currentSong?.id in libraryState.favorites.mapNotNull { it.canonicalSongId }.toSet(),
                    cloudLiked = cloudLike?.liked == true,
                    cloudVisible = cloudLibraryState.projection.cloudVisible,
                    canDownload = currentSong?.archivePath != null,
                    onToggle = playbackController::toggle,
                    onPrevious = playbackController::previous,
                    onNext = playbackController::next,
                    onSeek = playbackController::seekTo,
                    onShuffle = playbackController::setShuffle,
                    onRepeat = playbackController::setRepeat,
                    onRetry = playbackController::retry,
                    onFavorite = { currentSong?.id?.let(libraryViewModel::toggleFavorite) },
                    onCloudLike = { currentSong?.id?.let { cloudLibraryViewModel.setLike(it, cloudLike?.liked != true) } },
                    onLyrics = {
                        currentSong?.let { song ->
                            selectedLyrics = SearchResult(song)
                            pendingLyricsSongId = song.id
                            searchViewModel.setMode(SearchMode.LYRICS)
                            searchViewModel.setQuery(song.title)
                            navController.navigate("lyrics")
                        }
                    },
                    onDownload = {
                        currentSong?.archivePath?.let { path ->
                            downloadViewModel.enqueue(
                                ArchiveEntry(path = path, name = path.substringAfterLast('/'), kind = ArchiveKind.AUDIO, canonicalSongId = currentSong.id),
                                graph.archiveApi.fileDownloadUrl(path),
                            )
                        }
                    },
                    onOpenQueue = { navController.navigate("queue") },
                    onBack = navController::popBackStack,
                )
            }
            composable("queue") {
                QueueScreen(
                    state = playbackState,
                    onSelect = playbackController::skipTo,
                    onClear = playbackController::clearQueue,
                    onBack = navController::popBackStack,
                )
            }
            composable("playlist") {
                val detail = selectedDevicePlaylist
                PlaylistEditorScreen(
                    title = detail?.playlist?.name ?: "Device playlist",
                    description = detail?.playlist?.description.orEmpty(),
                    ownership = "On this device",
                    songIds = detail?.songIds.orEmpty(),
                    catalog = catalogState.songs,
                    onSave = { name, description -> detail?.playlist?.id?.let { libraryViewModel.updatePlaylist(it, name, description) } },
                    onSongs = { songs -> detail?.playlist?.id?.let { libraryViewModel.setPlaylistSongs(it, songs) } },
                    onDelete = {
                        detail?.playlist?.id?.let(libraryViewModel::deletePlaylist)
                        libraryViewModel.selectPlaylist(null)
                        navController.popBackStack()
                    },
                    onPlay = { songs, shuffle -> playPlaylist(songs, catalogState.songs, shuffle, playbackController) },
                    onBack = { libraryViewModel.selectPlaylist(null); navController.popBackStack() },
                )
            }
            composable("cloud-playlist") {
                val playlist = cloudLibraryState.projection.cloudPlaylists.firstOrNull { (it.localId ?: it.id) == selectedCloudPlaylistId }
                PlaylistEditorScreen(
                    title = playlist?.name ?: "Cloud playlist",
                    description = playlist?.description.orEmpty(),
                    ownership = playlist?.syncState?.name?.lowercase()?.replace('_', ' ') ?: "Unavailable",
                    songIds = playlist?.songIds.orEmpty(),
                    catalog = catalogState.songs,
                    onSave = { name, description -> playlist?.let { cloudLibraryViewModel.updatePlaylist(it.localId ?: it.id, name, description) } },
                    onSongs = { songs ->
                        playlist?.let { current ->
                            val localId = current.localId ?: current.id
                            current.songIds.filterNot(songs::contains).forEach { cloudLibraryViewModel.setPlaylistSong(localId, it, false) }
                            songs.filterNot(current.songIds::contains).forEach { cloudLibraryViewModel.setPlaylistSong(localId, it, true) }
                            if (songs.toSet() == current.songIds.toSet() && songs != current.songIds) cloudLibraryViewModel.reorderPlaylist(localId, songs)
                        }
                    },
                    onDelete = {
                        playlist?.let { cloudLibraryViewModel.deletePlaylist(it.localId ?: it.id) }
                        selectedCloudPlaylistId = null
                        navController.popBackStack()
                    },
                    onPlay = { songs, shuffle -> playPlaylist(songs, catalogState.songs, shuffle, playbackController) },
                    onBack = { selectedCloudPlaylistId = null; navController.popBackStack() },
                )
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
    onNested: (String) -> Unit,
) {
    var categoryFilter by remember { mutableStateOf<SongCategory?>(null) }
    var eraFilter by remember { mutableStateOf<String?>(null) }
    val eras = remember(songs) { songs.mapNotNull { it.era?.name }.distinct().sorted() }
    val visibleSongs = remember(songs, categoryFilter, eraFilter) {
        songs.filter { song ->
            (categoryFilter == null || song.category == categoryFilter) &&
                (eraFilter == null || song.era?.name == eraFilter)
        }
    }
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
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
                    MetricCard((categoryCounts.values.count { it > 0 }.takeIf { it > 0 } ?: 4).toString(), "CATEGORIES", Modifier.weight(1f))
                    MetricCard(totalSongs?.toString() ?: songs.size.toString(), healthStatus?.uppercase() ?: if (songs.isEmpty()) "OFFLINE" else "CATALOG", Modifier.weight(1f))
                }
                randomSong?.let { discovery ->
                    Column(Modifier.fillMaxWidth().background(VaultColors.SurfaceRaised).padding(16.dp)) {
                        Text("RANDOM FIND", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
                        Text(discovery.title, style = MaterialTheme.typography.titleLarge)
                        Text(discovery.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            Button(onClick = { onPlay(discovery) }, enabled = discovery.isPlayable) { Text("Play") }
                            Button(onClick = onAnother, enabled = !discoveryLoading) { Text(if (discoveryLoading) "Loading…" else "Another") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onNested("wrapped") }, modifier = Modifier.weight(1f)) { Text("Wrapped") }
                    Button(onClick = { onNested("credits") }, modifier = Modifier.weight(1f)) { Text("Credits") }
                }
                Button(onClick = { onNested("files") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Folder, contentDescription = null)
                    Text(" Browse archive files")
                }
                Button(onClick = onFullCollection, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Text(" Download full Compilation")
                }
                Text("CATALOG", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(selected = categoryFilter == null, onClick = { categoryFilter = null }, label = { Text("All") })
                    }
                    items(listOf(SongCategory.RELEASED, SongCategory.UNRELEASED, SongCategory.UNSURFACED, SongCategory.SESSION)) { category ->
                        FilterChip(
                            selected = categoryFilter == category,
                            onClick = { categoryFilter = category },
                            label = { Text(if (category == SongCategory.SESSION) "Sessions" else category.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }
                if (eras.isNotEmpty()) {
                    Text("ERA", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = eraFilter == null, onClick = { eraFilter = null }, label = { Text("All eras") }) }
                        items(eras, key = { "era:$it" }) { era ->
                            FilterChip(selected = eraFilter == era, onClick = { eraFilter = era }, label = { Text(era) })
                        }
                    }
                }
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
        } else if (visibleSongs.isEmpty() && !loading) {
            item { Text("No songs in this category are present in the loaded catalog page.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(visibleSongs, key = { it.id }) { song ->
                VaultTrackRow(
                    number = song.publicNumber,
                    title = song.title,
                    metadata = "${song.artist} · ${song.era?.name ?: "Unknown era"}",
                    status = song.category.name.replace('_', ' '),
                    onPlay = { onPlay(song) },
                    favorite = song.id in favoriteSongIds,
                    onFavorite = { onFavorite(song.id) },
                )
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
    onOpenFile: (ArchiveEntry) -> Unit,
    onDownload: (ArchiveEntry) -> Unit,
    onDownloadSelection: (List<String>) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf<ArchiveKind?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var selecting by remember { mutableStateOf(false) }
    val visibleItems = remember(state.items, query, kindFilter) {
        state.items.filter { entry ->
            (query.isBlank() || entry.name.contains(query.trim(), ignoreCase = true)) &&
                (kindFilter == null || entry.kind == ArchiveKind.DIRECTORY || entry.kind == kindFilter)
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (state.path.isBlank()) "Files" else state.path.substringAfterLast('/')) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { selecting = !selecting; if (!selecting) selected = emptySet() }) {
                    Icon(Icons.Rounded.CheckCircle, if (selecting) "Done selecting" else "Select files", tint = if (selecting) VaultColors.Yellow else MaterialTheme.colorScheme.onSurface)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultColors.Chrome),
        )
        if (state.offline && state.items.isNotEmpty()) Text("Offline · showing saved file index", color = VaultColors.Yellow, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(120) },
            placeholder = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { FilterChip(selected = kindFilter == null, onClick = { kindFilter = null }, label = { Text("All") }) }
            items(listOf(ArchiveKind.AUDIO, ArchiveKind.LOSSLESS, ArchiveKind.ARTWORK, ArchiveKind.VIDEO, ArchiveKind.TEXT)) { kind ->
                FilterChip(selected = kindFilter == kind, onClick = { kindFilter = kind }, label = { Text(kind.name.lowercase().replaceFirstChar(Char::uppercase)) })
            }
        }
        if (selecting && visibleItems.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val visible = visibleItems.map(ArchiveEntry::path).toSet()
                    selected = if (selected.containsAll(visible)) selected - visible else selected + visible
                }, modifier = Modifier.weight(1f)) { Text(if (selected.containsAll(visibleItems.map(ArchiveEntry::path))) "Clear visible" else "Select visible") }
                Button(
                    onClick = { onDownloadSelection(selected.toList()); selected = emptySet() },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Download ${selected.size}") }
            }
        }
        if (state.loading && state.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = VaultColors.Cyan) }
        } else if (state.error != null && state.items.isEmpty()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(state.error, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry) { Text("Retry") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(visibleItems, key = { it.path }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().height(64.dp)
                            .clickable(onClickLabel = if (entry.kind == ArchiveKind.DIRECTORY) "Open ${entry.name}" else "View or play ${entry.name}") {
                                if (entry.kind == ArchiveKind.DIRECTORY) onOpenFolder(entry.path) else onOpenFile(entry)
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (entry.kind == ArchiveKind.DIRECTORY) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile, contentDescription = null, tint = if (entry.kind == ArchiveKind.DIRECTORY) VaultColors.Yellow else VaultColors.Cyan)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (entry.kind == ArchiveKind.DIRECTORY) "Folder" else listOfNotNull(
                                    entry.kind.name.lowercase().replaceFirstChar(Char::uppercase),
                                    entry.sizeBytes?.takeIf { it > 0 }?.let(::formatBytes),
                                ).joinToString(" · "),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selecting) {
                            Checkbox(
                                checked = entry.path in selected,
                                onCheckedChange = { checked -> selected = if (checked) selected + entry.path else selected - entry.path },
                            )
                        }
                        if (entry.kind == ArchiveKind.DIRECTORY) {
                            IconButton(onClick = { onDownloadSelection(listOf(entry.path)) }) {
                                Icon(Icons.Rounded.Download, contentDescription = "Download ${entry.name} recursively")
                            }
                        } else {
                            IconButton(onClick = { onDownload(entry) }) {
                                Icon(Icons.Rounded.Download, contentDescription = "Download ${entry.name}")
                            }
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
private fun ListenScreen(
    state: ListenUiState,
    radio: RadioUiState,
    onStart: (ListenMode) -> Unit,
    onMode: (ListenMode) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onForward: () -> Unit,
    onRefreshRadio: () -> Unit,
    onPlayRadio: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            VaultWordmark()
            Text("Listen", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 12.dp))
            Text("Eight ahead · eight recent · reversible history", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ListenMode.entries, key = { it.name }) { mode ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { onMode(mode) },
                        label = { Text(mode.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            Button(onClick = { onStart(state.mode) }, enabled = state.catalogReady, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Text(if (state.current == null) " Start endless listen" else " Restart this mode")
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
        state.current?.let { current ->
            item {
                Column(Modifier.fillMaxWidth().background(VaultColors.SurfaceRaised).padding(16.dp)) {
                    Text("NOW IN LISTEN", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
                    Text(current.title, style = MaterialTheme.typography.titleLarge)
                    Text(current.artist)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Button(onClick = onBack, enabled = state.canGoBack) { Text("Back") }
                        Button(onClick = onNext) { Text("Next") }
                        Button(onClick = onForward, enabled = state.canGoForward) { Text("Forward") }
                    }
                }
            }
        }
        if (state.lookAhead.isNotEmpty()) {
            item { Text("UP NEXT · ${state.lookAhead.size}", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge) }
            items(state.lookAhead.take(8), key = { "ahead:${it.mediaId}" }) { item -> Text("${item.title} · ${item.artist}") }
        }
        if (state.recents.isNotEmpty()) {
            item { Text("RECENT", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge) }
            items(state.recents, key = { "recent:${it.mediaId}" }) { item -> Text("${item.title} · ${item.artist}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item {
            HorizontalDivider(color = VaultColors.SurfaceRaised)
            Text("999 FM", color = VaultColors.Cyan, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
            when {
                radio.loading -> CircularProgressIndicator(color = VaultColors.Cyan)
                radio.station != null -> {
                    Text(radio.station.nowTitle ?: radio.station.station, style = MaterialTheme.typography.titleMedium)
                    Text("${radio.station.listenerCount} listeners · ${if (radio.station.isLive) "LIVE" else radio.station.state}")
                    radio.error?.let { Text(it, color = VaultColors.Yellow) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onPlayRadio, enabled = radio.playableStation != null) { Text("Play station") }
                        Button(onClick = onRefreshRadio, enabled = !radio.refreshing) { Text("Refresh") }
                    }
                    if (radio.station.queuePreview.isNotEmpty()) Text("Queue: ${radio.station.queuePreview.take(3).joinToString(" · ")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Text(radio.error ?: "Radio status unavailable", color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRefreshRadio) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun MyMusicScreen(
    state: LibraryUiState,
    cloud: CloudLibraryUiState,
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onCreateCloudPlaylist: (String, String) -> Unit,
    onDeleteCloudPlaylist: (String) -> Unit,
    onRetryCloud: () -> Unit,
    onOpenDevicePlaylist: (String) -> Unit,
    onOpenCloudPlaylist: (String) -> Unit,
    onPlayDownloaded: (com.vault999.android.music.DownloadedItem) -> Unit,
    onNested: (String) -> Unit,
) {
    var playlistName by remember { mutableStateOf("") }
    var cloudPlaylistName by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            VaultWordmark()
            Text("My Music", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 14.dp))
            Text("On this device", color = VaultColors.Cyan, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 18.dp))
            Text("${state.downloads.size} downloads · ${state.favorites.size} favorites · ${state.playlists.size} playlists")
            Button(onClick = { onNested("downloads") }, modifier = Modifier.padding(top = 10.dp)) {
                Icon(Icons.Rounded.Download, null)
                Text(" Downloads")
            }
            OutlinedTextField(
                value = playlistName,
                onValueChange = { playlistName = it.take(80) },
                label = { Text("New device playlist") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
            Button(
                onClick = { onCreatePlaylist(playlistName); playlistName = "" },
                enabled = playlistName.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Create playlist") }
        }
        if (state.playlists.isEmpty()) {
            item { Text("No device playlists yet. Signed-out playlists remain on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(state.playlists, key = { it.id }) { playlist ->
                Column(Modifier.fillMaxWidth().background(VaultColors.SurfaceRaised).clickable { onOpenDevicePlaylist(playlist.id) }.padding(14.dp)) {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                    Text(playlist.ownership.replace('_', ' '), color = VaultColors.Cyan, style = MaterialTheme.typography.labelMedium)
                    if (playlist.id.startsWith("device:")) {
                        Button(onClick = { onDeletePlaylist(playlist.id) }, modifier = Modifier.padding(top = 8.dp)) { Text("Delete") }
                    }
                }
            }
        }
        if (state.downloads.isNotEmpty()) {
            item { Text("DOWNLOADED AUDIO", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) }
            items(state.downloads, key = { "download:${it.id}" }) { item ->
                Column(Modifier.fillMaxWidth().background(VaultColors.SurfaceRaised).padding(14.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (item.localUri != null) "Ready on this device" else "Saved collection or SAF item",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { onPlayDownloaded(item) }, enabled = item.localUri != null, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Text(if (item.localUri != null) " Play local file" else " Playback unavailable")
                    }
                }
            }
        }
        item {
            Text("Cloud library", color = VaultColors.Cyan, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
            if (!cloud.projection.cloudVisible) {
                Text("Sign in is optional. Cloud likes and playlists are hidden while signed out; device records remain available.")
            } else {
                Text("${cloud.projection.cloudLikes.count { it.liked }} cloud likes · ${cloud.projection.cloudPlaylists.size} cloud playlists")
                OutlinedTextField(
                    value = cloudPlaylistName,
                    onValueChange = { cloudPlaylistName = it.take(80) },
                    label = { Text("New cloud playlist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Button(
                    onClick = { onCreateCloudPlaylist(cloudPlaylistName, ""); cloudPlaylistName = "" },
                    enabled = cloudPlaylistName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Create cloud playlist") }
                cloud.projection.cloudPlaylists.forEach { playlist ->
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(VaultColors.SurfaceRaised).clickable { onOpenCloudPlaylist(playlist.localId ?: playlist.id) }.padding(14.dp)) {
                        Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                        Text(playlist.syncState.name.lowercase().replace('_', ' '), color = when (playlist.syncState.name) {
                            "SYNCED" -> VaultColors.Green
                            "ERROR", "CONFLICT" -> MaterialTheme.colorScheme.error
                            else -> VaultColors.Yellow
                        })
                        Text("${playlist.songIds.size} songs · Synced account", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { onDeleteCloudPlaylist(playlist.localId ?: playlist.id) }, modifier = Modifier.padding(top = 8.dp)) { Text("Delete") }
                    }
                }
                cloud.message?.let { Text(it, color = VaultColors.Yellow, modifier = Modifier.padding(top = 8.dp)) }
                Button(onClick = onRetryCloud, enabled = !cloud.syncing, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (cloud.syncing) "Syncing…" else "Retry cloud sync")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WrappedScreen(state: LibraryUiState, catalog: List<CanonicalSong>, onBack: () -> Unit) {
    val songsById = remember(catalog) { catalog.associateBy { it.id } }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Wrapped") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultColors.Chrome),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text("YOUR LISTENING", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
                Text("Honest coverage only", style = MaterialTheme.typography.displaySmall)
                Text("Legacy totals without timestamps are excluded from rolling periods.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            listOf("All time" to state.allTime, "30 days" to state.thirtyDays, "7 days" to state.sevenDays).forEach { (label, summary) ->
                item {
                    Column(Modifier.fillMaxWidth().background(VaultColors.SurfaceRaised).padding(18.dp)) {
                        Text(label, color = VaultColors.Cyan, style = MaterialTheme.typography.titleLarge)
                        Text("${summary.totalPlays}", color = VaultColors.Yellow, style = MaterialTheme.typography.displaySmall)
                        Text("credited plays · ${summary.distinctSongs} songs · ${summary.listenedSeconds / 60} minutes")
                        if (summary.coverageStartEpochMs == null) Text("No timestamped listening events yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        summary.topSongs.take(5).forEachIndexed { index, song ->
                            Text(
                                "${index + 1}. ${songsById[song.songId]?.title ?: "Song ${song.songId}"} · ${song.plays} plays",
                                color = if (index == 0) VaultColors.Yellow else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(state: SearchUiState, onQuery: (String) -> Unit, onMode: (SearchMode) -> Unit, onResult: (SearchResult) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
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
            VaultTrackRow(
                result.song.publicNumber,
                result.song.title,
                result.excerpt ?: result.song.artist,
                if (state.mode == SearchMode.LYRICS) "OPEN LYRICS" else result.song.category.name.replace('_', ' '),
                { onResult(result) },
            )
            HorizontalDivider(color = VaultColors.SurfaceRaised)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsScreen(result: SearchResult?, onBack: () -> Unit, onPlay: (CanonicalSong) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(result?.song?.title ?: "Lyrics") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultColors.Chrome),
            actions = {
                IconButton(onClick = { result?.song?.let(onPlay) }, enabled = result?.song?.isPlayable == true) {
                    Icon(Icons.Rounded.PlayArrow, "Play song")
                }
            },
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
            item {
                Text(result?.song?.artist.orEmpty(), color = VaultColors.Cyan, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Text(
                    result?.fullLyrics?.takeIf(String::isNotBlank) ?: "Full lyrics are unavailable for this result.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ArchiveViewerScreen(state: com.vault999.android.viewer.ViewerUiState, videoPlayer: Player?, onDownload: () -> Unit, onBack: () -> Unit) {
    var videoPlaying by remember(state.entry?.path) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(state.entry?.name ?: "Archive viewer", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultColors.Chrome),
        )
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = VaultColors.Cyan) }
            state.error != null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Preview unavailable", style = MaterialTheme.typography.titleLarge)
                Button(onClick = onDownload) { Text("Download") }
            }
            state.entry?.kind == ArchiveKind.ARTWORK -> AsyncImage(
                model = state.mediaUrl,
                contentDescription = state.entry.name,
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
            state.entry?.kind == ArchiveKind.TEXT -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            ) { item { Text(state.text.orEmpty(), style = MaterialTheme.typography.bodyLarge) } }
            state.entry?.kind == ArchiveKind.VIDEO -> Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
                PlayerSurface(player = videoPlayer, modifier = Modifier.fillMaxSize())
                FilledIconButton(onClick = {
                    videoPlayer?.let { player -> if (videoPlaying) player.pause() else player.play() }
                    videoPlaying = !videoPlaying
                }, enabled = videoPlayer != null, modifier = Modifier.size(64.dp)) {
                    Icon(if (videoPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (videoPlaying) "Pause video" else "Play video")
                }
            }
            else -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Preview unavailable", style = MaterialTheme.typography.titleLarge)
                Button(onClick = onDownload) { Text("Download") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(
    state: VaultSettings,
    onTree: (String?) -> Unit,
    onNetwork: (NetworkPolicy) -> Unit,
    onConcurrency: (Int) -> Unit,
    onReducedMotion: (Boolean) -> Unit,
    account: AccountUiState,
    onSignIn: () -> Unit,
    onLogout: () -> Unit,
    onCredits: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsGranted = granted
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onSuccess { onTree(uri.toString()) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultColors.Chrome),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("DOWNLOADS", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
                Text(if (state.safTreeUri == null) "Vault storage" else "Selected folder", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { folderPicker.launch(null) }) { Text("Change") }
                    Button(onClick = { onTree(null) }, enabled = state.safTreeUri != null) { Text("Reset") }
                }
            }
            item {
                Text("NOTIFICATIONS", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationsGranted) {
                    Text("On", color = VaultColors.Green)
                } else {
                    Text("Playback controls and download progress")
                    Button(
                        onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Allow notifications") }
                }
            }
            item {
                Text("NETWORK", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NetworkPolicy.entries.forEach { policy ->
                        FilterChip(
                            selected = state.networkPolicy == policy,
                            onClick = { onNetwork(policy) },
                            label = { Text(when (policy) {
                                NetworkPolicy.ANY -> "Any network"
                                NetworkPolicy.WIFI_ONLY -> "Wi-Fi only"
                                NetworkPolicy.ASK_ON_METERED -> "Ask on mobile data"
                            }) },
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Reduce motion", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = state.reducedMotion, onCheckedChange = onReducedMotion)
                }
            }
            item {
                Text("ACCOUNT", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
                when (val projection = account.projection) {
                    AccountProjection.SignedOut -> {
                        if (account.configured) {
                            Text("Sync likes and playlists.")
                            Button(onClick = onSignIn, enabled = !account.working, modifier = Modifier.padding(top = 8.dp)) { Text("Sign in") }
                        } else {
                            Text("Account sync isn’t available", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    is AccountProjection.SignedIn -> {
                        Text(projection.account.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(if (projection.availability.name == "OFFLINE_CACHED") "Offline · cached account" else "Synced account", color = VaultColors.Cyan)
                        Button(onClick = onLogout, enabled = !account.working, modifier = Modifier.padding(top = 8.dp)) { Text("Sign out") }
                    }
                }
                account.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            }
            item { TextButton(onClick = onCredits) { Text("About & credits") } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsScreen(
    jobs: List<DownloadJob>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onBrowse: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Downloads") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultColors.Chrome),
        )
        if (jobs.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.Download, contentDescription = null, tint = VaultColors.Cyan)
                Text("No downloads yet", style = MaterialTheme.typography.titleLarge)
                Button(onClick = onBrowse, modifier = Modifier.padding(top = 12.dp)) { Text("Browse files") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(jobs, key = { it.id }) { job ->
                    Column(
                        Modifier.fillMaxWidth().background(VaultColors.SurfaceRaised)
                            .semantics { contentDescription = "${job.displayName}, ${job.stage.name.lowercase()}, ${job.bytesCompleted} bytes downloaded" }
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(job.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(job.stage.name.lowercase().replace('_', ' '), color = when (job.stage) {
                            DownloadStage.FAILED, DownloadStage.COMPLETED_WITH_ERRORS -> MaterialTheme.colorScheme.error
                            DownloadStage.COMPLETED -> VaultColors.Green
                            DownloadStage.PAUSED, DownloadStage.CANCELLED -> VaultColors.Yellow
                            else -> VaultColors.Cyan
                        })
                        val totalBytes = job.bytesTotal
                        Text(
                            if (totalBytes != null && totalBytes > 0) "${formatBytes(job.bytesCompleted)} / ${formatBytes(totalBytes)}" else formatBytes(job.bytesCompleted),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        job.currentItem?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (job.stage in setOf(DownloadStage.PREPARING, DownloadStage.DOWNLOADING, DownloadStage.EXTRACTING)) {
                            val speed = job.bytesPerSecond
                            val eta = job.etaSeconds
                            Text(
                                when {
                                    speed == null -> "Calculating…"
                                    eta == null -> "${formatBytes(speed)}/s · Calculating…"
                                    else -> "${formatBytes(speed)}/s · about ${eta}s remaining"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        job.errorCode?.let { Text("Retryable failure: $it", color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (job.stage) {
                                DownloadStage.PAUSED, DownloadStage.FAILED, DownloadStage.INTERRUPTED -> Button(onClick = { onResume(job.id) }) { Text("Resume") }
                                DownloadStage.DOWNLOADING, DownloadStage.PREPARING, DownloadStage.EXTRACTING -> Button(onClick = { onPause(job.id) }) { Text("Pause") }
                                else -> Unit
                            }
                            if (job.stage !in setOf(DownloadStage.COMPLETED, DownloadStage.CANCELLED, DownloadStage.CANCELLING)) {
                                Button(onClick = { onCancel(job.id) }) { Text("Cancel") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NestedScreen(title: String, message: String, icon: ImageVector, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultColors.Chrome))
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
        TopAppBar(title = { Text("Credits") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultColors.Chrome))
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            VaultWordmark()
            Text("999 Vault is an independent project. It is not affiliated with, endorsed by, sponsored by, or operated by the JuiceWRLDAPI team.")
            Text("Special thanks to JuiceWRLDAPI.com for maintaining the archive, metadata, media endpoints and public API.", color = VaultColors.Cyan)
            Text("Music, artwork, names and trademarks belong to their respective artists, creators and rights holders.")
        }
    }
}

@Composable
private fun MiniPlayer(item: QueueItem, playing: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(68.dp).background(VaultColors.SurfaceRaised).semantics { contentDescription = "Mini player, ${item.title}, ${if (playing) "playing" else "paused"}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            Column {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(item.artist, color = VaultColors.Cyan, maxLines = 1)
            }
        }
        IconButton(onClick = onOpen) { Icon(Icons.Rounded.MoreVert, "Open Now Playing") }
        FilledIconButton(onClick = onToggle) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play") }
        Spacer(Modifier.padding(end = 6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NowPlayingScreen(
    state: PlaybackUiState,
    favorite: Boolean,
    cloudLiked: Boolean,
    cloudVisible: Boolean,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: (Boolean) -> Unit,
    onRepeat: (RepeatMode) -> Unit,
    onVolume: (Float) -> Unit,
    onRetry: () -> Unit,
    onFavorite: () -> Unit,
    onCloudLike: () -> Unit,
    onLyrics: () -> Unit,
    onDownload: () -> Unit,
    onOpenQueue: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Now Playing") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultColors.Chrome))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp).background(VaultColors.SurfaceRaised), contentAlignment = Alignment.Center) {
                val artwork = state.currentItem?.artworkUri
                if (artwork != null) {
                    AsyncImage(
                        model = artwork,
                        contentDescription = "Artwork for ${state.title}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                } else {
                    Icon(Icons.Rounded.MusicNote, contentDescription = "Cover artwork unavailable", tint = VaultColors.Cyan, modifier = Modifier.fillMaxSize(.35f))
                }
            }
            Text(state.title.ifBlank { "Nothing playing" }, style = MaterialTheme.typography.headlineMedium)
            Text(state.artist.ifBlank { "Choose a track from the archive" }, color = VaultColors.Cyan)
            val duration = state.durationMs
            if (duration != null && duration > 0) {
                Slider(
                    value = state.positionMs.coerceIn(0, duration).toFloat(),
                    onValueChangeFinished = {},
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier.semantics { contentDescription = "Playback position" },
                )
                Text("${state.positionMs / 1000}s / ${duration / 1000}s", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = onPrevious, enabled = state.currentItem != null) { Text("‹") }
                FilledIconButton(onClick = onToggle, enabled = state.currentItem != null) {
                    Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play")
                }
                FilledIconButton(onClick = onNext, enabled = state.currentItem != null) { Text("›") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = state.shuffle, onClick = { onShuffle(!state.shuffle) }, label = { Text("Shuffle") })
                FilterChip(
                    selected = state.repeatMode != RepeatMode.OFF,
                    onClick = { onRepeat(if (state.repeatMode == RepeatMode.OFF) RepeatMode.ALL else if (state.repeatMode == RepeatMode.ALL) RepeatMode.ONE else RepeatMode.OFF) },
                    label = { Text("Repeat ${state.repeatMode.name.lowercase()}") },
                )
            }
            val playbackContext = LocalContext.current
            var outputLabel by remember(playbackContext) { mutableStateOf(audioOutputLabel(playbackContext)) }
            DisposableEffect(playbackContext) {
                val audioManager = playbackContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val callback = object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                        outputLabel = audioOutputLabel(playbackContext)
                    }

                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                        outputLabel = audioOutputLabel(playbackContext)
                    }
                }
                audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
                onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
            }
            Text("Output: $outputLabel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Volume", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = state.volume,
                    onValueChange = onVolume,
                    modifier = Modifier.weight(1f).padding(start = 12.dp).semantics { contentDescription = "Playback volume" },
                )
                Text("${(state.volume * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(selected = favorite, onClick = onFavorite, enabled = state.currentItem?.canonicalSongId != null, label = { Text(if (favorite) "On device" else "Device favorite") }, leadingIcon = { Icon(Icons.Rounded.Favorite, contentDescription = null) })
                if (cloudVisible) FilterChip(selected = cloudLiked, onClick = onCloudLike, enabled = state.currentItem?.canonicalSongId != null, label = { Text(if (cloudLiked) "Cloud liked" else "Like in cloud") }, leadingIcon = { Icon(Icons.Rounded.Favorite, contentDescription = null) })
                FilterChip(selected = false, onClick = onLyrics, enabled = state.currentItem?.canonicalSongId != null, label = { Text("Lyrics") })
                FilterChip(selected = false, onClick = onDownload, enabled = state.currentItem?.canonicalSongId != null, label = { Text("Download") }, leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) })
                FilterChip(selected = false, onClick = onOpenQueue, enabled = state.queue.isNotEmpty(), label = { Text("Queue") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) })
            }
            Text("${state.queue.size} in queue · ${state.playbackMode.name.lowercase().replace('_', ' ')}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry) { Text("Retry playback") }
            }
        }
    }
}

private fun audioOutputLabel(context: Context): String {
    val outputs = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val preferred = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        ?: outputs.firstOrNull { it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET) }
    return when (preferred?.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        else -> "System audio"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueScreen(
    state: PlaybackUiState,
    onSelect: (Int) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Queue") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = { Button(onClick = onClear, enabled = state.queue.isNotEmpty()) { Text("Clear") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultColors.Chrome),
        )
        if (state.queue.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = VaultColors.Cyan)
                Text("Queue is empty", style = MaterialTheme.typography.titleLarge)
                Text("Choose a song from Archive, Listen, or 999 FM to start a queue.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.queue.size, key = { state.queue[it].mediaId }) { index ->
                    val item = state.queue[index]
                    Row(
                        Modifier.fillMaxWidth().clickable(onClickLabel = "Play ${item.title}") { onSelect(index) }.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text((index + 1).toString().padStart(2, '0'), color = if (index == state.currentIndex) VaultColors.Yellow else VaultColors.Cyan)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (index == state.currentIndex) Text("PLAYING", color = VaultColors.Green, style = MaterialTheme.typography.labelLarge)
                    }
                    HorizontalDivider(color = VaultColors.SurfaceRaised)
                }
            }
        }
    }
}

private fun playPlaylist(
    songIds: List<Long>,
    catalog: List<CanonicalSong>,
    shuffle: Boolean,
    controller: PlaybackController,
) {
    val byId = catalog.associateBy { it.id }
    val items = songIds.mapNotNull(byId::get).filter(CanonicalSong::isPlayable).map { song ->
        QueueItem(
            mediaId = "song:${song.id}",
            title = song.title,
            artist = song.artist,
            uri = requireNotNull(song.streamUrl),
            artworkUri = song.artworkUrl,
            durationMs = song.durationSeconds?.times(1_000),
            canonicalSongId = song.id,
        )
    }
    if (items.isNotEmpty()) {
        controller.setQueue(QueueSnapshot(items = items, currentIndex = 0, shuffle = shuffle, playbackMode = PlaybackMode.EXPLICIT_QUEUE))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistEditorScreen(
    title: String,
    description: String,
    ownership: String,
    songIds: List<Long>,
    catalog: List<CanonicalSong>,
    onSave: (String, String) -> Unit,
    onSongs: (List<Long>) -> Unit,
    onDelete: () -> Unit,
    onPlay: (List<Long>, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var editedName by remember(title) { mutableStateOf(title) }
    var editedDescription by remember(description) { mutableStateOf(description) }
    var songQuery by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val songsById = remember(catalog) { catalog.associateBy { it.id } }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this playlist?") },
            confirmButton = { Button(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { editing = !editing }) { Icon(Icons.Rounded.Edit, "Edit playlist") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = VaultColors.Chrome),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("$ownership · ${songIds.size} songs", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledIconButton(onClick = { onPlay(songIds, false) }, enabled = songIds.isNotEmpty(), modifier = Modifier.size(58.dp)) {
                        Icon(Icons.Rounded.PlayArrow, "Play playlist")
                    }
                    FilledIconButton(onClick = { onPlay(songIds, true) }, enabled = songIds.isNotEmpty(), modifier = Modifier.size(58.dp)) {
                        Icon(Icons.Rounded.Shuffle, "Shuffle playlist")
                    }
                }
                if (editing) {
                    OutlinedTextField(editedName, { editedName = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(editedDescription, { editedDescription = it.take(500) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Description") })
                    Button(onClick = { onSave(editedName, editedDescription); editing = false }, enabled = editedName.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Save") }
                }
                OutlinedTextField(
                    value = songQuery,
                    onValueChange = { songQuery = it.take(80) },
                    placeholder = { Text("Add songs") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            if (songQuery.isNotBlank()) {
                val matches = catalog.filter { song ->
                    song.id !in songIds && (song.title.contains(songQuery, true) || song.artist.contains(songQuery, true))
                }.take(6)
                items(matches, key = { "add-song:${it.id}" }) { song ->
                    Row(Modifier.fillMaxWidth().clickable { onSongs(songIds + song.id); songQuery = "" }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.MusicNote, null, tint = VaultColors.Cyan)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(song.artist, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.Add, "Add ${song.title}")
                    }
                }
            }
            if (songIds.isEmpty()) {
                item { Text("No songs yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(songIds.size, key = { "playlist-song:${songIds[it]}" }) { index ->
                    val songId = songIds[index]
                    val song = songsById[songId]
                    Column(Modifier.fillMaxWidth().background(VaultColors.SurfaceRaised).padding(14.dp)) {
                        Text(song?.title ?: "Song $songId", style = MaterialTheme.typography.titleMedium)
                        Text(song?.artist ?: "Canonical ID $songId", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            IconButton(onClick = {
                                val reordered = songIds.toMutableList()
                                val previous = reordered[index - 1]
                                reordered[index - 1] = reordered[index]
                                reordered[index] = previous
                                onSongs(reordered)
                            }, enabled = index > 0) { Icon(Icons.Rounded.ArrowUpward, "Move ${song?.title ?: "song"} up") }
                            IconButton(onClick = {
                                val reordered = songIds.toMutableList()
                                val next = reordered[index + 1]
                                reordered[index + 1] = reordered[index]
                                reordered[index] = next
                                onSongs(reordered)
                            }, enabled = index < songIds.lastIndex) { Icon(Icons.Rounded.ArrowDownward, "Move ${song?.title ?: "song"} down") }
                            IconButton(onClick = { onSongs(songIds.filterNot { it == songId }) }) { Icon(Icons.Rounded.Delete, "Remove ${song?.title ?: "song"}") }
                        }
                    }
                }
            }
            item { TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete playlist", color = MaterialTheme.colorScheme.error) } }
        }
    }
}
