package com.blacklab.app.ui.music

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Time
import com.blacklab.app.media.Fx
import com.blacklab.app.media.Music
import com.blacklab.app.media.PlaybackService
import com.blacklab.app.media.Playlist
import com.blacklab.app.media.Song
import com.blacklab.app.ui.components.ActionSheet
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SheetAction
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.pressable
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ------------------------------------------------------------------ player state

class PlayerUi {
    var controller by mutableStateOf<MediaController?>(null)
    var playing by mutableStateOf(false)
    var meta by mutableStateOf(MediaMetadata.EMPTY)
    var uri by mutableStateOf<Uri?>(null)
    var position by mutableLongStateOf(0)
    var duration by mutableLongStateOf(0)
    var index by mutableIntStateOf(0)
    var count by mutableIntStateOf(0)
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var speed by mutableFloatStateOf(1f)
    var pitch by mutableFloatStateOf(1f)
    var buffering by mutableStateOf(false)

    fun sync(p: Player) {
        playing = p.isPlaying; meta = p.mediaMetadata; uri = p.currentMediaItem?.localConfiguration?.uri ?: p.currentMediaItem?.mediaId?.let { Uri.parse(it) }
        duration = p.duration.coerceAtLeast(0); index = p.currentMediaItemIndex; count = p.mediaItemCount
        shuffle = p.shuffleModeEnabled; repeat = p.repeatMode; speed = p.playbackParameters.speed; pitch = p.playbackParameters.pitch
        buffering = p.playbackState == Player.STATE_BUFFERING; position = p.currentPosition
    }
}

@Composable
fun rememberPlayer(): PlayerUi {
    val ctx = LocalContext.current
    val ui = remember { PlayerUi() }
    val listener = remember { object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) = ui.sync(player) } }
    LaunchedEffect(Unit) {
        val c = runCatching { Music.controller(ctx) }.getOrNull() ?: return@LaunchedEffect
        ui.controller = c; ui.sync(c); c.addListener(listener)
        while (true) { if (c.isPlaying) ui.position = c.currentPosition; delay(300) }
    }
    DisposableEffect(Unit) { onDispose { ui.controller?.removeListener(listener) } }
    return ui
}

private fun fmt(ms: Long) = Time.duration(ms.coerceAtLeast(0))

// ------------------------------------------------------------------ library

private val MusicTabs = listOf("Songs", "Albums", "Artists", "Playlists", "BlackLab")

