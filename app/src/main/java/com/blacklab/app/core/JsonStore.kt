package com.blacklab.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One JSON document on disk, for the parts of BlackLab that keep their data outside Room (Facility,
 * Garage, drive telemetry, recalls…). Every store gets the same guarantees:
 *
 *  - **Crash-safe writes**: the new copy is written to a temp file, flushed to disk, then moved into place.
 *    The previous good copy is kept as `name.json.bak`, so a write cut short by a crash or a full disk
 *    never leaves you with nothing.
 *  - **Damaged files are never overwritten**: if the file can't be parsed it is moved aside as
 *    `name.unreadable-<time>.json`, the `.bak` copy is used instead, and [problem] says what happened
 *    (Settings › Health shows it). Before, a damaged file read as "empty" and the next save replaced it.
 *  - **Schema versions and migrations**: the file carries `"_schema": n`. Older files are upgraded one step
 *    at a time through [migrations] (`from n` → n+1) before decoding. A file from a *newer* BlackLab is
 *    loaded but never written back, so fields this version doesn't know about aren't lost.
 *
 * The store is context-free (takes a [File]) so the logic is unit tested on the JVM.
 */
class JsonStore<T>(
    val name: String,
    val version: Int,
    private val empty: () -> T,
    private val decode: (JSONObject) -> T,
    private val encode: (T) -> JSONObject,
    private val migrations: Map<Int, (JSONObject) -> JSONObject> = emptyMap(),
) {
    private val _state = MutableStateFlow(empty())
    val state: StateFlow<T> = _state.asStateFlow()

    @Volatile private var loadedFrom: File? = null

    /** What went wrong the last time the file was read or written, in plain words; null when all is well. */
    @Volatile var problem: String? = null
        private set

    /** True when the file came from a newer BlackLab: changes stay in memory and the file is left alone. */
    @Volatile var readOnly: Boolean = false
        private set

    val value: T get() = _state.value

    fun load(file: File): T {
        if (loadedFrom == file) return _state.value
        synchronized(this) {
            if (loadedFrom == file) return _state.value
            val r = read(file, version, migrations)
            problem = r.problem
            readOnly = r.storedVersion > version
            _state.value = r.json?.let { j -> runCatching { decode(j) }.getOrElse { e ->
                problem = "$name couldn't be read (${e.javaClass.simpleName}); started empty. The file was kept."
                quarantine(file)
                empty()
            } } ?: empty()
            loadedFrom = file
        }
        return _state.value
    }

    /** Forgets the in-memory copy and reads the file again (after a restore, and in tests). */
    fun reload(file: File): T {
        synchronized(this) { loadedFrom = null }
        return load(file)
    }

    /** Applies [change], publishes the result and (unless [persist] is false) writes it to disk. */
    fun update(file: File, persist: Boolean = true, change: (T) -> T): T = synchronized(this) {
        val next = change(load(file))
        _state.value = next
        if (persist) writeNow(file)
        next
    }

    /** Writes the current state to disk now (stores that batch frequent changes call this themselves). */
    fun writeNow(file: File): Boolean = synchronized(this) {
        if (readOnly) return false
        val ok = runCatching { write(file, encode(_state.value).put(SCHEMA_KEY, version)) }
        problem = ok.exceptionOrNull()?.let { "$name couldn't be saved: ${it.message ?: it.javaClass.simpleName}" }
        ok.isSuccess
    }

    data class Read(val json: JSONObject?, val storedVersion: Int, val problem: String?)

    companion object {
        const val SCHEMA_KEY = "_schema"

        fun backupOf(file: File) = File(file.parentFile, file.name + ".bak")

        /** Reads [file] (or its `.bak` when the file is missing or damaged) and migrates it up to [version]. */
        fun read(file: File, version: Int, migrations: Map<Int, (JSONObject) -> JSONObject>): Read {
            val bak = backupOf(file)
            var problem: String? = null
            var json = parse(file)
            if (json == null && file.exists()) {
                val kept = quarantine(file)
                json = parse(bak)
                problem = if (json != null) "${file.name} was damaged, so the last good copy was used. The damaged file was kept as ${kept?.name}."
                else "${file.name} was damaged and there was no good copy; started empty. The damaged file was kept as ${kept?.name}."
            } else if (json == null && bak.exists()) {
                // A crash between the two moves in write(): the backup is the latest good copy
                json = parse(bak)
            }
            if (json == null) return Read(null, version, problem)
            val stored = json.optInt(SCHEMA_KEY, 1)
            var j: JSONObject = json
            var v = stored
            while (v < version) {
                val step = migrations[v]
                if (step != null) j = runCatching { step(j) }.getOrElse { e ->
                    return Read(j, v, "${file.name} couldn't be upgraded from version $v (${e.message}); loaded as-is.")
                }
                v++
            }
            if (stored > version) problem = "${file.name} was saved by a newer BlackLab (format $stored). It's shown but won't be changed until you update."
            return Read(j, stored, problem)
        }

        private fun parse(f: File): JSONObject? =
            if (!f.isFile) null else runCatching { JSONObject(f.readText()) }.getOrNull()

        /** Moves an unreadable file aside (keeps the 3 newest such files). */
        fun quarantine(file: File): File? {
            if (!file.exists()) return null
            val dir = file.parentFile ?: return null
            val base = file.name.removeSuffix(".json")
            val kept = File(dir, "$base.unreadable-${System.currentTimeMillis()}.json")
            if (!file.renameTo(kept)) return null
            dir.listFiles { f -> f.name.startsWith("$base.unreadable-") }?.sortedByDescending { it.name }?.drop(3)?.forEach { it.delete() }
            return kept
        }

        /** Temp file + fsync, then current → `.bak` and temp → current, each an atomic move. */
        fun write(file: File, json: JSONObject) {
            val dir = file.parentFile ?: error("No folder for ${file.name}")
            dir.mkdirs()
            val tmp = File(dir, ".${file.name}.${System.nanoTime()}.tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(json.toString().toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
                if (file.exists() && parse(file) != null) move(file, backupOf(file))
                move(tmp, file)
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }

        private fun move(from: File, to: File) {
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
