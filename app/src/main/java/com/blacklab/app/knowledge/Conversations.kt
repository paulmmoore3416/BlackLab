package com.blacklab.app.knowledge

import com.blacklab.app.AppContainer
import com.blacklab.app.assistant.Nano
import com.blacklab.app.core.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Conversations that draw on the knowledge bases. Each turn retrieves the most relevant notes from
 * the conversation's own base plus the domain bases it draws on, then:
 *  - with Gemini Nano: answers in the conversation's persona, grounded in those notes and the last
 *    few turns, ending with a natural follow-up;
 *  - without it: answers from the best-matching note (the sentences closest to the question) and
 *    offers related topics to keep the conversation going.
 * History is kept per conversation on the phone (filesDir/knowledge), never synced.
 */
class Conversations(private val app: AppContainer) {

    data class Turn(val user: Boolean, val text: String, val time: Long, val sources: List<String> = emptyList(), val engine: String = "")

    data class Answer(val text: String, val sources: List<Knowledge.Entry>, val followUps: List<String>, val engine: String)

    private val dir = File(app.context.filesDir, "knowledge").apply { mkdirs() }
    private val lock = Mutex()
    /** Turns per conversation id, loaded lazily. */
    val turns = MutableStateFlow<Map<String, List<Turn>>>(emptyMap())
    val busy = MutableStateFlow(false)

    fun history(id: String): List<Turn> {
        turns.value[id]?.let { return it }
        val loaded = load(id)
        turns.value = turns.value + (id to loaded)
        return loaded
    }

    fun clear(id: String) {
        turns.value = turns.value + (id to emptyList())
        File(dir, "conv_$id.json").delete()
    }

    /** Adds the opener as the first assistant turn of an empty conversation. */
    fun open(id: String): List<Turn> {
        val h = history(id)
        if (h.isNotEmpty()) return h
        val base = Knowledge.base(app.context, id) ?: return h
        val first = listOf(Turn(false, base.opener.ifBlank { "What's on your mind?" }, Time.now(), engine = base.title))
        turns.value = turns.value + (id to first)
        save(id, first)
        return first
    }

    suspend fun ask(id: String, question: String): Answer = lock.withLock {
        val base = Knowledge.base(app.context, id) ?: return Answer("That conversation isn't available.", emptyList(), emptyList(), "")
        busy.value = true
        try {
            val prior = history(id)
            val withUser = prior + Turn(true, question.trim(), Time.now())
            turns.value = turns.value + (id to withUser)
            val answer = withContext(Dispatchers.IO) { answer(base, question, prior) }
            val done = withUser + Turn(false, answer.text, Time.now(), answer.sources.map { it.id }, answer.engine)
            val kept = done.takeLast(80)
            turns.value = turns.value + (id to kept)
            save(id, kept)
            answer
        } finally {
            busy.value = false
        }
    }

