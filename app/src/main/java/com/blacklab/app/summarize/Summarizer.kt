package com.blacklab.app.summarize

import com.blacklab.app.assistant.LocalIndex
import com.blacklab.app.assistant.MeetingActions
import com.blacklab.app.assistant.Nano
import com.blacklab.app.knowledge.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ln

/**
 * Summarizes any mix of BlackLab items (documents, notes, notebook pages, journal entries, events,
 * tasks, recordings, dictations, files) in the style you ask for. With Gemini Nano it reads each item
 * (map), then writes the summary from those notes (reduce); offline it uses an extractive engine
 * that ranks sentences by centrality and pulls decisions, actions, dates and risks with cue phrases.
 */
object Summarizer {

    enum class Style(val label: String, val hint: String, val instruction: String) {
        TLDR("TL;DR", "Two or three sentences", "Write a TL;DR: the two or three sentences that matter most."),
        KEY_POINTS("Key points", "Bulleted highlights", "List the key points as short bullets, most important first."),
        DETAILED("Detailed", "Section per item, full picture", "Write a detailed summary with a short section per source, keeping names, numbers and dates."),
        EXECUTIVE("Executive brief", "Bottom line, highlights, risks, next steps", "Write an executive brief with these headings: Bottom line, Highlights, Risks & issues, Next steps."),
        ACTIONS("Action items", "Who does what, by when", "List every action item as a bullet: who will do what, by when (if stated). Then list open tasks."),
        DECISIONS("Decisions & questions", "What was decided, what's open", "List the decisions made, then the open questions that still need an answer."),
        TIMELINE("Timeline", "Chronological, dated", "Write a chronological timeline: one bullet per dated event or milestone, oldest first, date first."),
        COMPARE("Compare", "Common ground and differences", "Compare the sources: what they have in common, where they differ, and what stands out in each."),
        STUDY("Study guide", "Key concepts + review questions", "Write a study guide: key concepts with one-line explanations, then 5 review questions with short answers."),
        TALKING_POINTS("Talking points", "For a meeting or a call", "Write 4 to 7 crisp talking points someone could say out loud in a meeting."),
        SPOKEN("Spoken brief", "Natural, to be read aloud", "Write a short spoken briefing in plain conversational sentences, no bullets or symbols, as if reading it to someone driving."),
        CUSTOM("Custom", "Describe exactly what you want", "");
    }

    enum class Length(val label: String, val sentences: Int, val tokens: Int) { SHORT("Short", 4, 220), MEDIUM("Medium", 8, 380), LONG("Long", 14, 560) }

    data class Source(val kind: String, val title: String, val text: String, val time: Long, val route: String? = null, val due: Long? = null)

    data class Request(val sources: List<Source>, val style: Style, val length: Length = Length.MEDIUM, val custom: String = "")

    data class Result(val title: String, val text: String, val engine: String, val style: Style, val actions: List<String>, val sourceCount: Int)

    /** Maps "give me action items", "timeline", "for my boss"… to a style; null if nothing fits. */
    fun styleFrom(text: String): Style? {
        val l = text.lowercase(Locale.US)
        return when {
            Regex("action|to-?dos?|next steps|follow[- ]?ups?|who (is doing|does) what").containsMatchIn(l) -> Style.ACTIONS
            Regex("decision|decided|open questions?").containsMatchIn(l) -> Style.DECISIONS
            Regex("timeline|chronolog|in order|what happened when").containsMatchIn(l) -> Style.TIMELINE
            Regex("compar|contrast|differen|versus|\\bvs\\b").containsMatchIn(l) -> Style.COMPARE
            Regex("study|quiz|flash ?cards?|exam|review questions").containsMatchIn(l) -> Style.STUDY
            Regex("talking points|for (a|the|my) (meeting|call)|what (do|should) i say").containsMatchIn(l) -> Style.TALKING_POINTS
            Regex("executive|for my (boss|manager|vp|director|leadership)|bottom line|status report|brief(ing)? for").containsMatchIn(l) -> Style.EXECUTIVE
            Regex("read (it )?(aloud|out)|spoken|while (i'?m )?driving|in the car").containsMatchIn(l) -> Style.SPOKEN
            Regex("tl;?dr|one line|quick|short version|gist|in a nutshell").containsMatchIn(l) -> Style.TLDR
            Regex("detail|thorough|everything|deep|full").containsMatchIn(l) -> Style.DETAILED
            Regex("bullet|key points|highlights|main points").containsMatchIn(l) -> Style.KEY_POINTS
            else -> null
        }
    }

