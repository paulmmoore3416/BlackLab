package com.blacklab.app.media

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.blacklab.app.files.FileKinds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Song(
    val uri: Uri, val title: String, val artist: String, val album: String,
    val albumId: Long = 0, val duration: Long = 0, val path: String? = null, val added: Long = 0, val track: Int = 0,
) {
    fun toMediaItem(): MediaItem = MediaItem.Builder().setUri(uri).setMediaId(uri.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).setIsPlayable(true).setIsBrowsable(false).build())
        .build()
}

data class Playlist(val name: String, val items: List<String>)

object Music {
    // ------------------------------------------------------------------ controller

    private var controller: MediaController? = null

    /** Connects (once) to [PlaybackService]; safe to call from any screen. */
    suspend fun controller(c: Context): MediaController {
        controller?.takeIf { it.isConnected }?.let { return it }
        val token = SessionToken(c, ComponentName(c, PlaybackService::class.java))
        val future = MediaController.Builder(c.applicationContext, token).buildAsync()
        return suspendCancellableCoroutine { cont ->
            future.addListener({
                runCatching { future.get() }.onSuccess { controller = it; cont.resume(it) }.onFailure { cont.resumeWithException(it) }
            }, ContextCompat.getMainExecutor(c))
            cont.invokeOnCancellation { future.cancel(false) }
        }
    }

    suspend fun play(c: Context, songs: List<Song>, start: Int = 0, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        val m = controller(c)
        m.shuffleModeEnabled = shuffle
        m.setMediaItems(songs.map { it.toMediaItem() }, start.coerceIn(0, songs.lastIndex), 0)
        m.prepare(); m.play()
    }

    suspend fun enqueue(c: Context, songs: List<Song>, next: Boolean) {
        val m = controller(c)
        if (m.mediaItemCount == 0) { play(c, songs); return }
        val items = songs.map { it.toMediaItem() }
        if (next) m.addMediaItems((m.currentMediaItemIndex + 1).coerceAtMost(m.mediaItemCount), items) else m.addMediaItems(items)
    }

    // ------------------------------------------------------------------ sleep timer

    val sleepAt = MutableStateFlow(0L)
    private var sleepJob: Job? = null
    /** Pauses after [minutes]; 0 cancels; -1 = at the end of the current track. */
    fun sleep(c: Context, scope: CoroutineScope, minutes: Int) {
        sleepJob?.cancel(); sleepAt.value = 0
        if (minutes == 0) return
        sleepJob = scope.launch(Dispatchers.Main) {
            val m = controller(c)
            val ms = if (minutes < 0) (m.duration - m.currentPosition).coerceAtLeast(1000) else minutes * 60_000L
            sleepAt.value = System.currentTimeMillis() + ms
            delay(ms)
            // fade out over 8 seconds rather than cutting off
            val v = m.volume
            for (i in 1..16) { m.volume = v * (1 - i / 16f); delay(500) }
            m.pause(); m.volume = v; sleepAt.value = 0
        }
    }

    // ------------------------------------------------------------------ library

