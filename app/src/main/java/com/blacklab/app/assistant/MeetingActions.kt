package com.blacklab.app.assistant

import android.net.Uri
import com.blacklab.app.AppContainer
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Time
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.DocumentEntity
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import com.blacklab.app.dictation.DictationStore
import com.blacklab.app.ui.tasks.QuickParse

/**
 * Meeting → Actions: turns the latest dictation or recording transcript into minutes (summary,
 * decisions, action items) and creates a To-Do task for each action item. Runs offline — Gemini
 * Nano does the reading when available, with a cue-phrase extractor as the fallback.
 */
object MeetingActions {

    data class Source(val title: String, val text: String, val route: String, val time: Long)
    data class Result(val source: Source, val summary: String, val decisions: List<String>, val actions: List<String>, val docId: Long, val taskIds: List<Long>)

    suspend fun latestSource(app: AppContainer): Source? {
        val profile = app.settings.current.activeProfile
        val dict = DictationStore.list(app.storage.dir(profile, Folder.DICTATION)).firstOrNull()?.let { (folder, m) ->
            Source(m.title, DictationStore.plainText(m, false), "dictation/session?path=" + Uri.encode(app.storage.relative(folder)), m.startedAt)
        }
        val rec = app.db.recordings().allFor(profile.name).firstOrNull { it.transcript.isNotBlank() }?.let { r ->
            Source(r.title, r.transcript, "recording/${r.id}", r.createdAt)
        }
        return listOfNotNull(dict, rec).filter { it.text.isNotBlank() }.maxByOrNull { it.time }
    }

    suspend fun run(app: AppContainer, source: Source): Result {
        val s = app.settings.current
        var summary = ""
        val decisions = ArrayList<String>()
        val actions = ArrayList<String>()

        if (s.asstUseNano && Nano.ready) {
            source.text.chunked(2600).take(4).forEachIndexed { i, part ->
                val out = Nano.generate(
                    """You are taking minutes. Read this meeting transcript excerpt and reply in exactly this format:
SUMMARY: <one or two sentences>
DECISION: <decision>   (one line per decision, omit if none)
ACTION: <who> will <what> by <when, if said>   (one line per action item, omit if none)

Transcript (part ${i + 1}):
$part""", temperature = 0.2f, maxTokens = 400,
                ) ?: return@forEachIndexed
                out.lines().map { it.trim().trimStart('-', '*', '•', ' ') }.forEach { line ->
                    when {
                        line.startsWith("SUMMARY:", true) -> summary += (if (summary.isBlank()) "" else " ") + line.substringAfter(':').trim()
                        line.startsWith("DECISION:", true) -> line.substringAfter(':').trim().takeIf { it.length > 3 }?.let(decisions::add)
                        line.startsWith("ACTION:", true) -> line.substringAfter(':').trim().takeIf { it.length > 3 }?.let(actions::add)
                    }
                }
            }
        }
        if (actions.isEmpty() && decisions.isEmpty()) {
            val (d, a) = heuristic(source.text)
            decisions += d; actions += a
        }
        if (summary.isBlank()) summary = source.text.split(Regex("(?<=[.!?])\\s+")).filter { it.split(' ').size > 5 }.take(2).joinToString(" ").take(400)

        // To-Do: one task per action item, filed in the follow-up / work-order list when present
        val lists = app.db.tasks().lists(s.activeProfile.name)
        val listId = (lists.firstOrNull { it.name.equals("Follow-ups", true) } ?: lists.firstOrNull { it.name.equals(s.asstWorkOrderList, true) })?.id
        val taskIds = actions.distinct().take(15).map { a ->
            val p = QuickParse.parse(a)
            TaskOps.save(app, TaskEntity(
                profile = s.activeProfile.name, listId = listId, title = a.take(140).replaceFirstChar { it.uppercase() },
                notes = "From meeting: ${source.title} (${Time.dateTime(source.time)})", dueDay = p.dueDay?.toEpochDay(), dueMinute = p.dueMinute,
                tags = "meeting",
            ))
        }

        val e = ContentStore::escape
        val html = buildString {
            append("<h1 class=\"title\">Meeting Minutes — ${e(source.title)}</h1>")
            append("<p><em>${e(Time.dateTime(source.time))} · generated on-device by ${e(s.asstName)}</em></p>")
            append("<h2>Summary</h2><p>${e(summary)}</p>")
            append("<h2>Decisions</h2>")
            append(if (decisions.isEmpty()) "<p>None recorded.</p>" else "<ul>" + decisions.joinToString("") { "<li>${e(it)}</li>" } + "</ul>")
            append("<h2>Action items</h2>")
            append(if (actions.isEmpty()) "<p>None recorded.</p>" else "<ul class=\"checklist\">" + actions.joinToString("") { "<li>${e(it)}</li>" } + "</ul>")
            append("<h2>Transcript</h2>")
            append(ContentStore.textToHtml(source.text))
        }
        val title = "Minutes — ${source.title}".take(80)
        val rel = app.content.writeDocument(s.activeProfile, title, "Meetings", "", html)
        val text = ContentStore.plainText(html)
        val docId = app.db.documents().insert(DocumentEntity(profile = s.activeProfile.name, title = title, folder = "Meetings", filePath = rel,
            preview = ContentStore.preview(text), wordCount = ContentStore.wordCount(text), tags = "meeting"))
        return Result(source, summary, decisions, actions, docId, taskIds)
    }

    private val actionCue = Regex(
        "\\b(i'll|i will|we'll|we will|you'll|he'll|she'll|they'll|will (send|call|follow|check|get|schedule|review|update|fix|order|email|draft|prepare)|need(s)? to|action item|follow up|follow-up|make sure|let's|todo|to-do|assign(ed)? to|by (monday|tuesday|wednesday|thursday|friday|tomorrow|next week|end of))\\b",
        RegexOption.IGNORE_CASE,
    )
    private val decisionCue = Regex("\\b(we decided|decided to|agreed to|we agreed|decision is|the decision|approved|we're going with|going forward)\\b", RegexOption.IGNORE_CASE)

    fun heuristic(text: String): Pair<List<String>, List<String>> {
        val sentences = text.replace("\n", " ").split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.split(' ').size in 4..45 }
        val decisions = sentences.filter { decisionCue.containsMatchIn(it) }.take(10)
        val actions = sentences.filter { actionCue.containsMatchIn(it) && it !in decisions }.take(15)
        return decisions to actions
    }
}
