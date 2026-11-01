package com.blacklab.app.core

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Checks what other apps hand to BlackLab's exported entry points (open with, share, shortcuts).
 * Without it, any installed app could make BlackLab copy its own private files (database, keys,
 * notes) into shared storage, open them in an editor, or run assistant commands.
 */
object IncomingGuard {
    /** The only assistant request an outside intent may start: the "Brief me" home-screen shortcut. */
    private val SAFE_ASKS = setOf("brief me")

    fun askAllowed(text: String): Boolean = text.trim().lowercase() in SAFE_ASKS

    /** Screen routes are fine to deep-link; file viewers and editors (which take a path) are not. */
    fun routeAllowed(route: String): Boolean {
        val r = route.trim()
        if (r.isEmpty() || r.length > 200) return false
        if (r.contains("path=", ignoreCase = true) || r.contains("://") || r.contains("..")) return false
        return r.all { it.isLetterOrDigit() || it in "/?=&_-.,%" }
    }

    /** True when [path] is inside one of [privateRoots] (both already canonical). */
    fun isInside(path: String, privateRoots: List<String>): Boolean =
        privateRoots.any { root -> path == root || path.startsWith(root.trimEnd('/') + "/") }

    fun privateRoots(context: Context): List<String> = listOfNotNull(
        context.dataDir,
        File("/data/data/${context.packageName}"),
        File("/data/user/0/${context.packageName}"),
        File("/data/user_de/0/${context.packageName}"),
        context.getExternalFilesDir(null)?.parentFile,
        context.externalCacheDir?.parentFile,
    ).map { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }.distinct()

    /** Whether a URI another app sent may be read and copied. */
    fun uriAllowed(context: Context, uri: Uri): Boolean = when (uri.scheme?.lowercase()) {
        "content" -> {
            val authority = uri.authority.orEmpty()
            val owner = runCatching { context.packageManager.resolveContentProvider(authority, 0)?.packageName }.getOrNull()
            authority.isNotEmpty() && owner != context.packageName && !authority.startsWith(context.packageName + ".")
        }
        "file" -> {
            val p = uri.path
            if (p.isNullOrBlank()) false
            else {
                val canon = runCatching { File(p).canonicalPath }.getOrNull()
                canon != null && !isInside(canon, privateRoots(context))
            }
        }
        else -> false
    }
}