    fun lengthFrom(text: String): Length? {
        val l = text.lowercase(Locale.US)
        return when {
            Regex("\\b(short|brief|quick|tl;?dr|one line)\\b").containsMatchIn(l) -> Length.SHORT
            Regex("\\b(long|detailed|thorough|everything|in depth)\\b").containsMatchIn(l) -> Length.LONG
            else -> null
        }
    }

    suspend fun run(req: Request, useNano: Boolean, onProgress: (String) -> Unit = {}): Result {
        val sources = req.sources.filter { it.text.isNotBlank() || it.title.isNotBlank() }.sortedBy { it.time }
        val title = titleFor(req, sources)
        if (sources.isEmpty()) return Result(title, "Nothing to summarize — the selected items are empty.", "—", req.style, emptyList(), 0)
        if (useNano) {
            if (Nano.status.value == Nano.Status.UNKNOWN) Nano.refresh()
            if (Nano.ready) {
                val out = runCatching { nano(req, sources, onProgress) }.getOrNull()
                if (!out.isNullOrBlank()) {
                    val actions = if (req.style == Style.ACTIONS) bullets(out) else emptyList()
                    return Result(title, out.trim(), com.blacklab.app.assistant.Nano.engine, req.style, actions, sources.size)
                }
            }
        }
        onProgress("Summarizing on the phone…")
        val style = if (req.style == Style.CUSTOM) styleFrom(req.custom) ?: Style.KEY_POINTS else req.style
        val (text, actions) = Extractive.summarize(sources, style, req.length)
        val note = if (req.style == Style.CUSTOM) "\n\n(Custom instructions need Gemini Nano; this is a ${style.label.lowercase()} summary.)" else ""
        return Result(title, text + note, "On-device extractive", req.style, actions, sources.size)
    }

    private fun titleFor(req: Request, sources: List<Source>): String {
        val what = if (sources.size == 1) sources.first().title.take(48) else "${sources.size} items"
        val style = if (req.style == Style.CUSTOM) "Summary" else req.style.label
        return "$style — $what"
    }

    private fun bullets(text: String) = text.lines().map { it.trim() }.filter { it.startsWith("-") || it.startsWith("•") || it.startsWith("*") || Regex("^\\d+[.)]").containsMatchIn(it) }
        .map { it.trimStart('-', '•', '*', ' ').replace(Regex("^\\d+[.)]\\s*"), "").replace(Regex("^\\[[ xX]]\\s*"), "") }.filter { it.length > 3 }

    private fun header(src: Source, i: Int): String = "[${i + 1}] ${src.kind} “${src.title}”" + (if (src.time > 0) " (${fmt(src.time)})" else "")

