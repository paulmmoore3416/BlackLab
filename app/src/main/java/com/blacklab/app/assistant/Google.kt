package com.blacklab.app.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.blacklab.app.AppContainer
import com.blacklab.app.core.Keys
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

internal data class HttpResult(val code: Int, val body: String) {
    val ok get() = code in 200..299
    fun json(): JSONObject = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
}

internal fun http(
    method: String,
    url: String,
    token: String? = null,
    body: ByteArray? = null,
    contentType: String = "application/json; charset=UTF-8",
    headers: Map<String, String> = emptyMap(),
): HttpResult {
    val conn = URL(url).openConnection() as HttpURLConnection
    return try {
        if (method == "PATCH") { conn.requestMethod = "POST"; conn.setRequestProperty("X-HTTP-Method-Override", "PATCH") } else conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        HttpResult(code, text)
    } catch (e: Exception) {
        HttpResult(-1, e.message ?: "network error")
    } finally {
        conn.disconnect()
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/**
 * Google sign-in for Gmail and Drive. Google APIs that touch personal data (Gmail, Drive) only
 * accept OAuth access tokens; API keys are rejected. Authorization uses Google Play services'
 * AuthorizationClient, which needs an "Android" OAuth client for com.blacklab.app + the
 * release signing SHA-1 in the same Cloud project as the enabled APIs.
 */
object GoogleAuth {
    val scopes = listOf(
        "https://www.googleapis.com/auth/gmail.modify",
        "https://www.googleapis.com/auth/drive.readonly",
        "https://www.googleapis.com/auth/drive.file",
        "https://www.googleapis.com/auth/drive.appdata",
        "email",
    )

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(scopes.map { Scope(it) }).build()

    /** Interactive: returns a result that may need the consent UI (hasResolution). */
    suspend fun authorize(activity: Activity): Result<AuthorizationResult> = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(activity).authorize(request())
            .addOnSuccessListener { cont.resume(Result.success(it)) }
            .addOnFailureListener { cont.resume(Result.failure(it)) }
    }

    fun resultFromIntent(context: Context, data: Intent?): AuthorizationResult? =
        runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data) }.getOrNull()

    /** Silent token for already-granted scopes; null when the user hasn't signed in yet. */
    suspend fun token(context: Context): String? = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context.applicationContext).authorize(request())
            .addOnSuccessListener { r -> cont.resume(if (r.hasResolution()) null else r.accessToken) }
            .addOnFailureListener { cont.resume(null) }
    }

    suspend fun rememberAccount(app: AppContainer, token: String) {
        val email = withContext(Dispatchers.IO) {
            http("GET", "https://gmail.googleapis.com/gmail/v1/users/me/profile", token).json().optString("emailAddress")
        }
        if (email.isNotBlank()) app.settings.update { putString(Keys.GOOGLE_ACCOUNT, email) }
    }

    fun signOut(app: AppContainer) = app.settings.update { putString(Keys.GOOGLE_ACCOUNT, ""); putBoolean(Keys.CLOUD_SYNC, false) }
}

object Gmail {
    data class Mail(val id: String, val from: String, val subject: String, val snippet: String, val date: String)

    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

    suspend fun search(token: String, query: String, max: Int): Result<List<Mail>> = withContext(Dispatchers.IO) {
        val list = http("GET", "$BASE/messages?maxResults=$max&q=${enc(query)}", token)
        if (!list.ok) return@withContext Result.failure(Exception(errorText(list)))
        val ids = list.json().optJSONArray("messages") ?: JSONArray()
        val mails = (0 until ids.length()).mapNotNull { i ->
            val id = ids.getJSONObject(i).getString("id")
            val m = http("GET", "$BASE/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date", token)
            if (!m.ok) return@mapNotNull null
            val j = m.json()
            val headers = j.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
            fun h(name: String) = (0 until headers.length()).map { headers.getJSONObject(it) }.firstOrNull { it.optString("name").equals(name, true) }?.optString("value").orEmpty()
            Mail(id, h("From").substringBefore(" <").trim('"'), h("Subject"), android.text.Html.fromHtml(j.optString("snippet"), 0).toString(), h("Date"))
        }
        Result.success(mails)
    }

    private fun raw(to: String, subject: String, body: String): String {
        val mime = "To: $to\r\nSubject: $subject\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n$body"
        return Base64.encodeToString(mime.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    }

    suspend fun send(token: String, to: String, subject: String, body: String): Result<Unit> = withContext(Dispatchers.IO) {
        val r = http("POST", "$BASE/messages/send", token, JSONObject().put("raw", raw(to, subject, body)).toString().toByteArray())
        if (r.ok) Result.success(Unit) else Result.failure(Exception(errorText(r)))
    }

    suspend fun draft(token: String, to: String, subject: String, body: String): Result<Unit> = withContext(Dispatchers.IO) {
        val r = http("POST", "$BASE/drafts", token, JSONObject().put("message", JSONObject().put("raw", raw(to, subject, body))).toString().toByteArray())
        if (r.ok) Result.success(Unit) else Result.failure(Exception(errorText(r)))
    }
}

object Drive {
    data class DFile(val id: String, val name: String, val mime: String, val modified: String, val link: String)

    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"

