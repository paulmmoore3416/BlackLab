package com.blacklab.app.assistant

import android.net.Uri
import com.blacklab.app.AppContainer
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.ContentStore
import com.blacklab.app.dictation.DictationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.ln

/**
 * “Ask my files” — an on-device BM25 search index over documents, journal entries, recording and
 * dictation transcripts, tasks, events and courses. Nothing leaves the phone; Gemini Nano (when
 * available) writes the answer from the top passages, otherwise the best passages are quoted.
 */
class LocalIndex(private val app: AppContainer) {

    data class Passage(val kind: String, val title: String, val text: String, val route: String, val profile: String, val time: Long)
    data class Hit(val passage: Passage, val score: Double)

    private var passages: List<Passage> = emptyList()
    private var postings: Map<String, List<Pair<Int, Int>>> = emptyMap() // term -> (passage, tf)
    private var lengths: IntArray = IntArray(0)
    private var avgLen = 1.0
    private var builtAt = 0L
    private val lock = Mutex()

    val size: Int get() = passages.size

    suspend fun ensure(maxAgeMs: Long = 5 * 60_000L) {
        if (Time.now() - builtAt > maxAgeMs) rebuild()
    }

    suspend fun rebuild() = lock.withLock {
        withContext(Dispatchers.IO) {
            val s = app.settings.current
            val out = ArrayList<Passage>()
            if (s.asstIndexDocs) app.db.documents().all().forEach { d ->
                val text = ContentStore.plainText(app.content.readBody(d.filePath))
                chunk(text).forEach { out += Passage("Document", d.title, it, "doc/${d.id}", d.profile, d.updatedAt) }
                if (text.isBlank()) out += Passage("Document", d.title, d.preview, "doc/${d.id}", d.profile, d.updatedAt)
            }
            if (s.asstIndexJournal) Profile.entries.forEach { p ->
                app.db.journal().allFor(p.name).forEach { j ->
                    val text = ContentStore.plainText(app.content.readBody(j.filePath))
                    val title = j.title.ifBlank { "Journal · " + Time.date(LocalDate.ofEpochDay(j.entryDay)) }
                    chunk(text).forEach { out += Passage("Journal", title, it, "journal/${j.id}", j.profile, j.updatedAt) }
                }
            }
            if (s.asstIndexTranscripts) {
                Profile.entries.forEach { p ->
                    app.db.recordings().allFor(p.name).forEach { r ->
                        chunk(r.transcript + "\n" + r.notes).forEach { out += Passage("Recording", r.title, it, "recording/${r.id}", r.profile, r.createdAt) }
                    }
                    DictationStore.list(app.storage.dir(p, Folder.DICTATION)).forEach { (folder, m) ->
                        val rel = app.storage.relative(folder)
                        chunk(DictationStore.plainText(m, false)).forEach {
                            out += Passage("Dictation", m.title, it, "dictation/session?path=" + Uri.encode(rel), p.name, m.startedAt)
                        }
                    }
                }
            }
            if (s.asstIndexTasks) {
                app.db.tasks().all().forEach { t ->
                    out += Passage("Task", t.title, listOf(t.title, t.notes, t.tags).filter { it.isNotBlank() }.joinToString(". "), "task/${t.id}", t.profile, t.updatedAt)
                }
                app.db.events().all().forEach { e ->
                    out += Passage("Event", e.title, listOf(e.title, e.location, e.notes, e.attendees, Time.dateTime(e.start)).filter { it.isNotBlank() }.joinToString(". "),
                        "event/${e.id}", e.profile, e.start)
                }
                app.db.education().allCourses().forEach { c ->
                    out += Passage("Course", c.title, listOf(c.code, c.title, c.institution, c.instructor, c.officeHours, c.location, c.notes).filter { it.isNotBlank() }.joinToString(". "),
                        "course/${c.id}", c.profile, c.createdAt)
                }
                app.db.education().allCerts().forEach { c ->
                    out += Passage("Certification", c.name, listOf(c.name, c.issuer, c.credentialId, c.status, c.notes).filter { it.isNotBlank() }.joinToString(". "),
                        "education?tab=Certifications", c.profile, 0)
                }
            }
            val post = HashMap<String, MutableList<Pair<Int, Int>>>()
            val lens = IntArray(out.size)
            out.forEachIndexed { i, p ->
                val terms = tokens(p.title + " " + p.text)
                lens[i] = terms.size
                terms.groupingBy { it }.eachCount().forEach { (t, n) -> post.getOrPut(t) { ArrayList() } += i to n }
            }
            passages = out
            postings = post
            lengths = lens
            avgLen = if (lens.isEmpty()) 1.0 else lens.average().coerceAtLeast(1.0)
            builtAt = Time.now()
        }
    }

    fun search(query: String, limit: Int = 6, profile: String? = null): List<Hit> {
        val q = tokens(query).distinct()
        if (q.isEmpty() || passages.isEmpty()) return emptyList()
        val n = passages.size.toDouble()
        val scores = HashMap<Int, Double>()
        val k1 = 1.4; val b = 0.75
        q.forEach { term ->
            val list = postings[term] ?: return@forEach
            val idf = ln(1 + (n - list.size + 0.5) / (list.size + 0.5))
            list.forEach { (i, tf) ->
                val norm = tf * (k1 + 1) / (tf + k1 * (1 - b + b * lengths[i] / avgLen))
                scores[i] = (scores[i] ?: 0.0) + idf * norm
            }
        }
        return scores.entries
            .map { (i, sc) -> Hit(passages[i], sc + if (profile != null && passages[i].profile == profile) 0.15 else 0.0) }
            .sortedByDescending { it.score }
            .distinctBy { it.passage.route to it.passage.text.take(40) }
            .take(limit)
    }

    companion object {
        private val stop = setOf(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "were", "be", "it", "that", "this", "with", "as", "at", "by",
            "from", "my", "me", "i", "you", "your", "what", "when", "where", "who", "how", "did", "do", "does", "about", "any", "have", "has", "had", "we",
            "our", "can", "will", "there", "find", "show", "tell", "files", "notes", "anything",
        )

        fun tokens(s: String): List<String> = Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }
            .filter { it.length > 1 && it !in stop }
            .map(::stem)
            .toList()

        private fun stem(w: String): String = when {
            w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
            w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
            w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
            else -> w
        }

        /** ~110-word passages with a little overlap so answers keep their context. */
        fun chunk(text: String, size: Int = 110, overlap: Int = 20): List<String> {
            val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) return emptyList()
            if (words.size <= size) return listOf(words.joinToString(" "))
            val out = ArrayList<String>()
            var i = 0
            while (i < words.size) {
                out += words.subList(i, minOf(words.size, i + size)).joinToString(" ")
                i += size - overlap
            }
            return out
        }
    }
}