    fun songs(c: Context, includeShort: Boolean = false): List<Song> {
        val out = mutableListOf<Song>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.TRACK,
        )
        val sel = if (includeShort) null else "${MediaStore.Audio.Media.DURATION} >= 20000"
        runCatching {
            c.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE")?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    out += Song(
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        cur.getString(1) ?: "Unknown", cur.getString(2)?.takeIf { it != "<unknown>" } ?: "Unknown artist", cur.getString(3) ?: "Unknown album",
                        cur.getLong(4), cur.getLong(5), cur.getString(6), cur.getLong(7) * 1000, cur.getInt(8) % 1000,
                    )
                }
            }
        }
        return out
    }

    /** Audio files under a folder (BlackLab recordings, imports, a downloaded album…). */
    fun folderSongs(dir: File): List<Song> = dir.walkTopDown().maxDepth(4).filter { it.isFile && FileKinds.of(it) == com.blacklab.app.files.FileKind.AUDIO }
        .sortedByDescending { it.lastModified() }.map { fileSong(it) }.toList()

    fun fileSong(f: File): Song {
        var title = f.nameWithoutExtension; var artist = ""; var album = f.parentFile?.name ?: ""; var dur = 0L
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(f.path)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it }
                dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            }
        }
        return Song(Uri.fromFile(f), title, artist.ifBlank { "BlackLab" }, album, duration = dur, path = f.path, added = f.lastModified())
    }

    /** The local file behind a media item, when there is one (for editing / sharing). */
    fun fileOf(c: Context, uri: Uri?): File? {
        uri ?: return null
        if (uri.scheme == "file") return uri.path?.let { File(it) }
        return runCatching {
            c.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { cur -> if (cur.moveToFirst()) cur.getString(0)?.let { File(it) } else null }
        }.getOrNull()?.takeIf { it.canRead() }
    }

    private val art = object : LruCache<String, Bitmap>(12 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    private val noArt = HashSet<String>()

    suspend fun artwork(c: Context, uri: Uri, px: Int = 160): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$uri@$px"
        art.get(key)?.let { return@withContext it }
        if (key in noArt) return@withContext null
        val b = runCatching {
            if (uri.scheme == "content") c.contentResolver.loadThumbnail(uri, Size(px, px), null)
            else MediaMetadataRetriever().use { r -> r.setDataSource(uri.path); r.embeddedPicture?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) } }
        }.getOrNull()
        if (b != null) art.put(key, b) else noArt += key
        b
    }

    // ------------------------------------------------------------------ playlists

    private fun plFile(c: Context) = File(c.filesDir, "playlists.json")

    fun playlists(c: Context): List<Playlist> = runCatching {
        val a = JSONArray(plFile(c).readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> Playlist(o.getString("name"), o.getJSONArray("items").let { arr -> (0 until arr.length()).map { arr.getString(it) } }) } }
    }.getOrDefault(emptyList())

    fun savePlaylists(c: Context, list: List<Playlist>) {
        val a = JSONArray()
        list.forEach { p -> a.put(JSONObject().put("name", p.name).put("items", JSONArray(p.items))) }
        plFile(c).writeText(a.toString())
    }

    fun addToPlaylist(c: Context, name: String, songs: List<Song>) {
        val all = playlists(c).toMutableList()
        val i = all.indexOfFirst { it.name == name }
        val add = songs.map { it.uri.toString() }
        if (i >= 0) all[i] = all[i].copy(items = (all[i].items + add).distinct()) else all += Playlist(name, add)
        savePlaylists(c, all)
    }

    const val FAVORITES = "Favorites"
    fun isFavorite(c: Context, uri: String) = playlists(c).firstOrNull { it.name == FAVORITES }?.items?.contains(uri) == true
    fun toggleFavorite(c: Context, song: Song): Boolean {
        val all = playlists(c).toMutableList()
        val i = all.indexOfFirst { it.name == FAVORITES }
        val u = song.uri.toString()
        val fav = if (i < 0) { all += Playlist(FAVORITES, listOf(u)); true }
        else if (u in all[i].items) { all[i] = all[i].copy(items = all[i].items - u); false }
        else { all[i] = all[i].copy(items = all[i].items + u); true }
        savePlaylists(c, all)
        return fav
    }

    /** Resolves playlist entries against the library (and plain files). */
    fun resolve(p: Playlist, library: List<Song>): List<Song> {
        val byUri = library.associateBy { it.uri.toString() }
        return p.items.mapNotNull { u -> byUri[u] ?: Uri.parse(u).takeIf { it.scheme == "file" }?.path?.let { File(it) }?.takeIf { it.exists() }?.let { fileSong(it) } }
    }

    /** Writes an .m3u8 playlist file other apps can read. */
    fun exportM3u(songs: List<Song>, out: File) {
        out.writeText("#EXTM3U\n" + songs.joinToString("\n") { s -> "#EXTINF:${s.duration / 1000},${s.artist} - ${s.title}\n${s.path ?: s.uri}" })
    }
}
