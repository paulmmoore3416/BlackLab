// Media3 transformer, effect and session APIs used here are marked unstable; the version is pinned in build.gradle.kts.
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.blacklab.app.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors
import com.blacklab.app.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * Background audio for BlackLab's music player: notification + lock-screen controls, Bluetooth
 * buttons, audio focus, pausing when headphones are unplugged, and system-wide effects. It is also
 * a media *library*, so Android Auto's media launcher can browse songs, albums, playlists and your
 * BlackLab recordings, and "Hey Google, play … on BlackLab" searches it.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val io = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private val handler = Handler(Looper.getMainLooper())

    private val abLoop = object : Runnable {
        override fun run() {
            val p = player
            val a = loopA.value; val b = loopB.value
            if (p != null && a != null && b != null && b > a && p.currentPosition >= b) p.seekTo(a)
            handler.postDelayed(this, 150)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { Fx.attach(this@PlaybackService, audioSessionId) }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { loopA.value = null; loopB.value = null }
        })
        player = exo
        running.value = true
        val open = PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "nowplaying").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaLibrarySession.Builder(this, exo, LibraryCallback(this, io)).setSessionActivity(open).setId("blacklab-music").build()
        Fx.attach(this, exo.audioSessionId)
        handler.post(abLoop)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        Fx.release()
        session?.run { player.release(); release() }
        session = null
        io.shutdown()
        player = null
        running.value = false
        super.onDestroy()
    }

    companion object {
        /** Same-process handle for things a MediaController can't reach (audio session for effects). */
        @Volatile var player: ExoPlayer? = null
        /** True while the music service exists — drives the app-wide mini player. */
        val running = MutableStateFlow(false)
        val loopA = MutableStateFlow<Long?>(null)
        val loopB = MutableStateFlow<Long?>(null)
    }
}

/**
 * The browse tree Android Auto shows: Songs · Albums · Playlists · Recordings. Browsed items carry
 * "parent|uri" ids so tapping one song queues the whole album/playlist from that song.
 */
private class LibraryCallback(private val ctx: android.content.Context, private val io: com.google.common.util.concurrent.ListeningExecutorService) : MediaLibraryService.MediaLibrarySession.Callback {
    private val root = "root"
    private val tabs = listOf("songs" to "Songs", "albums" to "Albums", "playlists" to "Playlists", "recordings" to "Recordings")

    private fun folder(id: String, title: String, playable: Boolean = false) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(playable)
            .setMediaType(if (id == root) MediaMetadata.MEDIA_TYPE_FOLDER_MIXED else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()

    private fun songItem(parent: String, s: Song) = MediaItem.Builder().setMediaId("$parent|${s.uri}").setUri(s.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album).setIsBrowsable(false).setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()

    private fun songs(): List<Song> = Music.songs(ctx)
    private fun recordings(): List<Song> {
        val app = (ctx.applicationContext as com.blacklab.app.BlackLabApp).container
        val p = app.settings.current.activeProfile
        return (Music.folderSongs(app.storage.dir(p, com.blacklab.app.core.Folder.RECORDINGS)) + Music.folderSongs(app.storage.dir(p, com.blacklab.app.core.Folder.DICTATION)))
            .sortedByDescending { it.added }.take(200)
    }

    fun children(parent: String): List<MediaItem> = when {
        parent == root -> tabs.map { (id, t) -> folder(id, t) }
        parent == "songs" -> songs().take(1500).map { songItem("songs", it) }
        parent == "albums" -> songs().groupBy { it.albumId to it.album }.entries.sortedBy { it.key.second.lowercase() }.take(500)
            .map { (k, _) -> folder("album:${k.first}", k.second, playable = false) }
        parent.startsWith("album:") -> songs().filter { it.albumId.toString() == parent.removePrefix("album:") }.sortedBy { it.track }.map { songItem(parent, it) }
        parent == "playlists" -> Music.playlists(ctx).map { folder("pl:${it.name}", it.name) }
        parent.startsWith("pl:") -> Music.playlists(ctx).firstOrNull { it.name == parent.removePrefix("pl:") }?.let { Music.resolve(it, songs()) }.orEmpty().map { songItem(parent, it) }
        parent == "recordings" -> recordings().map { songItem("recordings", it) }
        else -> emptyList()
    }

    private fun resolve(item: MediaItem): MediaItem {
        if (item.localConfiguration != null) return item
        val id = item.mediaId
        val uri = android.net.Uri.parse(id.substringAfter('|'))
        val meta = if (item.mediaMetadata.title != null) item.mediaMetadata else MediaMetadata.Builder().setTitle(uri.lastPathSegment).setIsPlayable(true).setIsBrowsable(false).build()
        return item.buildUpon().setUri(uri).setMediaMetadata(meta).build()
    }

    private fun search(query: String): List<MediaItem> {
        val q = query.lowercase().trim()
        if (q.isBlank()) return emptyList()
        return (songs() + recordings()).filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) }
            .take(100).map { songItem("search", it) }
    }

    override fun onGetLibraryRoot(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(folder(root, "BlackLab"), params))

    override fun onGetChildren(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        io.submit<LibraryResult<ImmutableList<MediaItem>>> {
            val all = children(parentId)
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, (from + pageSize).coerceAtMost(all.size))), params)
        }

    override fun onGetItem(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
        io.submit<LibraryResult<MediaItem>> {
            val found = tabs.firstOrNull { it.first == mediaId }?.let { folder(it.first, it.second) } ?: if (mediaId.contains('|')) resolve(MediaItem.Builder().setMediaId(mediaId).build()) else null
            if (found != null) LibraryResult.ofItem(found, null) else LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE)
        }

    override fun onSearch(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<Void>> {
        io.execute { session.notifySearchResultChanged(browser, query, search(query).size, params) }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        io.submit<LibraryResult<ImmutableList<MediaItem>>> {
            val all = search(query)
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, (from + pageSize).coerceAtMost(all.size))), params)
        }

    /** Items from the phone UI already carry URIs; voice requests ("play … on BlackLab") carry a search query. */
    override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
        io.submit<MutableList<MediaItem>> {
            mediaItems.flatMap { item ->
                val q = item.requestMetadata.searchQuery
                when {
                    item.localConfiguration != null -> listOf(item)
                    !q.isNullOrBlank() -> search(q).ifEmpty { songs().shuffled().take(50).map { songItem("songs", it) } }
                    item.mediaId.isNotBlank() -> listOf(resolve(item))
                    else -> emptyList()
                }
            }.toMutableList()
        }

    /** Tapping one browsed song plays its whole list (album, playlist, songs) starting from it. */
    override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val single = mediaItems.singleOrNull()
        if (single != null && single.localConfiguration == null && single.mediaId.contains('|')) {
            return io.submit<MediaSession.MediaItemsWithStartPosition> {
                val parent = single.mediaId.substringBefore('|')
                val list = if (parent == "search") listOf(resolve(single)) else children(parent).map(::resolve)
                val at = list.indexOfFirst { it.mediaId == single.mediaId }.coerceAtLeast(0)
                MediaSession.MediaItemsWithStartPosition(list.ifEmpty { listOf(resolve(single)) }, at, startPositionMs)
            }
        }
        return com.google.common.util.concurrent.Futures.transform(onAddMediaItems(mediaSession, controller, mediaItems),
            { items -> MediaSession.MediaItemsWithStartPosition(items!!, startIndex, startPositionMs) }, MoreExecutors.directExecutor())
    }
}