@Composable
fun MusicScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = settingsState()
    val player = rememberPlayer()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("title") }
    var refresh by remember { mutableIntStateOf(0) }
    var drill by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    var menu by remember { mutableStateOf<Song?>(null) }
    var addTo by remember { mutableStateOf<List<Song>?>(null) }

    val granted = remember(refresh) { Environment.isExternalStorageManager() || ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.READ_MEDIA_AUDIO) }

    val library by produceState<List<Song>?>(null, refresh, granted) { value = withContext(Dispatchers.IO) { if (granted) Music.songs(ctx) else emptyList() } }
    val blacklab by produceState(emptyList<Song>(), refresh, s.activeProfile) { value = withContext(Dispatchers.IO) { Music.folderSongs(app.storage.profileDir(s.activeProfile)) } }
    val playlists by produceState(emptyList<Playlist>(), refresh) { value = withContext(Dispatchers.IO) { Music.playlists(ctx) } }
    fun play(list: List<Song>, i: Int, shuffle: Boolean = false) = scope.launch { Music.play(ctx, list, i, shuffle); shell.go("nowplaying") }

    BackHandler(enabled = drill != null) { drill = null }
    val songs = library.orEmpty().let { l ->
        val f = if (query.isBlank()) l else l.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }
        when (sort) { "artist" -> f.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.track })); "recent" -> f.sortedByDescending { it.added }; else -> f }
    }

    Scaffold(topBar = {
        if (searching) Row(Modifier.statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search") }
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Songs, artists, albums") })
        } else BlTopBar(drill?.first ?: "Music", subtitle = drill?.let { "${it.second.size} songs" } ?: library?.let { "${it.size} songs on this phone" }, onBack = drill?.let { { drill = null } }, actions = {
            IconButton(onClick = { searching = true; tab = 0; drill = null }) { Icon(Icons.Rounded.Search, "Search") }
            IconButton(onClick = { refresh++ }) { Icon(Icons.Rounded.Refresh, "Rescan") }
        })
    }, bottomBar = { MiniPlayer(player) { shell.go("nowplaying") } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!granted) {
                EmptyState(Icons.Rounded.LibraryMusic, "Allow access to music", "BlackLab needs permission to read audio files to show your songs. BlackLab's own recordings work without it.",
                    action = { FilledTonalButton(onClick = { ask.launch(Manifest.permission.READ_MEDIA_AUDIO) }) { Text("Allow") } })
            }
            drill?.let { (_, list) -> SongList(list, onPlay = { play(list, it) }, onShuffle = { play(list, 0, true) }, onMenu = { menu = it }); return@Column }
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp, containerColor = MaterialTheme.colorScheme.background, divider = {}) {
                MusicTabs.forEachIndexed { i, l -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(l) }) }
            }
            when (MusicTabs[tab]) {
                "Songs" -> {
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("title" to "A–Z", "artist" to "Artist", "recent" to "Recently added").forEach { (k, l) -> FilterChip(sort == k, { sort = k }, label = { Text(l) }) }
                    }
                    if (library == null) com.blacklab.app.ui.components.SkeletonRows(8, Modifier.padding(16.dp), leading = 44.dp)
                    else if (songs.isEmpty() && granted) EmptyState(Icons.Rounded.MusicOff, if (query.isBlank()) "No music found" else "No matches", "Songs saved to the phone (Music, Downloads…) show up here automatically.")
                    else SongList(songs, onPlay = { play(songs, it) }, onShuffle = { play(songs, 0, true) }, onMenu = { menu = it })
                }
                "Albums" -> {
                    val albums = remember(library) { library.orEmpty().groupBy { it.albumId to it.album }.toList().sortedBy { it.first.second.lowercase() } }
                    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(albums, key = { it.first.first.toString() + it.first.second }) { (k, list) ->
                            Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { drill = k.second to list.sortedBy { it.track } }) {
                                Artwork(list.first().uri, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)), 360)
                                Text(k.second, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                                Text("${list.first().artist} · ${list.size}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                "Artists" -> {
                    val artists = remember(library) { library.orEmpty().groupBy { it.artist }.toList().sortedBy { it.first.lowercase() } }
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(artists, key = { it.first }) { (name, list) ->
                            Row(Modifier.fillMaxWidth().clickable { drill = name to list.sortedWith(compareBy({ it.album }, { it.track })) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(list.first().uri, Modifier.size(48.dp).clip(CircleShape), 120)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${list.map { it.album }.distinct().size} album(s) · ${list.size} songs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                "Playlists" -> PlaylistsTab(playlists, library.orEmpty(), onOpen = { p -> drill = p.name to Music.resolve(p, library.orEmpty()) }, onChanged = { refresh++ })
                else -> {
                    Text("Recordings, dictation and audio you've imported into BlackLab (${app.settings.profileName(s.activeProfile)}).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    if (blacklab.isEmpty()) EmptyState(Icons.Rounded.Mic, "No audio yet", "Record in the Recorder or import audio files in the Library.")
                    else SongList(blacklab, onPlay = { play(blacklab, it) }, onShuffle = null, onMenu = { menu = it })
                }
            }
        }
    }

    menu?.let { song ->
        val file = remember(song) { song.path?.let { File(it) }?.takeIf { it.canRead() } }
        var fav by remember(song) { mutableStateOf(Music.isFavorite(ctx, song.uri.toString())) }
        ActionSheet(title = song.title, subtitle = "${song.artist} · ${song.album} · ${fmt(song.duration)}", onDismiss = { menu = null },
            leading = { Artwork(song.uri, Modifier.fillMaxSize(), 160) },
            quick = listOf(
                SheetAction("Play next", Icons.Rounded.PlaylistPlay) { scope.launch { Music.enqueue(ctx, listOf(song), true); shell.toast("Playing next") } },
                SheetAction("Queue", Icons.AutoMirrored.Rounded.QueueMusic) { scope.launch { Music.enqueue(ctx, listOf(song), false); shell.toast("Added to queue") } },
                SheetAction(if (fav) "Unfavorite" else "Favorite", if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder) { fav = Music.toggleFavorite(ctx, song); refresh++ },
            ),
            actions = buildList {
                add(SheetAction("Add to playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd) { addTo = listOf(song) })
                if (file != null) {
                    add(SheetAction("Edit audio", Icons.Rounded.GraphicEq, hint = "Trim, fade, normalize, speed, effects") { shell.go("audioedit?path=" + Uri.encode(app.storage.relative(file))) })
                    add(SheetAction("Share", Icons.Rounded.Share) { Files.share(ctx, listOf(file)) })
                    add(SheetAction("Save a copy to BlackLab", Icons.Rounded.SaveAlt) { app.io { val f = com.blacklab.app.files.FileOps.saveToBlackLab(app, file); withContext(Dispatchers.Main) { shell.toast("Saved to ${f.parentFile?.name}") } } })
                }
            })
    }
    addTo?.let { list -> AddToPlaylistDialog(playlists, onDismiss = { addTo = null }) { name -> Music.addToPlaylist(ctx, name, list); refresh++; shell.toast("Added to $name"); addTo = null } }
}

@Composable
private fun SongList(songs: List<Song>, onPlay: (Int) -> Unit, onShuffle: (() -> Unit)?, onMenu: (Song) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onPlay(0) }, enabled = songs.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play all") }
                if (onShuffle != null) FilledTonalButton(onClick = onShuffle, enabled = songs.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle") }
            }
        }
        itemsIndexed(songs, key = { i, s -> s.uri.toString() + i }) { i, s ->
            Row(Modifier.fillMaxWidth().pressable(onClick = { onPlay(i) }, onLongClick = { onMenu(s) }).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(s.uri, Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)), 120)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${s.artist} · ${fmt(s.duration)}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onMenu(s) }) { Icon(Icons.Rounded.MoreVert, "More") }
            }
        }
    }
}

