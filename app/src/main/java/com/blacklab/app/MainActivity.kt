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
import com.blacklab.app.ui.AppRoot
import com.blacklab.app.ui.theme.BlackLabTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : FragmentActivity() {

    companion object {
        const val EXTRA_ROUTE = "route"
        /** Route requested by a notification / shortcut, consumed by the nav host. */
        val pendingRoute = MutableStateFlow<String?>(null)
        /** Files or text shared into BlackLab from other apps. */
        val pendingShare = MutableStateFlow<SharePayload?>(null)
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
            val dark = when (settings.themeMode) { "LIGHT" -> false; "SYSTEM" -> isSystemInDarkTheme(); else -> true }
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                )
            }
            BlackLabTheme(settings.themeMode, settings.dynamicColor, settings.activeProfile) {
                AppRoot(activity = this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                pendingShare.value = SharePayload(listOfNotNull(uri), intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf()
                pendingShare.value = SharePayload(uris, null, null)
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