/** Equalizer, bass boost, virtualizer and loudness, persisted across sessions. */
object Fx {
    data class State(
        val enabled: Boolean = false, val preset: Int = -1, val bands: List<Int> = emptyList(),
        val bass: Int = 0, val virtualizer: Int = 0, val loudnessMb: Int = 0,
    )

    val state = MutableStateFlow(State())
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null
    private var session = 0
    private var loaded = false

    val bandCount get() = eq?.numberOfBands?.toInt() ?: 0
    val levelRange get() = eq?.bandLevelRange?.let { it[0].toInt() to it[1].toInt() } ?: (-1500 to 1500)
    fun bandFreq(i: Int) = (eq?.getCenterFreq(i.toShort()) ?: 0) / 1000
    val presets get() = eq?.let { e -> (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) } } ?: emptyList()

    private fun prefs(c: Context) = c.getSharedPreferences("blacklab_fx", Context.MODE_PRIVATE)

    fun attach(c: Context, sessionId: Int) {
        if (!loaded) { load(c); loaded = true }
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == session && eq != null) return
        release(); session = sessionId
        runCatching { eq = Equalizer(0, sessionId) }
        runCatching { bass = BassBoost(0, sessionId) }
        runCatching { virt = Virtualizer(0, sessionId) }
        runCatching { loud = LoudnessEnhancer(sessionId) }
        apply(c, state.value)
    }

    fun apply(c: Context, s: State) {
        state.value = s
        runCatching {
            eq?.let { e ->
                e.enabled = s.enabled
                if (s.preset >= 0 && s.preset < e.numberOfPresets) e.usePreset(s.preset.toShort())
                else s.bands.forEachIndexed { i, lvl -> if (i < e.numberOfBands) e.setBandLevel(i.toShort(), lvl.toShort()) }
            }
            bass?.let { b -> b.enabled = s.enabled && s.bass > 0; if (b.strengthSupported) b.setStrength(s.bass.toShort()) }
            virt?.let { v -> v.enabled = s.enabled && s.virtualizer > 0; if (v.strengthSupported) v.setStrength(s.virtualizer.toShort()) }
            loud?.let { l -> l.enabled = s.enabled && s.loudnessMb > 0; l.setTargetGain(s.loudnessMb) }
        }
        save(c, s)
    }

    /** Current band levels (after a preset is applied). */
    fun currentBands(): List<Int> = eq?.let { e -> (0 until e.numberOfBands).map { e.getBandLevel(it.toShort()).toInt() } } ?: state.value.bands

    private fun save(c: Context, s: State) = prefs(c).edit().putString("fx", JSONObject().apply {
        put("enabled", s.enabled); put("preset", s.preset); put("bands", s.bands.joinToString(",")); put("bass", s.bass); put("virt", s.virtualizer); put("loud", s.loudnessMb)
    }.toString()).apply()

    private fun load(c: Context) = runCatching {
        val j = JSONObject(prefs(c).getString("fx", null) ?: return@runCatching)
        state.value = State(j.optBoolean("enabled"), j.optInt("preset", -1), j.optString("bands").split(',').mapNotNull { it.toIntOrNull() }, j.optInt("bass"), j.optInt("virt"), j.optInt("loud"))
    }

    fun release() {
        listOf(eq, bass, virt, loud).forEach { runCatching { it?.release() } }
        eq = null; bass = null; virt = null; loud = null; session = 0
    }
}