    private suspend fun nano(req: Request, sources: List<Source>, onProgress: (String) -> Unit): String? {
        val instruction = if (req.style == Style.CUSTOM) req.custom.ifBlank { Style.KEY_POINTS.instruction } else req.style.instruction
        // Map: condense long items into notes that keep what this style needs
        var budget = 14
        val notes = sources.mapIndexed { i, src ->
            val body = src.text.trim()
            if (body.length <= 1400 || budget <= 0) return@mapIndexed "${header(src, i)}:\n${body.take(1400)}"
            onProgress("Reading ${i + 1} of ${sources.size}: ${src.title.take(40)}…")
            val parts = body.chunked(2400).take(minOf(4, budget))
            budget -= parts.size
            val condensed = parts.mapNotNull { part ->
                Nano.generate(
                    """Take notes on this excerpt for a later summary. The final summary will be: $instruction
Keep facts, names, numbers, dates, decisions, action items and risks. At most 8 short bullets.

${header(src, i)}:
$part""", 0.2f, 300,
                )
            }.joinToString("\n")
            "${header(src, i)} — notes:\n${condensed.ifBlank { body.take(1400) }}"
        }
        // Reduce: merge groups until the notes fit in one prompt, then write the summary
        var merged = notes
        var round = 0
        while (merged.sumOf { it.length } > 5200 && merged.size > 1 && round < 3) {
            onProgress("Combining notes…")
            merged = merged.chunked(3).map { group ->
                Nano.generate("Combine these notes, keeping every fact, date, decision and action item. Bullets only.\n\n" + group.joinToString("\n\n"), 0.2f, 360)
                    ?: group.joinToString("\n").take(1700)
            }
            round++
        }
        onProgress("Writing the summary…")
        val length = when (req.length) { Length.SHORT -> "Keep it short."; Length.MEDIUM -> "Medium length."; Length.LONG -> "Be thorough." }
        val prompt = """You summarize the user's own files. $instruction $length
Use only the material below. Plain text; bullets start with "- "; headings end with ":". Mention the source number like [2] when it helps.
Today is ${fmt(System.currentTimeMillis())}.

${merged.joinToString("\n\n").take(6000)}"""
        return Nano.generate(prompt, 0.25f, req.length.tokens, timeoutMs = 45_000)
    }

    private val dayFmt = DateTimeFormatter.ofPattern("EEE MMM d, yyyy", Locale.US)
    fun fmt(ms: Long): String = dayFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}

/**
 * Offline summarizer. Sentences are scored by how central their terms are to the whole selection
 * (TF-IDF weight shared with other sentences), with a small lead bias, and picked per style.
 * Pure Kotlin so it's unit-tested on the JVM.
 */
object Extractive {

    private val riskCue = Regex("\\b(risk|issue|problem|concern|delay(ed)?|blocked|blocker|overdue|late|fail(ed|ure)?|leak|broken|outage|over budget|warning|urgent|critical|safety)\\b", RegexOption.IGNORE_CASE)
    private val dateCue = Regex("\\b(\\d{1,2}/\\d{1,2}(/\\d{2,4})?|(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.? \\d{1,2}|monday|tuesday|wednesday|thursday|friday|saturday|sunday|tomorrow|today|next week|q[1-4]|\\d{4}-\\d{2}-\\d{2})\\b", RegexOption.IGNORE_CASE)
    private val defCue = Regex("\\b(is|are|means|refers to|is called|defined as)\\b", RegexOption.IGNORE_CASE)

    data class Scored(val src: Int, val pos: Int, val text: String, val score: Double)