    private suspend fun answer(base: Knowledge.Base, question: String, prior: List<Turn>): Answer {
        val s = app.settings.current
        // Short follow-ups ("why?", "go on", "give me an example") lean on the previous question for retrieval
        val lastUser = prior.lastOrNull { it.user }?.text.orEmpty()
        val retrievalQuery = if (com.blacklab.app.assistant.LocalIndex.tokens(question).size < 3) "$question $lastUser" else question
        val scope = (base.draws + base.id).toSet()
        val hits = Knowledge.search(app.context, retrievalQuery, 4, scope, mapOf(base.id to 1.35))
        val followUps = followUps(base, hits, question)

        if (s.asstUseNano) {
            if (Nano.status.value == Nano.Status.UNKNOWN) Nano.refresh()
            if (Nano.ready) {
                val notes = hits.take(3).mapIndexed { i, h -> "[${i + 1}] ${h.entry.title}: ${Text.bestSentences(h.entry.body, retrievalQuery, 6).joinToString(" ").take(700)}" }
                    .joinToString("\n")
                val recent = prior.takeLast(4).joinToString("\n") { (if (it.user) "User: " else "You: ") + it.text.take(280) }
                val who = s.userName.substringBefore(' ').ifBlank { "the user" } + (s.jobTitle.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
                val prompt = """${base.persona.ifBlank { "You are a knowledgeable, friendly conversation partner." }}
You're talking with $who. Speak naturally, like a sharp friend across the table: 2 to 6 sentences, concrete, no lists unless asked. Use the reference notes when they fit; if they don't cover it, answer from general knowledge and say when you're unsure. Don't give medical, legal or financial instructions as if you were a professional — share general knowledge and suggest a pro for specifics. End with one short follow-up question or a natural next thread, unless the user is wrapping up.

Reference notes:
${notes.ifBlank { "(none matched)" }}

Conversation so far:
${recent.ifBlank { "(just starting)" }}
User: $question
You:"""
                val out = Nano.generate(prompt, (s.asstNanoTemp / 100f).coerceAtLeast(0.35f), maxOf(s.asstNanoTokens, 360))
                    ?.removePrefix("You:")?.trim()
                if (!out.isNullOrBlank()) return Answer(out, hits.map { it.entry }, followUps, "${com.blacklab.app.assistant.Nano.engineShort} · ${base.title}")
            }
        }
        return offline(base, question, retrievalQuery, hits, followUps)
    }

    private fun offline(base: Knowledge.Base, question: String, query: String, hits: List<Knowledge.Hit>, followUps: List<String>): Answer {
        val top = hits.firstOrNull()
        if (top == null || top.score < 1.2) {
            val topics = base.starters.take(3).joinToString("; ")
            return Answer(
                "I don't have notes on that one in ${base.title} yet. " + if (topics.isNotBlank()) "Some threads we could pull on: $topics." else "Try asking it a different way.",
                emptyList(), base.starters.take(3), "${base.title} · offline",
            )
        }
        val core = Text.bestSentences(top.entry.body, query, 4).joinToString(" ")
        val second = hits.getOrNull(1)?.takeIf { it.score > top.score * 0.6 && it.entry.id != top.entry.id }
        val extra = second?.let { " Related — ${it.entry.title.lowercase()}: " + Text.bestSentences(it.entry.body, query, 1).joinToString(" ") }.orEmpty()
        val nudge = top.entry.ask.firstOrNull()?.let { " $it" } ?: second?.let { " Want to get into ${it.entry.title.lowercase()}?" }.orEmpty()
        return Answer((core + extra).trim() + nudge, hits.map { it.entry }, followUps, "${base.title} · offline notes")
    }

    private fun followUps(base: Knowledge.Base, hits: List<Knowledge.Hit>, question: String): List<String> {
        val q = question.lowercase()
        // Domain notes phrase "ask" lines as the user would; conversation notes phrase them as the assistant's own follow-ups
        val domainIds = Knowledge.domains(app.context).map { it.id }.toSet()
        val asks = hits.filter { it.entry.kbId in domainIds }.flatMap { it.entry.ask }.filter { it.lowercase() != q }
        val related = hits.drop(1).map { "Tell me about ${it.entry.title.lowercase()}" }
        return (asks + related + base.starters).distinct().take(4)
    }

    private fun load(id: String): List<Turn> = runCatching {
        val arr = JSONArray(File(dir, "conv_$id.json").readText())
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val src = o.optJSONArray("sources") ?: JSONArray()
            Turn(o.getBoolean("user"), o.getString("text"), o.getLong("time"), (0 until src.length()).map { src.getString(it) }, o.optString("engine"))
        }
    }.getOrDefault(emptyList())

    private fun save(id: String, list: List<Turn>) {
        runCatching {
            File(dir, "conv_$id.json").writeText(JSONArray().apply {
                list.forEach { t ->
                    put(JSONObject().put("user", t.user).put("text", t.text).put("time", t.time).put("engine", t.engine)
                        .put("sources", JSONArray().apply { t.sources.forEach { put(it) } }))
                }
            }.toString())
        }
    }
}
