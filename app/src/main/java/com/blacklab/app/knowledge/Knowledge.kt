package com.blacklab.app.knowledge

import android.content.Context
import com.blacklab.app.assistant.LocalIndex
import kotlin.math.ln

/**
 * BlackLab's built-in expert knowledge: seven domain knowledge bases (software engineering, software
 * development, AI engineering, AI development, GitHub, peer support practice, MHT practice & unit safety)
 * and eight conversation knowledge bases that
 * the assistant talks from. Everything ships inside the APK as Markdown under assets/knowledge and is
 * searched on the phone, so it works with no signal (in the car, on a plane, in a mechanical room).
 *
 * File format (one file per knowledge base):
 * ```
 * ---
 * id: software-engineering
 * title: Software Engineering
 * kind: domain | conversation
 * summary: one line
 * draws: kb-id, kb-id            (conversation only: which domain bases it pulls from)
 * persona: how the assistant talks in this conversation
 * opener: first thing it says
 * starters: question | question | question
 * ---
 * ## Entry title
 * tags: a, b, c
 * ask: follow-up question | follow-up question
 * Body paragraphs…
 * ```
 */
object Knowledge {

    enum class Kind { DOMAIN, CONVERSATION }

    data class Entry(
        val id: String,
        val kbId: String,
        val title: String,
        val tags: List<String>,
        val ask: List<String>,
        val body: String,
    ) {
        /** First sentence or two — what a list row or a quick spoken answer shows. */
        val lead: String get() = Text.sentences(body).take(2).joinToString(" ").take(260)
    }

    data class Base(
        val id: String,
        val title: String,
        val kind: Kind,
        val summary: String,
        val draws: List<String>,
        val persona: String,
        val opener: String,
        val starters: List<String>,
        val icon: String,
        val entries: List<Entry>,
    )

    data class Hit(val entry: Entry, val score: Double)

    @Volatile private var loaded: List<Base>? = null
    @Volatile private var index: Bm25? = null

    fun bases(context: Context): List<Base> = loaded ?: synchronized(this) {
        loaded ?: load(context).also { loaded = it; index = Bm25(it.flatMap { b -> b.entries }) }
    }

    fun domains(context: Context) = bases(context).filter { it.kind == Kind.DOMAIN }
    fun conversations(context: Context) = bases(context).filter { it.kind == Kind.CONVERSATION }
    fun base(context: Context, id: String) = bases(context).firstOrNull { it.id == id }
    fun entry(context: Context, id: String) = bases(context).asSequence().flatMap { it.entries }.firstOrNull { it.id == id }
    fun entryCount(context: Context) = bases(context).sumOf { it.entries.size }

    /**
     * BM25 over every entry. [scope] limits the search to those knowledge bases; [boost] multiplies
     * the score of entries from particular bases (a conversation favors its own notes).
     */
    fun search(context: Context, query: String, limit: Int = 5, scope: Set<String>? = null, boost: Map<String, Double> = emptyMap()): List<Hit> {
        bases(context)
        return index?.search(query, limit, scope, boost).orEmpty()
    }

    private fun load(context: Context): List<Base> {
        val am = context.assets
        val files = runCatching { am.list("knowledge").orEmpty().filter { it.endsWith(".md") }.sorted() }.getOrDefault(emptyList())
        return files.mapNotNull { name -> runCatching { parse(am.open("knowledge/$name").bufferedReader().use { it.readText() }) }.getOrNull() }
            .sortedWith(compareBy({ it.kind }, { order.indexOf(it.id).let { i -> if (i < 0) 99 else i } }, { it.title }))
    }

    /** Display order for the built-in bases; anything else sorts after them by title. */
    private val order = listOf(
        "software-engineering", "software-development", "ai-engineering", "ai-development", "github", "peer-support", "mht-practice",
        "conv-architecture", "conv-code-craft", "conv-ai-builder", "conv-ship-it", "conv-peer-lab", "conv-health", "conv-money-home", "conv-downtime",
    )

