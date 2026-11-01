package com.blacklab.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.blacklab.app.core.IncomingGuard
import com.blacklab.app.ui.AppRoot
import com.blacklab.app.ui.theme.BlackLabTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    companion object {
        const val EXTRA_ROUTE = "route"
        /** Route requested by a notification / shortcut, consumed by the nav host. */
        val pendingRoute = MutableStateFlow<String?>(null)
        /** Files or text shared into BlackLab from other apps. */
        val pendingShare = MutableStateFlow<SharePayload?>(null)
        /** Text a shortcut asked the assistant to run once its screen is open. */
        val pendingAsk = MutableStateFlow<String?>(null)
        val locked = MutableStateFlow(false)
        private var backgroundedAt = 0L
    }

    data class SharePayload(val uris: List<Uri>, val text: String?, val subject: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = app
        if (savedInstanceState == null) {
            locked.value = app.settings.current.appLock
            // Open in the user's default profile on a cold start
            if (app.settings.current.onboarded) app.settings.setProfile(app.settings.current.defaultProfile)
        }
        handleIntent(intent)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { backgroundedAt = System.currentTimeMillis() }
            override fun onStart(owner: LifecycleOwner) {
                val s = app.settings.current
                if (s.appLock && backgroundedAt > 0 && System.currentTimeMillis() - backgroundedAt > s.lockTimeoutSec * 1000L) locked.value = true
            }
        })

        setContent {
            val settings by app.settings.state.collectAsState()
            val themeMode = com.blacklab.app.ui.theme.rememberThemeMode(settings)
            val dark = when (themeMode) { "LIGHT" -> false; "SYSTEM" -> isSystemInDarkTheme(); else -> true }
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                )
            }
            BlackLabTheme(themeMode, settings.dynamicColor, settings.activeProfile, com.blacklab.app.ui.theme.ThemeChoice.from(settings)) {
                AppRoot(activity = this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.blacklab.app.core.FrameStats.attach(this)
        val app = app
        com.blacklab.app.link.LinkService.sync(this)
        if (app.settings.current.cloudSync && System.currentTimeMillis() - com.blacklab.app.link.CloudSync.lastSync.value > 120_000) {
            app.appScope.launch { com.blacklab.app.link.CloudSync.sync(app) }
        }
    }

    override fun onPause() {
        com.blacklab.app.core.FrameStats.detach(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // This activity is exported, so any app can send these extras. Routes may not carry file
        // paths (that would open BlackLab's private files in the viewer/editor), and only the
        // home-screen shortcut's own request runs in the assistant: arbitrary text could otherwise
        // send email or change settings on another app's behalf.
        intent.getStringExtra(EXTRA_ROUTE)?.takeIf { IncomingGuard.routeAllowed(it) }?.let { pendingRoute.value = it }
        intent.getStringExtra("ask")?.takeIf { IncomingGuard.askAllowed(it) }?.let { pendingAsk.value = it }
        when (intent.action) {
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND, "com.blacklab.app.ASSIST" -> pendingRoute.value = "assistant?listen=true"
            // "Hey Google, play … on BlackLab": search the phone's music and start playing (shuffle when no query)
            android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                val q = intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty().trim().take(120).lowercase()
                val ctx = this
                lifecycleScope.launch(Dispatchers.Main) {
                    val all = withContext(Dispatchers.IO) { com.blacklab.app.media.Music.songs(ctx) }
                    val hits = if (q.isBlank()) emptyList() else all.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) }
                    val list = hits.ifEmpty { all.shuffled() }.take(100)
                    if (list.isNotEmpty()) runCatching { com.blacklab.app.media.Music.play(ctx, list, 0) }
                    pendingRoute.value = "nowplaying"
                }
            }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.takeIf { IncomingGuard.uriAllowed(this, it) }
                pendingShare.value = SharePayload(listOfNotNull(uri), intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT))
            }
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data?.takeIf { IncomingGuard.uriAllowed(this, it) }?.let { uri -> openExternal(uri, intent.type) }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf()).filter { IncomingGuard.uriAllowed(this, it) }
                if (uris.isNotEmpty()) pendingShare.value = SharePayload(uris, null, null)
            }
        }
    }

    /** Copies a file another app asked us to open into BlackLab's "opened" area, then shows it. */
    private fun openExternal(uri: Uri, type: String?) {
        val app = app
        app.appScope.launch {
            val mime = type ?: runCatching { contentResolver.getType(uri) }.getOrNull()
            var name = com.blacklab.app.core.Files.displayName(this@MainActivity, uri)
            if (!name.contains('.')) name += "." + com.blacklab.app.files.FileKinds.extFor(mime)
            val ext = name.substringAfterLast('.', "bin")
            // A new name per file, so opening two different "report.pdf"s doesn't overwrite the one on screen
            val dest = com.blacklab.app.core.Files.unique(app.storage.openedDir, name.substringBeforeLast('.'), ext)
            if (com.blacklab.app.core.Files.copyFromUri(this@MainActivity, uri, dest)) {
                pendingRoute.value = com.blacklab.app.files.FileKinds.route(dest, dest.absolutePath)
            }
        }
    }

    fun canAuthenticate(): Boolean =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    /** Biometric (or device PIN) prompt. Falls back to success when no lock screen is configured. */
    fun authenticate(title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        if (!canAuthenticate()) { onResult(true); return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }
}
