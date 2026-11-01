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
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.blacklab.app.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * Background audio for BlackLab's music player: notification + lock-screen controls, Bluetooth
 * buttons, audio focus, pausing when headphones are unplugged, and system-wide effects.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
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
        session = MediaSession.Builder(this, exo).setSessionActivity(open).setId("blacklab-music").build()
        Fx.attach(this, exo.audioSessionId)
        handler.post(abLoop)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        Fx.release()
        session?.run { player.release(); release() }
        session = null
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