    fun score(sources: List<Summarizer.Source>): List<Scored> {
        // Skip BlackLab's own metadata lines ("Noted Sun, Nov 1 · 5:13 PM via BlackLab", "From meeting: …")
        val meta = Regex("^(Noted .* via |From meeting: |— Page \\d+ —)")
        val sents = sources.flatMapIndexed { si, s -> Text.sentences(s.text).filter { it.split(' ').size in 4..60 && !meta.containsMatchIn(it) }.mapIndexed { pi, t -> Triple(si, pi, t) } }
        if (sents.isEmpty()) return emptyList()
        val toks = sents.map { LocalIndex.tokens(it.third) }
        val df = HashMap<String, Int>()
        toks.forEach { t -> t.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = sents.size.toDouble()
        val tf = HashMap<String, Int>()
        toks.forEach { t -> t.forEach { tf[it] = (tf[it] ?: 0) + 1 } }
        return sents.mapIndexed { i, (si, pi, text) ->
            val terms = toks[i].toSet()
            val weight = terms.sumOf { w -> (tf[w] ?: 0).toDouble().let { f -> if (f < 2) 0.0 else ln(1 + f) * ln(1 + n / (df[w] ?: 1)) } }
            val norm = weight / (1 + ln(1.0 + terms.size))
            val lead = if (pi == 0) 1.25 else if (pi < 3) 1.1 else 1.0
            Scored(si, pi, text, norm * lead)
        }
    }

    fun top(scored: List<Scored>, n: Int): List<Scored> {
        val picked = ArrayList<Scored>()
        for (s in scored.sortedByDescending { it.score }) {
            if (picked.size >= n) break
            val t = LocalIndex.tokens(s.text).toSet()
            // skip near-duplicates
            if (picked.any { p -> val o = LocalIndex.tokens(p.text).toSet(); t.isNotEmpty() && (t intersect o).size.toDouble() / t.size > 0.7 }) continue
            picked += s
        }
        return picked.sortedWith(compareBy({ it.src }, { it.pos }))
    }

    /** Returns the summary text and the action items it found. */
    fun summarize(sources: List<Summarizer.Source>, style: Summarizer.Style, length: Summarizer.Length): Pair<String, List<String>> {
        val scored = score(sources)
        val n = length.sentences
        val tasks = sources.filter { it.kind == "Task" }
        val all = sources.joinToString("\n") { it.text }
        val (decisions, actionSentences) = MeetingActions.heuristic(all)
        val actions = (actionSentences + tasks.map { t -> t.title + (t.due?.let { " — due ${Summarizer.fmt(it)}" } ?: "") }).distinct()
        fun bullets(list: List<String>) = list.joinToString("\n") { "- ${it.trim()}" }
        fun fallback(k: Int) = top(scored, k).map { it.text }.ifEmpty { sources.map { it.title } }

        val text = when (style) {
            Summarizer.Style.TLDR -> fallback(minOf(3, n)).joinToString(" ")
            Summarizer.Style.KEY_POINTS, Summarizer.Style.CUSTOM -> bullets(fallback(n))
            Summarizer.Style.TALKING_POINTS -> bullets(fallback(minOf(7, n)).map { it.split(' ').take(24).joinToString(" ").trimEnd(',', ';') })
            Summarizer.Style.DETAILED -> sources.mapIndexed { i, s ->
                val mine = top(scored.filter { it.src == i }, maxOf(2, n / 2)).map { it.text }
                "${s.title}" + (if (s.time > 0) " (${Summarizer.fmt(s.time)})" else "") + ":\n" + bullets(mine.ifEmpty { listOf(s.text.take(300).ifBlank { s.kind }) })
            }.joinToString("\n\n")
            Summarizer.Style.EXECUTIVE -> {
                val best = fallback(n)
                val risks = scored.filter { riskCue.containsMatchIn(it.text) }.sortedByDescending { it.score }.take(maxOf(2, n / 3)).map { it.text }
                buildString {
                    val bottom = best.firstOrNull { it.split(' ').size >= 8 } ?: best.firstOrNull()
                    append("Bottom line:\n").append(bottom ?: "—")
                    append("\n\nHighlights:\n").append(bullets(best.filter { it != bottom }.take(maxOf(3, n / 2))).ifBlank { "- —" })
                    append("\n\nRisks & issues:\n").append(bullets(risks).ifBlank { "- None flagged" })
                    append("\n\nNext steps:\n").append(bullets(actions.take(maxOf(3, n / 2))).ifBlank { "- None stated" })
                }
            }
            Summarizer.Style.ACTIONS -> if (actions.isEmpty()) "No action items found." else "Action items:\n" + bullets(actions.take(n * 2))
            Summarizer.Style.DECISIONS -> {
                val questions = scored.filter { it.text.trimEnd().endsWith("?") }.map { it.text }.distinct().take(n)
                "Decisions:\n" + bullets(decisions.take(n)).ifBlank { "- None recorded" } + "\n\nOpen questions:\n" + bullets(questions).ifBlank { "- None recorded" }
            }
            Summarizer.Style.TIMELINE -> {
                val dated = sources.filter { it.time > 0 }.sortedBy { it.due ?: it.time }.map { s ->
                    val best = top(scored.filter { sc -> sources.indexOf(s) == sc.src }, 1).firstOrNull()?.text
                    "${Summarizer.fmt(s.due ?: s.time)} — ${s.title}" + (best?.let { ": ${it.take(160)}" } ?: "")
                }
                val mentions = scored.filter { dateCue.containsMatchIn(it.text) }.sortedByDescending { it.score }.take(n / 2).map { it.text }
                "Timeline:\n" + bullets(dated.take(n * 2)) + if (mentions.isNotEmpty()) "\n\nDates mentioned:\n" + bullets(mentions) else ""
            }
            Summarizer.Style.COMPARE -> {
                val termsBy = sources.map { s -> LocalIndex.tokens(s.title + " " + s.text).groupingBy { it }.eachCount() }
                val common = termsBy.map { it.keys }.reduceOrNull { a, b -> a intersect b }.orEmpty()
                    .sortedByDescending { t -> termsBy.sumOf { it[t] ?: 0 } }.take(8)
                buildString {
                    append("Common ground: ").append(if (common.isEmpty()) "little overlap" else common.joinToString(", ")).append("\n")
                    sources.forEachIndexed { i, s ->
                        val unique = termsBy[i].entries.filter { e -> termsBy.withIndex().none { (j, m) -> j != i && e.key in m } }
                            .sortedByDescending { it.value }.take(5).map { it.key }
                        val best = top(scored.filter { it.src == i }, 2).map { it.text }
                        append("\n${s.title}:\n").append(bullets(best.ifEmpty { listOf(s.text.take(200)) }))
                        if (unique.isNotEmpty()) append("\n- Only here: ").append(unique.joinToString(", "))
                        append("\n")
                    }
                }.trim()
            }
            Summarizer.Style.STUDY -> {
                val freq = LocalIndex.tokens(all).filter { it.length > 3 }.groupingBy { it }.eachCount()
                val terms = freq.entries.sortedByDescending { it.value }.take(maxOf(4, n / 2)).map { it.key }
                val concepts = terms.map { t ->
                    val def = scored.filter { it.text.lowercase().contains(t) && defCue.containsMatchIn(it.text) }.maxByOrNull { it.score }
                        ?: scored.filter { it.text.lowercase().contains(t) }.maxByOrNull { it.score }
                    "${t.replaceFirstChar { it.uppercase() }} — ${def?.text?.take(180) ?: "appears ${freq[t]} times"}"
                }
                val qs = top(scored, minOf(5, n)).mapIndexed { i, s ->
                    val key = LocalIndex.tokens(s.text).firstOrNull { it in terms } ?: LocalIndex.tokens(s.text).maxByOrNull { it.length } ?: "this"
                    "Q${i + 1}: What should you remember about “$key”?\n  A: ${s.text}"
                }
                "Key concepts:\n" + bullets(concepts) + "\n\nReview questions:\n" + qs.joinToString("\n")
            }
            Summarizer.Style.SPOKEN -> {
                val counts = sources.groupingBy { it.kind }.eachCount().entries.joinToString(", ") { (k, v) -> "$v ${k.lowercase()}${if (v > 1) "s" else ""}" }
                val best = fallback(minOf(4, n))
                "Here's the rundown on $counts. " + best.joinToString(" ") +
                    if (actions.isNotEmpty()) " Things to act on: " + actions.take(3).joinToString("; ") + "." else ""
            }
        }
        return text.trim() to actions
    }
}