@Composable
private fun PlaylistsTab(playlists: List<Playlist>, library: List<Song>, onOpen: (Playlist) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val app = rememberApp()
    val shell = LocalShell.current
    var create by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf<Playlist?>(null) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { TextButton(onClick = { create = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("New playlist") } }
        if (playlists.isEmpty()) item { EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "No playlists", "Long-press any song to add it to a playlist or your Favorites.") }
        items(playlists, key = { it.name }) { p ->
            Row(Modifier.fillMaxWidth().pressable(onClick = { onOpen(p) }, onLongClick = { manage = p }).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(if (p.name == Music.FAVORITES) Icons.Rounded.Favorite else Icons.AutoMirrored.Rounded.QueueMusic, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) { Text(p.name); Text("${p.items.size} songs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { manage = p }) { Icon(Icons.Rounded.MoreVert, "More") }
            }
        }
    }
    if (create) TextInputDialog("New playlist", "", "Name", confirmLabel = "Create", onDismiss = { create = false }) { n ->
        if (playlists.none { it.name == n }) { Music.savePlaylists(ctx, playlists + Playlist(n, emptyList())); onChanged() }
    }
    manage?.let { p ->
        ActionSheet(title = p.name, subtitle = "${p.items.size} songs", onDismiss = { manage = null }, actions = listOf(
            SheetAction("Export as .m3u8", Icons.Rounded.Upload) {
                app.io {
                    val out = Files.unique(app.storage.dir(app.settings.current.activeProfile, Folder.EXPORTS), p.name, "m3u8")
                    Music.exportM3u(Music.resolve(p, library), out)
                    withContext(Dispatchers.Main) { shell.toast("Saved ${out.name} to Exports") }
                }
            },
            SheetAction("Delete playlist", Icons.Rounded.Delete, destructive = true) { Music.savePlaylists(ctx, playlists.filter { it.name != p.name }); onChanged() },
        ))
    }
}

@Composable
private fun AddToPlaylistDialog(playlists: List<Playlist>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                (listOf(Music.FAVORITES) + playlists.map { it.name }).distinct().forEach { n ->
                    Text(n, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(n) }.padding(12.dp))
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("New playlist name") })
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onPick(name.trim()) }) { Text("Create & add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun Artwork(uri: Uri?, modifier: Modifier, px: Int, data: ByteArray? = null) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri, data) {
        value = data?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(it, 0, it.size) } } ?: uri?.let { Music.artwork(ctx, it, px) }
    }
    Box(modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)))), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.fillMaxSize(0.45f))
    }
}