    /** Parses one knowledge-base Markdown file. Public for unit tests. */
    fun parse(md: String): Base {
        val text = md.replace("\r\n", "\n")
        val header = HashMap<String, String>()
        var body = text
        if (text.startsWith("---")) {
            val end = text.indexOf("\n---", 3)
            require(end > 0) { "Unterminated header" }
            text.substring(3, end).lines().forEach { line ->
                val i = line.indexOf(':')
                if (i > 0) header[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            body = text.substring(end + 4)
        }
        val id = header["id"] ?: error("Missing id")
        val entries = ArrayList<Entry>()
        val sections = body.split(Regex("(?m)^## ")).drop(1)
        sections.forEachIndexed { n, sec ->
            val lines = sec.lines()
            val title = lines.first().trim()
            var tags = emptyList<String>()
            var ask = emptyList<String>()
            val paras = ArrayList<String>()
            lines.drop(1).forEach { raw ->
                val l = raw.trimEnd()
                when {
                    l.startsWith("tags:", true) -> tags = l.substringAfter(':').split(',').map { it.trim() }.filter { it.isNotBlank() }
                    l.startsWith("ask:", true) -> ask = l.substringAfter(':').split('|').map { it.trim() }.filter { it.isNotBlank() }
                    else -> paras += l
                }
            }
            val content = paras.joinToString("\n").trim()
            if (title.isNotBlank() && content.isNotBlank()) entries += Entry("$id#${n + 1}", id, title, tags, ask, content)
        }
        return Base(
            id = id,
            title = header["title"] ?: id,
            kind = if (header["kind"].equals("conversation", true)) Kind.CONVERSATION else Kind.DOMAIN,
            summary = header["summary"].orEmpty(),
            draws = header["draws"].orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() },
            persona = header["persona"].orEmpty(),
            opener = header["opener"].orEmpty(),
            starters = header["starters"].orEmpty().split('|').map { it.trim() }.filter { it.isNotBlank() },
            icon = header["icon"].orEmpty(),
            entries = entries,
        )
    }

    /** Small, dependency-free BM25 index (title and tags weigh more than body text). */
    class Bm25(private val entries: List<Entry>) {
        private val postings = HashMap<String, MutableList<Pair<Int, Int>>>()
        private val lengths = IntArray(entries.size)
        private val avg: Double

        init {
            entries.forEachIndexed { i, e ->
                val terms = LocalIndex.tokens(e.title).let { it + it + it } + LocalIndex.tokens(e.tags.joinToString(" ")).let { it + it } + LocalIndex.tokens(e.body)
                lengths[i] = terms.size
                terms.groupingBy { it }.eachCount().forEach { (t, n) -> postings.getOrPut(t) { ArrayList() } += i to n }
            }
            avg = if (entries.isEmpty()) 1.0 else lengths.average().coerceAtLeast(1.0)
        }

        fun search(query: String, limit: Int, scope: Set<String>?, boost: Map<String, Double>): List<Hit> {
            val q = LocalIndex.tokens(query).distinct()
            if (q.isEmpty() || entries.isEmpty()) return emptyList()
            val n = entries.size.toDouble()
            val scores = HashMap<Int, Double>()
            q.forEach { term ->
                val list = postings[term] ?: return@forEach
                val idf = ln(1 + (n - list.size + 0.5) / (list.size + 0.5))
                list.forEach { (i, tf) ->
                    if (scope != null && entries[i].kbId !in scope) return@forEach
                    val norm = tf * 2.4 / (tf + 1.4 * (0.25 + 0.75 * lengths[i] / avg))
                    scores[i] = (scores[i] ?: 0.0) + idf * norm
                }
            }
            return scores.entries.map { (i, s) -> Hit(entries[i], s * (boost[entries[i].kbId] ?: 1.0)) }
                .sortedByDescending { it.score }.take(limit)
        }
    }
}

/** Sentence helpers shared by the knowledge and summary engines. */
object Text {
    private val splitter = Regex("(?<=[.!?])\\s+(?=[\"“(]?[A-Z0-9])")

    fun sentences(text: String): List<String> = text
        .replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
        .split(Regex("\\n{2,}|\\n(?=[A-Z0-9•\\-*])"))
        .flatMap { it.replace('\n', ' ').split(splitter) }
        .map { it.trim() }
        .filter { it.length > 2 }

    /** The [n] sentences of [text] that share the most terms with [query], kept in their original order. */
    fun bestSentences(text: String, query: String, n: Int): List<String> {
        val q = LocalIndex.tokens(query).toSet()
        val all = sentences(text)
        if (q.isEmpty()) return all.take(n)
        val ranked = all.withIndex().sortedByDescending { (i, s) -> LocalIndex.tokens(s).count { it in q } * 10 - i }.take(n)
        return ranked.sortedBy { it.index }.map { it.value }
    }
}