    suspend fun search(token: String, text: String, max: Int = 10): Result<List<DFile>> = withContext(Dispatchers.IO) {
        val q = "(name contains '${text.replace("'", "\\'")}' or fullText contains '${text.replace("'", "\\'")}') and trashed = false"
        val r = http("GET", "$API/files?pageSize=$max&orderBy=modifiedTime desc&fields=${enc("files(id,name,mimeType,modifiedTime,webViewLink)")}&q=${enc(q)}", token)
        if (!r.ok) return@withContext Result.failure(Exception(errorText(r)))
        val arr = r.json().optJSONArray("files") ?: JSONArray()
        Result.success((0 until arr.length()).map { arr.getJSONObject(it) }.map {
            DFile(it.getString("id"), it.optString("name"), it.optString("mimeType"), it.optString("modifiedTime"), it.optString("webViewLink"))
        })
    }

    private fun folderId(token: String, name: String): String? {
        val q = "mimeType = 'application/vnd.google-apps.folder' and name = '${name.replace("'", "\\'")}' and trashed = false"
        val found = http("GET", "$API/files?fields=files(id)&q=${enc(q)}", token).json().optJSONArray("files")
        if (found != null && found.length() > 0) return found.getJSONObject(0).getString("id")
        val created = http("POST", "$API/files?fields=id", token,
            JSONObject().put("name", name).put("mimeType", "application/vnd.google-apps.folder").toString().toByteArray())
        return created.json().optString("id").ifBlank { null }
    }

    suspend fun upload(token: String, file: File, folderName: String, mime: String): Result<DFile> = withContext(Dispatchers.IO) {
        val parent = folderId(token, folderName.ifBlank { "BlackLab" })
        val meta = JSONObject().put("name", file.name).apply { parent?.let { put("parents", JSONArray().put(it)) } }
        val r = multipart("POST", "$UPLOAD/files?uploadType=multipart&fields=id,name,mimeType,modifiedTime,webViewLink", token, meta, file.readBytes(), mime)
        if (!r.ok) return@withContext Result.failure(Exception(errorText(r)))
        val j = r.json()
        Result.success(DFile(j.optString("id"), j.optString("name"), j.optString("mimeType"), j.optString("modifiedTime"), j.optString("webViewLink")))
    }

    private fun multipart(method: String, url: String, token: String, meta: JSONObject, data: ByteArray, mime: String): HttpResult {
        val boundary = "blacklab" + System.nanoTime()
        val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: $mime\r\n\r\n"
        val body = head.toByteArray() + data + "\r\n--$boundary--".toByteArray()
        return http(method, url, token, body, "multipart/related; boundary=$boundary")
    }

    // -------------------------------------------------------------- hidden app-data folder (sync)

    fun appDataFind(token: String, name: String): String? {
        val r = http("GET", "$API/files?spaces=appDataFolder&fields=files(id,name)&q=${enc("name = '$name'")}", token)
        val arr = r.json().optJSONArray("files") ?: return null
        return if (arr.length() > 0) arr.getJSONObject(0).getString("id") else null
    }

    fun appDataRead(token: String, name: String): String? {
        val id = appDataFind(token, name) ?: return null
        val r = http("GET", "$API/files/$id?alt=media", token)
        return if (r.ok) r.body else null
    }

    fun appDataWrite(token: String, name: String, json: String): Boolean {
        val id = appDataFind(token, name)
        val r = if (id == null) {
            multipart("POST", "$UPLOAD/files?uploadType=multipart", token, JSONObject().put("name", name).put("parents", JSONArray().put("appDataFolder")),
                json.toByteArray(), "application/json")
        } else http("PATCH", "$UPLOAD/files/$id?uploadType=media", token, json.toByteArray())
        return r.ok
    }
}

/** Google Developer Knowledge API: search Android / Firebase / Cloud developer documentation. */
object DevDocs {
    data class Hit(val title: String, val url: String, val text: String)

    suspend fun search(key: String, query: String, max: Int = 3): Result<List<Hit>> = withContext(Dispatchers.IO) {
        val r = http("GET", "https://developerknowledge.googleapis.com/v1/documents:searchDocumentChunks?query=${enc(query)}&key=${enc(key)}")
        if (!r.ok) return@withContext Result.failure(Exception(errorText(r)))
        val arr = r.json().optJSONArray("results") ?: JSONArray()
        Result.success((0 until minOf(max, arr.length())).map { arr.getJSONObject(it) }.map { j ->
            val parent = j.optString("parent").removePrefix("documents/")
            val text = j.optString("content").replace(Regex("\\s+"), " ").trim()
            Hit(parent.substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.uppercase() }, "https://$parent", text)
        })
    }
}

/** Google Keep has no consumer API (the Keep API is Workspace-only), so notes go through the Keep app. */
object Keep {
    const val PACKAGE = "com.google.android.keep"

    fun installed(context: Context) = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    fun intent(title: String, text: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        setPackage(PACKAGE)
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TITLE, title)
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

internal fun errorText(r: HttpResult): String = when (r.code) {
    -1 -> "No connection (${r.body})"
    401 -> "Google sign-in expired — sign in again in Settings › Assistant."
    403 -> runCatching { JSONObject(r.body).getJSONObject("error").getString("message") }.getOrDefault("Access denied (403)")
    else -> runCatching { JSONObject(r.body).getJSONObject("error").getString("message") }.getOrDefault("HTTP ${r.code}")
}
