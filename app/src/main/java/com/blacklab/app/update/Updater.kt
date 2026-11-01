package com.blacklab.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blacklab.app.BuildConfig
import com.blacklab.app.R
import com.blacklab.app.app
import com.blacklab.app.core.Keys
import com.blacklab.app.reminders.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Safe self-updates from BlackLab's GitHub Releases.
 *
 * An update is only offered for install after three independent checks pass:
 *  1. the downloaded APK's SHA-256 equals the checksum published next to it in the release,
 *  2. the APK is signed by the same certificate as the installed app (a tampered or
 *     re-signed APK fails here even if someone also replaced the checksum), and
 *  3. it is the same package with a higher version code.
 * Android's installer then repeats the signature check itself before replacing the app.
 */
object Updater {
    const val REPO = "paulmmoore3416/BlackLab"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val WORK = "blacklab-update-check"
    const val CH_UPDATES = "updates"
    private const val NOTIFY_ID = 7_301

    // ----------------------------------------------------------------- pure helpers (unit tested)

    data class Release(
        val version: String,
        val tag: String,
        val name: String,
        val notes: String,
        val publishedAt: String,
        val pageUrl: String,
        val apkUrl: String,
        val apkSize: Long,
        val shaUrl: String?,
    )

    /** "v4.3.0" / "4.3.0-debug" → [4, 3, 0]. Non-numeric parts are ignored. */
    fun versionParts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** Positive when [a] is newer than [b]. */
    fun compareVersions(a: String, b: String): Int {
        val x = versionParts(a); val y = versionParts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** Parses the GitHub "latest release" JSON. Picks the BlackLab-vX.Y.Z.apk asset and its .sha256. */
    fun parseRelease(json: String): Release? = runCatching {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.getString("tag_name")
        val assets = o.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        var sha: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.matches(Regex("BlackLab-v[0-9][0-9A-Za-z.\\-]*\\.apk"))) apk = a
            if (name.matches(Regex("BlackLab-v[0-9][0-9A-Za-z.\\-]*\\.apk\\.sha256"))) sha = a
        }
        val a = apk ?: return null
        Release(
            version = tag.removePrefix("v"), tag = tag, name = o.optString("name", tag),
            notes = o.optString("body").trim(), publishedAt = o.optString("published_at"), pageUrl = o.optString("html_url"),
            apkUrl = a.getString("browser_download_url"), apkSize = a.optLong("size"), shaUrl = sha?.optString("browser_download_url"),
        )
    }.getOrNull()

    /** First token of a `sha256sum` line, if it is a 64-character hex digest. */
    fun parseSha256(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

    /** Downloads are only accepted over HTTPS from GitHub and its release-asset CDN. */
    fun isAllowedUrl(url: String): Boolean = runCatching {
        val u = URL(url)
        val h = u.host.lowercase()
        u.protocol == "https" && (h == "github.com" || h == "api.github.com" || h.endsWith(".githubusercontent.com"))
    }.getOrDefault(false)

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    fun certFingerprint(sig: Signature): String = hex(MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()))

    /** "AB:CD:…" short form for display. */
    fun prettyFingerprint(fp: String, groups: Int = 8) = fp.uppercase().chunked(2).take(groups).joinToString(":") + if (fp.length > groups * 2) "…" else ""

    // ----------------------------------------------------------------- state

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val latest: String) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val done: Long, val total: Long) : State
        data class Verified(val release: Release, val apk: File, val checks: Checks) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    data class Checks(val sha256: String, val shaMatches: Boolean, val signer: String, val signerMatches: Boolean, val packageMatches: Boolean, val newer: Boolean) {
        val ok get() = shaMatches && signerMatches && packageMatches && newer
    }

    val state = MutableStateFlow<State>(State.Idle)

    // ----------------------------------------------------------------- network

    private fun open(url: String): HttpURLConnection {
        require(isAllowedUrl(url)) { "Refusing to download from $url" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BlackLab-Updater/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream;q=0.9, */*;q=0.5")
        }
    }

    private fun HttpURLConnection.checkedStream(): java.io.InputStream {
        if (responseCode !in 200..299) throw IOException("GitHub answered $responseCode")
        // Redirects must stay on GitHub's hosts too
        if (!isAllowedUrl(url.toString())) throw IOException("Unexpected download host ${url.host}")
        return inputStream
    }

    suspend fun fetchLatest(): Release = withContext(Dispatchers.IO) {
        val c = open(API)
        try {
            val body = c.checkedStream().bufferedReader().use { it.readText() }
            parseRelease(body) ?: throw IOException("The latest release has no BlackLab APK attached")
        } finally { c.disconnect() }
    }

    /** Checks GitHub. Returns the release when it is newer than this build and not skipped. */
    suspend fun check(context: Context, manual: Boolean = true): Release? {
        val app = context.app
        state.value = State.Checking
        return try {
            val r = fetchLatest()
            app.settings.update { putLong(Keys.UPDATE_LAST_CHECK, System.currentTimeMillis()) }
            val newer = compareVersions(r.version, BuildConfig.VERSION_NAME) > 0
            val skipped = !manual && app.settings.current.updateSkip == r.version
            if (newer && !skipped) { state.value = State.Available(r); r } else { state.value = State.UpToDate(r.version); null }
        } catch (e: Exception) {
            state.value = State.Failed(e.message ?: "Couldn't reach GitHub")
            null
        }
    }

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates").also { it.mkdirs() }

    /** Downloads the checksum and the APK, hashing while writing, then runs every check. */
    suspend fun download(context: Context, r: Release): State = withContext(Dispatchers.IO) {
        try {
            val shaUrl = r.shaUrl ?: throw IOException("The release has no .sha256 checksum, so it can't be verified")
            val expected = open(shaUrl).let { c -> try { parseSha256(c.checkedStream().bufferedReader().use { it.readText() }) } finally { c.disconnect() } }
                ?: throw IOException("The published checksum is unreadable")
            val dir = updatesDir(context)
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "BlackLab-v${r.version}.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            val c = open(r.apkUrl)
            try {
                val total = c.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
                c.checkedStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            output.write(buf, 0, n); digest.update(buf, 0, n); done += n
                            if (done - last > 256 * 1024) { last = done; state.value = State.Downloading(r, done, total) }
                        }
                    }
                }
            } finally { c.disconnect() }
            val actual = hex(digest.digest())
            val checks = verify(context, out, expected, actual)
            val result = if (checks.ok) State.Verified(r, out, checks) else {
                out.delete()
                State.Failed(when {
                    !checks.shaMatches -> "Checksum mismatch — the download doesn't match the published SHA-256. Nothing was installed."
                    !checks.packageMatches -> "That file isn't BlackLab. Nothing was installed."
                    !checks.signerMatches -> "Signature mismatch — the APK isn't signed with BlackLab's key. Nothing was installed."
                    else -> "That release isn't newer than the installed version."
                }, r)
            }
            state.value = result
            result
        } catch (e: Exception) {
            State.Failed(e.message ?: "Download failed", r).also { state.value = it }
        }
    }

    /** Signature, package and version checks against the installed app. */
    fun verify(context: Context, apk: File, expectedSha: String, actualSha: String = hex(sha256(apk))): Checks {
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archiveSigners = archive?.signingInfo?.let { si ->
            (si.apkContentsSigners.orEmpty().toList() + if (si.hasMultipleSigners()) emptyList() else si.signingCertificateHistory.orEmpty().toList()).map(::certFingerprint).toSet()
        } ?: emptySet()
        val installedSigners = installed.signingInfo?.apkContentsSigners.orEmpty().map(::certFingerprint).toSet()
        val currentSigner = archive?.signingInfo?.apkContentsSigners?.firstOrNull()?.let(::certFingerprint).orEmpty()
        return Checks(
            sha256 = actualSha,
            shaMatches = actualSha.equals(expectedSha, ignoreCase = true),
            signer = currentSigner,
            signerMatches = installedSigners.isNotEmpty() && archiveSigners.containsAll(installedSigners),
            packageMatches = archive?.packageName == context.packageName,
            newer = (archive?.longVersionCode ?: 0) > installed.longVersionCode,
        )
    }

    fun sha256(f: File): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) } }
        return d.digest()
    }

    fun installedSigner(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            .signingInfo?.apkContentsSigners?.firstOrNull()?.let(::certFingerprint).orEmpty()
    }.getOrDefault("")

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    // ----------------------------------------------------------------- install

    /** Hands a verified APK to Android's package installer. */
    suspend fun install(context: Context, v: State.Verified) = withContext(Dispatchers.IO) {
        // Re-hash right before installing so the file can't have changed since it was verified
        val again = verify(context, v.apk, v.checks.sha256)
        if (!again.ok) { state.value = State.Failed("The downloaded file changed after it was checked. Nothing was installed.", v.release); return@withContext }
        state.value = State.Installing(v.release)
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(v.apk.length())
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, v.apk.length()).use { out -> v.apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                val status = Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_STATUS).setPackage(context.packageName)
                val pi = PendingIntent.getBroadcast(context, id, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            state.value = State.Failed("The installer refused: ${e.message}", v.release)
        }
    }

    // ----------------------------------------------------------------- background check

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!context.app.settings.current.updateAuto || BuildConfig.DEBUG) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun notifyAvailable(context: Context, r: Release) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(context, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("BlackLab ${r.version} is available")
            .setContentText("Tap to see what's new. It installs only after the checksum and signature check out.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Tap to see what's new. It installs only after the checksum and signature check out."))
            .setColor(0xFF2BD9A8.toInt())
            .setAutoCancel(true)
            .setContentIntent(Notifications.openAppIntent(context, "updates", NOTIFY_ID))
            .build()
        try { nm.notify(NOTIFY_ID, n) } catch (_: SecurityException) {}
    }

    fun isForeground() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!applicationContext.app.settings.current.updateAuto) return Result.success()
        val r = Updater.check(applicationContext, manual = false)
        if (r != null) Updater.notifyAvailable(applicationContext, r)
        return Result.success()
    }
}

/** Receives the package installer's status for a self-update session. */
class UpdateReceiver : BroadcastReceiver() {
    companion object { const val ACTION_STATUS = "com.blacklab.app.UPDATE_STATUS" }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val current = Updater.state.value
        val release = (current as? Updater.State.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Updater.isForeground()) runCatching { context.startActivity(confirm) }
                else {
                    // Can't pop a dialog from the background; leave a notification that opens the confirm screen
                    val pi = PendingIntent.getActivity(context, 7_302, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val n = NotificationCompat.Builder(context, Updater.CH_UPDATES).setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("Finish updating BlackLab").setContentText("Tap to confirm the install").setContentIntent(pi).setAutoCancel(true).build()
                    try { NotificationManagerCompat.from(context).notify(7_302, n) } catch (_: SecurityException) {}
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.state.value = Updater.State.Idle // the app is replaced and restarted
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed"
                Updater.state.value = Updater.State.Failed(
                    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Install cancelled." else "Install failed: $msg", release,
                )
            }
        }
    }
}
