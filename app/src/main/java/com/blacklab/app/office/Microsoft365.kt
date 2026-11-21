package com.blacklab.app.office

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import com.blacklab.app.AppContainer
import com.blacklab.app.assistant.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Your personal Microsoft account (Outlook.com / Microsoft 365 Personal) through Microsoft Graph: OneDrive files
 * (recent, open, save changes back), Outlook mail and calendar, and To Do. Sign-in is OAuth 2.0 with PKCE in the
 * browser against the "BlackLab" app you registered in Microsoft Entra (Application ID in Office › Microsoft 365);
 * nothing but that ID is needed — there's no client secret. The refresh token is kept in the Keystore-encrypted
 * secrets. Work accounts managed by IT are never signed in here (Teams and work files open in Microsoft's apps).
 */
object Microsoft365 {
    private const val AUTHORITY = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private const val GRAPH = "https://graph.microsoft.com/v1.0"
    const val SCOPES = "openid profile offline_access User.Read Files.ReadWrite Mail.ReadWrite Calendars.ReadWrite Tasks.ReadWrite"
    private const val PREFS = "ms365"

    data class Account(val name: String, val email: String)
    data class State(val configured: Boolean = false, val account: Account? = null, val error: String? = null, val busy: Boolean = false)
    data class DriveItem(val id: String, val name: String, val size: Long, val modified: String, val webUrl: String, val folder: Boolean)
    data class Mail(val id: String, val from: String, val subject: String, val preview: String, val received: String, val unread: Boolean, val webLink: String)
    data class Event(val subject: String, val start: String, val end: String, val location: String, val joinUrl: String?)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var access: String? = null
    @Volatile private var accessUntil: Long = 0

    private val GUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    fun validClientId(id: String) = GUID.matches(id.trim())

    fun clientId(app: AppContainer): String? = app.secrets.get(Secrets.MS_CLIENT_ID)?.trim()?.takeIf { validClientId(it) }

    fun refresh(app: AppContainer) {
        val acct = app.secrets.get(Secrets.MS_ACCOUNT)?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { Account(it.optString("name"), it.optString("email")) }
        _state.value = State(configured = clientId(app) != null, account = acct?.takeIf { app.secrets.has(Secrets.MS_REFRESH) })
    }

    fun setClientId(app: AppContainer, id: String): Boolean {
        if (!validClientId(id)) return false
        app.secrets.put(Secrets.MS_CLIENT_ID, id.trim()); refresh(app); return true
    }

    /** base64 SHA-1 of the signing certificate — the "signature hash" in the Entra app's Android platform. */
    fun signatureHash(ctx: Context): String {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return ""
        return Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest(cert), Base64.NO_WRAP)
    }

    fun redirectUri(ctx: Context) = "msauth://${ctx.packageName}/" + URLEncoder.encode(signatureHash(ctx), "UTF-8")

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** Opens Microsoft's sign-in page; the answer comes back to MainActivity as an msauth:// link ([complete]). */
    fun signIn(app: AppContainer, ctx: Context): String? {
        val id = clientId(app) ?: return "Paste the Application (client) ID first."
        val verifier = b64url(ByteArray(48).also { SecureRandom().nextBytes(it) })
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val st = b64url(ByteArray(16).also { SecureRandom().nextBytes(it) })
        ctx.getSharedPreferences(PREFS, 0).edit().putString("verifier", verifier).putString("state", st).putLong("at", System.currentTimeMillis()).apply()
        val url = "$AUTHORITY/authorize?client_id=$id&response_type=code&response_mode=query&prompt=select_account" +
            "&redirect_uri=" + URLEncoder.encode(redirectUri(ctx), "UTF-8") + "&scope=" + URLEncoder.encode(SCOPES, "UTF-8") +
            "&code_challenge=$challenge&code_challenge_method=S256&state=$st"
        return runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null }.getOrElse { "No browser to sign in with." }
    }

    fun isRedirect(ctx: Context, uri: Uri?): Boolean = uri?.scheme == "msauth" && uri.host == ctx.packageName

    /** Finishes sign-in from the redirect: checks state, trades the code for tokens, reads your name and email. */
    suspend fun complete(app: AppContainer, uri: Uri): String = withContext(Dispatchers.IO) {
        val ctx = app.context
        val p = ctx.getSharedPreferences(PREFS, 0)
        val err = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
        if (err != null) return@withContext fail("Microsoft said: ${err.take(200)}")
        val code = uri.getQueryParameter("code") ?: return@withContext fail("No sign-in code came back.")
        if (uri.getQueryParameter("state") != p.getString("state", null) || System.currentTimeMillis() - p.getLong("at", 0) > 15 * 60_000)
            return@withContext fail("That sign-in link is stale or wasn't started here. Try Connect again.")
        val r = token(app, "grant_type=authorization_code&code=" + enc(code) + "&code_verifier=" + enc(p.getString("verifier", "")!!) + "&redirect_uri=" + enc(redirectUri(ctx)))
        p.edit().remove("verifier").remove("state").apply()
        if (r == null) return@withContext _state.value.error ?: "Sign-in failed."
        val me = runCatching { graph(app, "GET", "/me?\$select=displayName,mail,userPrincipalName") }.getOrNull()
        val acct = Account(me?.optString("displayName").orEmpty(), me?.optString("mail")?.ifBlank { null } ?: me?.optString("userPrincipalName").orEmpty())
        app.secrets.put(Secrets.MS_ACCOUNT, JSONObject().put("name", acct.name).put("email", acct.email).toString())
        refresh(app)
        "Connected ${acct.name.ifBlank { acct.email }}"
    }

    private fun fail(msg: String): String { _state.value = _state.value.copy(error = msg); return msg }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun token(app: AppContainer, grant: String): String? {
        val id = clientId(app) ?: return null
        val c = URL("$AUTHORITY/token").openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 20_000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(("client_id=$id&scope=" + enc(SCOPES) + "&" + grant).toByteArray()) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val j = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
            if (code !in 200..299) {
                val d = j.optString("error_description").substringBefore("\r").substringBefore(" Trace ID").take(240)
                if (j.optString("error") == "invalid_grant") { app.secrets.put(Secrets.MS_REFRESH, null); app.secrets.put(Secrets.MS_ACCOUNT, null) }
                fail(d.ifBlank { "Microsoft sign-in failed (HTTP $code)." }); return null
            }
            j.optString("refresh_token").takeIf { it.isNotBlank() }?.let { app.secrets.put(Secrets.MS_REFRESH, it) }
            access = j.optString("access_token"); accessUntil = System.currentTimeMillis() + (j.optLong("expires_in", 3600) - 120) * 1000
            _state.value = _state.value.copy(error = null)
            access
        } catch (e: Exception) { fail(com.blacklab.app.core.Net.message(com.blacklab.app.core.Net.classify(e), "Microsoft")); null } finally { c.disconnect() }
    }

    private fun accessToken(app: AppContainer): String? {
        access?.takeIf { System.currentTimeMillis() < accessUntil }?.let { return it }
        val rt = app.secrets.get(Secrets.MS_REFRESH) ?: return null
        return token(app, "grant_type=refresh_token&refresh_token=" + enc(rt))
    }

    fun signOut(app: AppContainer) {
        app.secrets.put(Secrets.MS_REFRESH, null); app.secrets.put(Secrets.MS_ACCOUNT, null)
        access = null; accessUntil = 0; refresh(app)
    }

    /** One Graph call; throws with Graph's own message on failure. */
    fun graph(app: AppContainer, method: String, path: String, body: JSONObject? = null): JSONObject {
        val t = accessToken(app) ?: throw IllegalStateException("Not connected to Microsoft")
        val c = URL(if (path.startsWith("http")) path else GRAPH + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 10_000; c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "Bearer $t")
            body?.let { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { o -> o.write(it.toString().toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException(runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull() ?: "HTTP $code")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { c.disconnect() }
    }

    private fun items(a: JSONArray?): List<DriveItem> = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }.map { j ->
        val ref = j.optJSONObject("remoteItem") ?: j
        DriveItem(ref.optString("id"), j.optString("name"), j.optLong("size"), j.optString("lastModifiedDateTime"), j.optString("webUrl"), j.has("folder"))
    }

    suspend fun recent(app: AppContainer): List<DriveItem> = withContext(Dispatchers.IO) { items(graph(app, "GET", "/me/drive/recent?\$top=25").optJSONArray("value")).filter { !it.folder } }
    suspend fun search(app: AppContainer, q: String): List<DriveItem> = withContext(Dispatchers.IO) {
        items(graph(app, "GET", "/me/drive/root/search(q='" + q.replace("'", "''").let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } + "')?\$top=25").optJSONArray("value")).filter { !it.folder }
    }

    /** Downloads [item] into [dir] and remembers where it came from, so changes can go back to OneDrive. */
    suspend fun download(app: AppContainer, item: DriveItem, dir: File): File = withContext(Dispatchers.IO) {
        val t = accessToken(app) ?: error("Not connected to Microsoft")
        dir.mkdirs()
        val out = File(dir, com.blacklab.app.core.Files.sanitize(item.name.substringBeforeLast('.')) + "." + item.name.substringAfterLast('.', "bin"))
        val c = URL("$GRAPH/me/drive/items/${item.id}/content").openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("Authorization", "Bearer $t"); c.instanceFollowRedirects = true; c.connectTimeout = 10_000; c.readTimeout = 120_000
            if (c.responseCode !in 200..299) error("OneDrive download failed (HTTP ${c.responseCode})")
            com.blacklab.app.files.FileOps.writeAtomically(out) { tmp -> c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } } }
        } finally { c.disconnect() }
        links(app.context).edit().putString(out.absolutePath, JSONObject().put("id", item.id).put("name", item.name).put("at", out.lastModified()).toString()).apply()
        out
    }

    private fun links(ctx: Context) = ctx.getSharedPreferences("ms365_links", 0)

    /** The OneDrive file a local file came from, if it was opened from OneDrive. */
    fun linkOf(ctx: Context, f: File): JSONObject? = links(ctx).getString(f.absolutePath, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun changedSinceDownload(ctx: Context, f: File): Boolean = linkOf(ctx, f)?.let { f.lastModified() > it.optLong("at") + 1000 } ?: false

    /** Saves [f] back over its OneDrive original (upload session, so large decks and workbooks work too). */
    suspend fun upload(app: AppContainer, f: File): String = withContext(Dispatchers.IO) {
        val link = linkOf(app.context, f) ?: error("This file didn't come from OneDrive.")
        val session = graph(app, "POST", "/me/drive/items/${link.optString("id")}/createUploadSession", JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "replace")))
        val up = session.optString("uploadUrl").ifBlank { error("OneDrive didn't accept the upload.") }
        val total = f.length(); val chunk = 5L * 1024 * 1024 // multiple of 320 KiB as Graph requires
        f.inputStream().use { input ->
            var start = 0L
            val buf = ByteArray(chunk.toInt())
            while (start < total) {
                val n = input.read(buf, 0, minOf(chunk, total - start).toInt()).takeIf { it > 0 } ?: break
                val c = URL(up).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "PUT"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 120_000
                    c.setRequestProperty("Content-Range", "bytes $start-${start + n - 1}/$total")
                    c.setFixedLengthStreamingMode(n)
                    c.outputStream.use { it.write(buf, 0, n) }
                    if (c.responseCode !in 200..299) error("OneDrive upload failed (HTTP ${c.responseCode})")
                } finally { c.disconnect() }
                start += n
            }
        }
        links(app.context).edit().putString(f.absolutePath, link.put("at", f.lastModified()).toString()).apply()
        "Saved ${link.optString("name")} to OneDrive"
    }

    suspend fun inbox(app: AppContainer, top: Int = 8): List<Mail> = withContext(Dispatchers.IO) {
        val a = graph(app, "GET", "/me/mailFolders/inbox/messages?\$top=$top&\$select=subject,from,bodyPreview,receivedDateTime,isRead,webLink&\$orderby=receivedDateTime%20desc").optJSONArray("value") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it) }.map { j ->
            Mail(j.optString("id"), j.optJSONObject("from")?.optJSONObject("emailAddress")?.optString("name").orEmpty(), j.optString("subject"), j.optString("bodyPreview").take(160),
                j.optString("receivedDateTime"), !j.optBoolean("isRead"), j.optString("webLink"))
        }
    }

    suspend fun events(app: AppContainer, days: Long = 7): List<Event> = withContext(Dispatchers.IO) {
        val now = java.time.OffsetDateTime.now(); val end = now.plusDays(days)
        val a = graph(app, "GET", "/me/calendarView?startDateTime=${enc(now.toString())}&endDateTime=${enc(end.toString())}&\$top=25&\$orderby=start/dateTime&\$select=subject,start,end,location,onlineMeeting,bodyPreview").optJSONArray("value") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it) }.map { j ->
            Event(j.optString("subject"), j.optJSONObject("start")?.optString("dateTime").orEmpty(), j.optJSONObject("end")?.optString("dateTime").orEmpty(),
                j.optJSONObject("location")?.optString("displayName").orEmpty(), j.optJSONObject("onlineMeeting")?.optString("joinUrl")?.ifBlank { null } ?: MicrosoftApps.teamsLink(j.optString("bodyPreview")))
        }
    }

    /** Adds a task to your default Microsoft To Do list. */
    suspend fun addTodo(app: AppContainer, title: String): String = withContext(Dispatchers.IO) {
        val lists = graph(app, "GET", "/me/todo/lists").optJSONArray("value") ?: JSONArray()
        val list = (0 until lists.length()).map { lists.getJSONObject(it) }.let { l -> l.firstOrNull { it.optString("wellknownListName") == "defaultList" } ?: l.firstOrNull() } ?: error("No To Do list found.")
        graph(app, "POST", "/me/todo/lists/${list.optString("id")}/tasks", JSONObject().put("title", title.take(255)))
        "Added to Microsoft To Do: $title"
    }
}
