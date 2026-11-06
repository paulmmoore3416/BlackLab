package com.blacklab.app.files

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Folders and sorting for the Library (Photos, Videos, Scans, Audio, Exports, Imports): create,
 * rename, move, copy and delete folders and files, auto-sort into month folders, and the sort /
 * group orders the screens use. File-only and side-effect free apart from the disk (unit tested);
 * database fix-ups for moved recordings happen in the caller.
 */
object LibraryOps {

    enum class Sort(val label: String) {
        NEWEST("Newest first"), OLDEST("Oldest first"), NAME_AZ("Name A–Z"), NAME_ZA("Name Z–A"), LARGEST("Largest first"), SMALLEST("Smallest first"), TYPE("Type");
        companion object { fun of(n: String?) = entries.firstOrNull { it.name == n } ?: NEWEST }
    }

    enum class Group(val label: String) {
        MONTH("By month"), DAY("By day"), TYPE("By type"), NONE("No grouping");
        companion object { fun of(n: String?) = entries.firstOrNull { it.name == n } ?: MONTH }
    }

    /** Files that travel with a file when it moves (transcripts, metadata) — same base name. */
    private val SIDECARS = setOf("txt", "json", "srt", "vtt", "xmp")

    private val illegal = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    fun cleanName(name: String): String = name.replace(illegal, " ").replace(Regex("\\s+"), " ").trim().trim('.').take(80)

    /** [dir]/[name], or "[name] (2)", "(3)"… when taken. */
    fun uniqueChild(dir: File, name: String, isDir: Boolean): File {
        val base = if (isDir) name else name.substringBeforeLast('.', name)
        val ext = if (isDir) "" else name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var f = File(dir, base + ext)
        var n = 2
        while (f.exists()) { f = File(dir, "$base ($n)$ext"); n++ }
        return f
    }

    fun newFolder(parent: File, name: String): File? {
        val clean = cleanName(name).ifBlank { return null }
        val f = uniqueChild(parent, clean, isDir = true)
        return if (f.mkdirs()) f else null
    }

    /** True when [child] is [root] or somewhere inside it. */
    fun inside(child: File, root: File): Boolean {
        val c = child.canonicalPath; val r = root.canonicalPath
        return c == r || c.startsWith(r.trimEnd('/') + "/")
    }

    data class Moved(val from: File, val to: File)

    /**
     * Moves files and folders into [dest]. Never moves a folder into itself, keeps names unique,
     * and brings sidecar files (a recording's transcript) along. Returns what moved where.
     */
    fun move(items: List<File>, dest: File): List<Moved> {
        dest.mkdirs()
        val out = ArrayList<Moved>()
        items.distinct().forEach { src ->
            if (!src.exists()) return@forEach
            if (src.parentFile?.canonicalPath == dest.canonicalPath) return@forEach
            if (src.isDirectory && inside(dest, src)) return@forEach
            val target = uniqueChild(dest, src.name, src.isDirectory)
            val travel = if (src.isFile) sidecars(src) else emptyList() // gather before the original is gone
            if (relocate(src, target)) {
                out += Moved(src, target)
                travel.forEach { sc ->
                    val t = File(dest, target.nameWithoutExtension + "." + sc.extension)
                    if (!t.exists() && relocate(sc, t)) out += Moved(sc, t)
                }
            }
        }
        return out
    }

    fun copy(items: List<File>, dest: File): List<File> {
        dest.mkdirs()
        return items.distinct().mapNotNull { src ->
            if (!src.exists() || (src.isDirectory && inside(dest, src))) return@mapNotNull null
            val target = uniqueChild(dest, src.name, src.isDirectory)
            runCatching { src.copyRecursively(target, overwrite = false); target }.getOrNull()
        }
    }

    fun rename(item: File, newName: String): File? {
        val clean = cleanName(newName).ifBlank { return null }
        val name = if (item.isFile && !clean.contains('.') && item.extension.isNotEmpty()) "$clean.${item.extension}" else clean
        if (name == item.name) return item
        val target = uniqueChild(item.parentFile ?: return null, name, item.isDirectory)
        return if (relocate(item, target)) target else null
    }

    fun sidecars(f: File): List<File> =
        f.parentFile?.listFiles()?.filter { it.isFile && it != f && it.nameWithoutExtension == f.nameWithoutExtension && it.extension.lowercase(Locale.US) in SIDECARS }.orEmpty()

    private fun relocate(src: File, target: File): Boolean {
        if (src.renameTo(target)) return true
        return runCatching { src.copyRecursively(target, overwrite = false); src.deleteRecursively() }.getOrDefault(false)
    }

    private val monthFolder = DateTimeFormatter.ofPattern("yyyy-MM MMMM", Locale.US)
    fun monthFolderName(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = monthFolder.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** Files directly in [dir] go into "2026-11 November"-style folders by their date. Returns how many moved. */
    fun autoSortByMonth(dir: File, zone: ZoneId = ZoneId.systemDefault()): List<Moved> {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase(Locale.US) !in SIDECARS }.orEmpty()
        return files.groupBy { monthFolderName(it.lastModified(), zone) }.flatMap { (folder, list) -> move(list, File(dir, folder)) }
    }

    fun sorted(items: List<File>, sort: Sort): List<File> {
        val cmp: Comparator<File> = when (sort) {
            Sort.NEWEST -> compareByDescending { it.lastModified() }
            Sort.OLDEST -> compareBy { it.lastModified() }
            Sort.NAME_AZ -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            Sort.NAME_ZA -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name }
            Sort.LARGEST -> compareByDescending { size(it) }
            Sort.SMALLEST -> compareBy { size(it) }
            Sort.TYPE -> compareBy<File> { it.extension.lowercase(Locale.US) }.thenByDescending { it.lastModified() }
        }
        // Folders always first, then files, each in the chosen order
        return items.sortedWith(compareBy<File> { !it.isDirectory }.then(cmp))
    }

    fun size(f: File): Long = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)
    private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

    fun groupKey(f: File, group: Group, zone: ZoneId = ZoneId.systemDefault()): String = when (group) {
        Group.MONTH -> monthFmt.format(Instant.ofEpochMilli(f.lastModified()).atZone(zone))
        Group.DAY -> dayFmt.format(Instant.ofEpochMilli(f.lastModified()).atZone(zone))
        Group.TYPE -> FileKinds.of(f).label
        Group.NONE -> ""
    }

    /** Every folder under [root] (including root), for the "Move to" picker, as (folder, depth). */
    fun folderTree(root: File, maxDepth: Int = 6): List<Pair<File, Int>> {
        val out = ArrayList<Pair<File, Int>>()
        fun walk(d: File, depth: Int) {
            out += d to depth
            if (depth >= maxDepth) return
            d.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase(Locale.US) }?.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    fun matches(f: File, query: String): Boolean = query.isBlank() || query.lowercase(Locale.US).split(Regex("\\s+")).all { f.name.lowercase(Locale.US).contains(it) }
}