@Composable
fun MiniPlayer(p: PlayerUi, bottomInset: Boolean = true, onOpen: () -> Unit) {
    if (p.count == 0) return
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).then(if (bottomInset) Modifier.navigationBarsPadding() else Modifier).clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(p.uri, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), 120, p.meta.artworkData)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.meta.title?.toString() ?: "Playing", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(p.meta.artist?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { p.controller?.seekToPrevious() }) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
        IconButton(onClick = { p.controller?.let { if (it.isPlaying) it.pause() else it.play() } }) { Icon(if (p.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause") }
        IconButton(onClick = { p.controller?.seekToNext() }) { Icon(Icons.Rounded.SkipNext, "Next") }
    }
}

// ------------------------------------------------------------------ now playing

/** Route "player?path=…": plays an audio file (and the rest of its folder) then shows Now Playing. */
@Composable
fun PlayFileScreen(path: String) {
    val app = rememberApp()
    val ctx = LocalContext.current
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(path) {
        if (started) return@LaunchedEffect
        val f = app.storage.resolve(path)
        val list = withContext(Dispatchers.IO) {
            f.parentFile?.listFiles()?.filter { it.isFile && com.blacklab.app.files.FileKinds.of(it) == com.blacklab.app.files.FileKind.AUDIO }?.sortedBy { it.name.lowercase() }?.map { Music.fileSong(it) } ?: listOf(Music.fileSong(f))
        }
        Music.play(ctx, list, list.indexOfFirst { it.path == f.path }.coerceAtLeast(0))
        started = true
    }
    NowPlayingScreen()
}

@Composable
fun NowPlayingScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = rememberPlayer()
    val c = p.controller
    var dialog by remember { mutableStateOf<String?>(null) }
    val loopA by PlaybackService.loopA.collectAsStateWithLifecycle()
    val loopB by PlaybackService.loopB.collectAsStateWithLifecycle()
    val sleepAt by Music.sleepAt.collectAsStateWithLifecycle()
    var seeking by remember { mutableStateOf<Float?>(null) }
    val file = remember(p.uri) { Music.fileOf(ctx, p.uri) }
    var fav by remember(p.uri) { mutableStateOf(p.uri?.let { Music.isFavorite(ctx, it.toString()) } == true) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f), MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { shell.back() }) { Icon(Icons.Rounded.KeyboardArrowDown, "Close") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.count > 0) Text("${p.index + 1} of ${p.count}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { shell.go("music") }) { Icon(Icons.Rounded.LibraryMusic, "Library") }
            }
            Spacer(Modifier.height(12.dp))
            Artwork(p.uri, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)), 900, p.meta.artworkData)
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.meta.title?.toString() ?: if (p.count == 0) "Nothing playing" else "…", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(p.meta.artist?.toString(), p.meta.albumTitle?.toString()).filter { it.isNotBlank() }.joinToString(" — "), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = {
                    val u = p.uri ?: return@IconButton
                    fav = Music.toggleFavorite(ctx, Song(u, p.meta.title?.toString() ?: "", p.meta.artist?.toString() ?: "", p.meta.albumTitle?.toString() ?: ""))
                }) { Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorite", tint = if (fav) Color(0xFFFF5C7A) else MaterialTheme.colorScheme.onSurface) }
            }
            // seek bar with A-B markers
            Box {
                Slider(seeking ?: if (p.duration > 0) p.position.toFloat() / p.duration else 0f, { seeking = it }, onValueChangeFinished = { seeking?.let { c?.seekTo((it * p.duration).toLong()) }; seeking = null })
            }
            Row {
                Text(fmt(seeking?.let { (it * p.duration).toLong() } ?: p.position), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                if (loopA != null) Text("A ${fmt(loopA!!)}" + (loopB?.let { " → B ${fmt(it)}" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text("-" + fmt(p.duration - p.position), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { c?.shuffleModeEnabled = !p.shuffle }) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (p.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = { c?.seekBack() }) { Icon(Icons.Rounded.Replay10, "Back 10 seconds") }
                IconButton(onClick = { c?.seekToPrevious() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(36.dp)) }
                FilledIconButton(onClick = { c?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } } }, Modifier.size(76.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Icon(if (p.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", Modifier.size(42.dp))
                }
                IconButton(onClick = { c?.seekToNext() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(36.dp)) }
                IconButton(onClick = { c?.seekForward() }) { Icon(Icons.Rounded.Forward30, "Forward 30 seconds") }
                IconButton(onClick = { c?.repeatMode = when (p.repeat) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }) {
                    Icon(if (p.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if (p.repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                NpAction(Icons.Rounded.Speed, if (p.speed == 1f && p.pitch == 1f) "Speed" else "${"%.2f".format(p.speed).trimEnd('0').trimEnd('.')}×") { dialog = "speed" }
                NpAction(Icons.Rounded.Bedtime, if (sleepAt > 0) "${((sleepAt - System.currentTimeMillis()) / 60000 + 1)} min" else "Sleep", sleepAt > 0) { dialog = "sleep" }
                NpAction(Icons.Rounded.Equalizer, "EQ", Fx.state.collectAsStateWithLifecycle().value.enabled) { dialog = "eq" }
                NpAction(Icons.Rounded.Loop, when { loopA == null -> "A-B"; loopB == null -> "Set B"; else -> "Clear" }, loopA != null) {
                    when { loopA == null -> PlaybackService.loopA.value = p.position; loopB == null -> PlaybackService.loopB.value = p.position.takeIf { it > loopA!! }; else -> { PlaybackService.loopA.value = null; PlaybackService.loopB.value = null } }
                }
                NpAction(Icons.AutoMirrored.Rounded.QueueMusic, "Queue") { dialog = "queue" }
                if (file != null) NpAction(Icons.Rounded.GraphicEq, "Edit") { shell.go("audioedit?path=" + Uri.encode(app.storage.relative(file))) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    when (dialog) {
        "speed" -> SpeedDialog(p.speed, p.pitch, onDismiss = { dialog = null }) { s, pi -> c?.playbackParameters = PlaybackParameters(s, pi) }
        "sleep" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Sleep timer") },
            text = {
                Column {
                    listOf(5, 15, 30, 45, 60, 90).forEach { m -> Text("$m minutes", Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { Music.sleep(ctx, app.appScope, m); dialog = null }.padding(12.dp)) }
                    Text("End of this track", Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { Music.sleep(ctx, app.appScope, -1); dialog = null }.padding(12.dp))
                    if (sleepAt > 0) Text("Turn off timer", Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { Music.sleep(ctx, app.appScope, 0); dialog = null }.padding(12.dp), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
        )
        "eq" -> EqualizerDialog { dialog = null }
        "queue" -> QueueSheet(c, p) { dialog = null }
    }
}

@Composable
private fun NpAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp)) {
        Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SpeedDialog(speed: Float, pitch: Float, onDismiss: () -> Unit, onChange: (Float, Float) -> Unit) {
    var s by remember { mutableFloatStateOf(speed) }
    var pi by remember { mutableFloatStateOf(pitch) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback speed & pitch") },
        text = {
            Column {
                Text("Speed ${"%.2f".format(s)}×")
                Slider(s, { s = (it * 20).toInt() / 20f; onChange(s, pi) }, valueRange = 0.25f..3f)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { v -> FilterChip(s == v, { s = v; onChange(s, pi) }, label = { Text("$v×") }) } }
                Text("Pitch ${"%.2f".format(pi)}", Modifier.padding(top = 10.dp))
                Slider(pi, { pi = (it * 20).toInt() / 20f; onChange(s, pi) }, valueRange = 0.5f..2f)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { s = 1f; pi = 1f; onChange(1f, 1f) }) { Text("Reset") } },
    )
}

@Composable
private fun EqualizerDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val st by Fx.state.collectAsStateWithLifecycle()
    val (lo, hi) = Fx.levelRange
    val bands = remember(st) { Fx.currentBands() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Row(verticalAlignment = Alignment.CenterVertically) { Text("Equalizer", Modifier.weight(1f)); Switch(st.enabled, { Fx.apply(ctx, st.copy(enabled = it)) }) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (Fx.bandCount == 0) Text("Start playing something to adjust the equalizer.")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Fx.presets.forEachIndexed { i, n -> FilterChip(st.preset == i, { Fx.apply(ctx, st.copy(enabled = true, preset = i)) }, label = { Text(n) }) }
                }
                (0 until Fx.bandCount).forEach { i ->
                    val f = Fx.bandFreq(i)
                    Text((if (f >= 1000) "${f / 1000} kHz" else "$f Hz") + " · ${"%+.1f".format((bands.getOrElse(i) { 0 }) / 100f)} dB", style = MaterialTheme.typography.labelMedium)
                    Slider(bands.getOrElse(i) { 0 }.toFloat(), { v -> val nb = bands.toMutableList().also { while (it.size <= i) it += 0; it[i] = v.toInt() }; Fx.apply(ctx, st.copy(enabled = true, preset = -1, bands = nb)) },
                        valueRange = lo.toFloat()..hi.toFloat())
                }
                Text("Bass boost", style = MaterialTheme.typography.labelMedium)
                Slider(st.bass.toFloat(), { Fx.apply(ctx, st.copy(enabled = true, bass = it.toInt())) }, valueRange = 0f..1000f)
                Text("Surround (virtualizer)", style = MaterialTheme.typography.labelMedium)
                Slider(st.virtualizer.toFloat(), { Fx.apply(ctx, st.copy(enabled = true, virtualizer = it.toInt())) }, valueRange = 0f..1000f)
                Text("Loudness boost", style = MaterialTheme.typography.labelMedium)
                Slider(st.loudnessMb.toFloat(), { Fx.apply(ctx, st.copy(enabled = true, loudnessMb = it.toInt())) }, valueRange = 0f..1500f)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { Fx.apply(ctx, Fx.State(enabled = st.enabled, bands = List(Fx.bandCount) { 0 })) }) { Text("Flat") } },
    )
}

@Composable
private fun QueueSheet(c: MediaController?, p: PlayerUi, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Up next", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        val items = remember(p.count, p.index) { c?.let { m -> (0 until m.mediaItemCount).map { m.getMediaItemAt(it) } } ?: emptyList() }
        LazyColumn(Modifier.fillMaxWidth().height(460.dp)) {
            itemsIndexed(items) { i, item ->
                Row(Modifier.fillMaxWidth().clickable { c?.seekTo(i, 0); c?.play() }.background(if (i == p.index) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(32.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(item.mediaMetadata.title?.toString() ?: "Track ${i + 1}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (i == p.index) FontWeight.Bold else FontWeight.Normal)
                        Text(item.mediaMetadata.artist?.toString() ?: "", maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (i != p.index) IconButton(onClick = { c?.removeMediaItem(i) }) { Icon(Icons.Rounded.Close, "Remove") }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}
