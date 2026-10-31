package com.blacklab.app.assistant

import android.content.Intent
import com.blacklab.app.AppContainer
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Keys
import com.blacklab.app.core.Profile
import com.blacklab.app.core.SettingsSchema
import com.blacklab.app.core.Time
import com.blacklab.app.data.CeuEntryEntity
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.DocumentEntity
import com.blacklab.app.data.EventEntity
import com.blacklab.app.data.Grades
import com.blacklab.app.data.JournalEntity
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import com.blacklab.app.dictation.DictationService
import com.blacklab.app.recorder.RecState
import com.blacklab.app.recorder.RecorderService
import com.blacklab.app.study.StudyTimer
import com.blacklab.app.ui.tasks.QuickParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class Link(val label: String, val route: String? = null, val url: String? = null)

/** UI-only actions the phone screen performs (camera, scanners). */
enum class UiAction { SCAN_DOCUMENT, SCAN_CODE, NAMEPLATE, GOOGLE_SIGN_IN }

data class Reply(
    val text: String,
    val links: List<Link> = emptyList(),
    val route: String? = null,
    val source: String = "On-device",
    val ui: UiAction? = null,
    val intent: Intent? = null,
    val speak: Boolean = true,
)

class Confirm(val prompt: String, val run: suspend () -> Reply)

data class Msg(val id: Long, val user: Boolean, val text: String, val time: Long, val links: List<Link> = emptyList(), val source: String = "")

enum class Origin { PHONE, DESKTOP, CLOUD }

/**
 * The BlackLab personal assistant. Every request first goes through a deterministic, offline
 * command router that can act on all of BlackLab (tasks, calendar, documents, journal, recorder,
 * dictation, camera, education, settings, files). Gemini Nano (on-device) handles phrasing the
 * router doesn't recognize by rewriting it into a command, and answers open questions. Google
 * services (Gmail, Drive, developer docs) and ElevenLabs are used only when online and allowed.
 */
class Assistant(private val app: AppContainer) {

    val index = LocalIndex(app)
    val busy = MutableStateFlow(false)
    val pending = MutableStateFlow<Confirm?>(null)
    private val _events = MutableSharedFlow<Reply>(extraBufferCapacity = 8)
    /** Replies the phone UI should act on (navigate, launch an intent, run a scanner). */
    val events = _events.asSharedFlow()

    private val historyFile = File(app.context.filesDir, "assistant/history.json")
    val messages = MutableStateFlow(loadHistory())

    private val s get() = app.settings.current
    private val profile get() = s.activeProfile

    // ------------------------------------------------------------------ conversation

    suspend fun handle(input: String, origin: Origin = Origin.PHONE): Reply {
        val text = input.trim()
        if (text.isBlank()) return Reply("")
        append(Msg(System.nanoTime(), true, text, Time.now(), source = origin.name.lowercase()))
        busy.value = true
        val reply = try {
            withContext(Dispatchers.IO) { route(text, origin, allowNano = true) }
        } catch (e: Exception) {
            Reply("Whoa, something went sideways there: ${e.message ?: e.javaClass.simpleName}", source = "Error")
        } finally {
            busy.value = false
        }
        append(Msg(System.nanoTime(), false, reply.text, Time.now(), reply.links, reply.source))
        if (origin == Origin.PHONE) _events.tryEmit(reply)
        return reply
    }

    /** Adds an assistant message produced outside [handle] (e.g. after a camera capture). */
    fun post(text: String, links: List<Link> = emptyList(), source: String = "On-device") =
        append(Msg(System.nanoTime(), false, text, Time.now(), links, source))

    fun clearHistory() { messages.value = emptyList(); saveHistory() }

    private fun append(m: Msg) {
        messages.value = (messages.value + m).takeLast(s.asstHistory.coerceAtLeast(20))
        saveHistory()
    }

    private fun loadHistory(): List<Msg> = runCatching {
        val arr = JSONArray(historyFile.readText())
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val links = o.optJSONArray("links") ?: JSONArray()
            Msg(o.getLong("id"), o.getBoolean("user"), o.getString("text"), o.getLong("time"),
                (0 until links.length()).map { links.getJSONObject(it) }.map { Link(it.getString("label"), it.optString("route").ifBlank { null }, it.optString("url").ifBlank { null }) },
                o.optString("source"))
        }
    }.getOrDefault(emptyList())

    private fun saveHistory() {
        runCatching {
            historyFile.parentFile?.mkdirs()
            historyFile.writeText(JSONArray().apply { messages.value.forEach { put(it.toJson()) } }.toString())
        }
    }

    // ------------------------------------------------------------------ persona

    private fun casual(vararg lines: String): String = if (s.asstPersona == "LAIDBACK") lines.random() else ""
    private fun done(base: String): String = when (s.asstPersona) {
        "LAIDBACK" -> listOf("Done — ", "Got it — ", "Easy. ", "Boom, ", "All set — ").random() + base.replaceFirstChar { it.lowercase() }
        "CONCISE" -> base
        else -> base
    }

    // ------------------------------------------------------------------ router

    private fun normalize(raw: String): String {
        var t = raw.trim().trimEnd('?', '!', '.').trim()
        val lead = Regex("^(hey|hi|ok|okay|yo)?\\s*(${Regex.escape(s.asstName.lowercase())}|blacklab|black lab)?[,\\s]*" +
            "(please |can you |could you |would you |will you |i need you to |i want you to |go ahead and |i'd like to |i want to |let's )*", RegexOption.IGNORE_CASE)
        t = t.replace(lead, "")
        return t.removeSuffix(" please").trim()
    }

    private suspend fun route(raw: String, origin: Origin, allowNano: Boolean): Reply {
        val t = normalize(raw)
        val l = t.lowercase(Locale.US)

        // Confirmation of a pending action
        pending.value?.let { p ->
            if (Regex("^(yes|yeah|yep|sure|confirm|do it|send it|go ahead|ok|okay|absolutely)\\b").containsMatchIn(l)) { pending.value = null; return p.run() }
            if (Regex("^(no|nope|cancel|stop|never ?mind|don't)\\b").containsMatchIn(l)) { pending.value = null; return Reply("Cancelled. " + casual("No worries.", "All good.", "")) }
        }

        fun m(re: String) = Regex(re, RegexOption.IGNORE_CASE).find(t)

        // ---- help & small talk
        if (Regex("^(help|what can you do|commands|what do you do|how do you work)").containsMatchIn(l)) return help()
        if (Regex("^(thanks|thank you|thx|cheers|appreciate)").containsMatchIn(l)) return Reply(casual("Anytime, man.", "You got it.", "Happy to help.").ifBlank { "You're welcome." }, speak = true)
        if (Regex("^(hi|hello|hey|yo|sup|what's up|good (afternoon|evening))$").containsMatchIn(l)) return Reply("${Time.greeting()}${s.userName.substringBefore(' ').takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""}. " +
            casual("What are we getting into?", "What's the plan?", "What do you need?").ifBlank { "How can I help?" })
        if (Regex("what time is it|current time|time is it").containsMatchIn(l)) return Reply("It's ${Time.time(Time.now())}.")
        if (Regex("what('s| is) (the |today's )?date|what day is (it|today)").containsMatchIn(l)) return Reply("Today is ${Time.longDate(Time.today())}, ${Time.today().year}.")

        // ---- briefing & agenda
        if (Regex("^(brief me|briefing|(daily|morning) brief(ing)?|give me (my |the |a )?(daily |morning )?brief(ing)?)|good morning|morning report|how does my day look|what's my day|daily summary|catch me up").containsMatchIn(l))
            return Reply(Briefing.build(app, s.asstPersona), listOf(Link("Schedule", "schedule"), Link("To-Do", "tasks")))

        // ---- create event (before agenda so "schedule a meeting" isn't read as a question)
        m("^(?:schedule|book|set up|add|create|put|plan)\\s+(?:an?\\s+)?(?:new\\s+)?(meeting|event|appointment|call|lunch|session|interview|inspection|walkthrough|class)?\\s*(.*)$")
            ?.takeIf { it.groupValues[1].isNotBlank() || Regex("on (my )?calendar|^schedule|^book").containsMatchIn(l) }
            ?.let { return createEvent(it.groupValues[1], it.groupValues[2]) }

        if (Regex("(agenda|calendar|schedule|meetings?|events?|appointments?)").containsMatchIn(l) &&
            Regex("^(what|what's|whats|show|read|list|do i have|anything|any|how|am i|is there|tell)").containsMatchIn(l))
            return agenda(l)

        // ---- recorder & dictation
        if (Regex("^(start|begin) (dictation|dictating)|^dictate").containsMatchIn(l)) { DictationService.send(app.context, DictationService.ACTION_START); return Reply(done("dictation's rolling. Say “stop recording” when you're done."), route = "dictation") }
        if (Regex("^(start|begin) (an? )?(audio )?(recording|recorder)|^record (audio|this|the meeting|a meeting|a voice memo)").containsMatchIn(l)) {
            RecorderService.send(app.context, RecorderService.ACTION_START); return Reply(done("recording started."), route = "recorder")
        }
        if (Regex("^(stop|end|finish|save) (the )?(recording|recorder|dictation|dictating)").containsMatchIn(l)) {
            val wasDict = DictationService.state.value.active
            if (wasDict) DictationService.send(app.context, DictationService.ACTION_STOP)
            if (RecorderService.state.value.status != RecState.Status.IDLE) RecorderService.send(app.context, RecorderService.ACTION_STOP)
            return Reply(done("stopped and saved."), route = if (wasDict) "dictation" else "recorder")
        }
        if (Regex("^(pause) (the )?(recording|recorder|dictation)").containsMatchIn(l)) {
            if (DictationService.state.value.active) DictationService.send(app.context, DictationService.ACTION_PAUSE) else RecorderService.send(app.context, RecorderService.ACTION_PAUSE)
            return Reply("Paused.")
        }
        if (Regex("^(add a |drop a |set a )?(marker|bookmark|mark that)").containsMatchIn(l)) {
            if (DictationService.state.value.active) DictationService.send(app.context, DictationService.ACTION_MARK) else RecorderService.send(app.context, RecorderService.ACTION_MARK)
            return Reply("Marked.")
        }

        // ---- tasks
        m("^(?:remind me(?: to)?|don't let me forget(?: to)?)\\s+(.+)$")?.let { return addTask(it.groupValues[1], remind = true) }
        m("^(?:add|create|new|make)(?: a| an)?(?: new)? (?:task|to-?do|todo|reminder)(?: to| for| called| named)?[:\\s]+(.+)$")?.let { return addTask(it.groupValues[1], remind = false) }
        m("^(?:add|put) (.+?) (?:to|on) (?:my )?(?:to-?do|todo|tasks|task list|list|my day)$")?.let { return addTask(it.groupValues[1], remind = false, myDay = l.endsWith("my day")) }
        m("^(?:to-?do|todo|task)[:\\s]+(.+)$")?.let { return addTask(it.groupValues[1], remind = false) }
        m("^(?:complete|finish|mark|check off|tick off|close|i (?:finished|completed|did))(?: the)?(?: task)? (.+?)(?: as)?(?: done| complete| completed| finished)?$")
            ?.let { return completeTask(it.groupValues[1]) }
        if (Regex("(tasks?|to-?dos?|to do list|what's due|whats due|overdue|my day)").containsMatchIn(l) &&
            Regex("^(what|what's|whats|show|read|list|do i have|any|how many|tell|my|tasks)").containsMatchIn(l))
            return listTasks(l)

        // ---- feature: Meeting → Actions
        if (Regex("(action items?|actions|minutes|summar(y|ize|ise)|follow[- ]?ups?)").containsMatchIn(l) && Regex("(meeting|dictation|recording|call|transcript)").containsMatchIn(l))
            return meetingActions()

        // ---- feature: Snap-to-Work-Order
        if (Regex("(name ?plate|data ?plate|equipment (label|tag)|work order from|snap .*work order|scan (the |this |an? )?(equipment|unit|motor|boiler|chiller|rtu|pump))").containsMatchIn(l))
            return if (origin == Origin.PHONE) Reply("Point the camera at the nameplate — I'll read it and write up the work order.", ui = UiAction.NAMEPLATE)
            else Reply("That one needs the phone camera. Open BlackLab on your phone and say “nameplate”.")

        // ---- Google Keep (via the Keep app)
        m("^(?:save|add|put|make|create|take)?\\s*(?:a )?(?:note )?(?:to|in|on) (?:google )?keep[:\\s]*(.*)$|^keep note[:\\s]+(.+)$|^(?:note|remember) (.+?) (?:in|to|on) (?:google )?keep$")
            ?.let { r -> return keep(r.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty(), origin) }

        // ---- notes & documents
        m("^(?:take a note|make a note|note to self|note|jot(?: down)?|write down|save (?:a )?note|new note|create (?:a )?(?:note|document|doc))(?: that| about| saying)?[:\\s]+(.+)$")
            ?.let { return note(it.groupValues[1]) }
        m("^(?:create|new|start|write|draft) (?:an? )?(memo|work order|incident report|meeting minutes|sop|inspection|pm log|project brief|vendor evaluation|budget request|weekly status|status report|shift handover|letter|checklist|cornell notes|travel plan)\\b")
            ?.let { return template(it.groupValues[1]) }

        // ---- journal
        m("^(?:journal|dear diary|diary|log (?:in|to) (?:my )?journal|add (?:a )?journal entry|new journal entry)(?: that| about)?[:,\\s]+(.+)$")?.let { return journal(it.groupValues[1]) }
        if (Regex("^(open|new|start) (a |my )?journal").containsMatchIn(l)) return Reply("Opening a fresh journal page.", route = "journal/0")

        // ---- camera & scanners
        if (Regex("^(scan|digitize) (a |this |the )?(document|page|paper|receipt|form)").containsMatchIn(l)) return if (origin == Origin.PHONE) Reply("Opening the document scanner.", ui = UiAction.SCAN_DOCUMENT) else Reply("Scanning needs the phone.")
        if (Regex("^(scan) (a |the |this )?(qr|barcode|code|asset tag)").containsMatchIn(l)) return if (origin == Origin.PHONE) Reply("Opening the code scanner.", ui = UiAction.SCAN_CODE) else Reply("Scanning needs the phone.")
        if (Regex("(take|snap|grab) (a |an )?(photo|picture|pic|shot)|open (the )?camera").containsMatchIn(l)) return Reply("Camera's up.", route = "camera?mode=photo")
        if (Regex("(record|shoot|take) (a )?video").containsMatchIn(l)) return Reply("Video mode.", route = "camera?mode=video")
        if (Regex("panorama|pano\\b").containsMatchIn(l)) return Reply("Panorama mode — sweep slow and steady.", route = "camera?mode=pano")

        // ---- education
        if (Regex("^(start|begin) (a )?(focus|pomodoro|study)( session| timer)?").containsMatchIn(l)) {
            StudyTimer.get(app.context).start(profile.name)
            return Reply(done("${s.pomodoroWork}-minute focus timer started. " + casual("Phone down, brain on.", "Lock in.", "")), route = "education?tab=Study")
        }
        m("^log (\\d+(?:\\.\\d+)?) (?:ceus?|pdhs?|hours?|credit hours?)(?: of (.+?))? (?:for|toward|towards|to) (.+)$")?.let { return logCeu(it.groupValues[1].toDouble(), it.groupValues[2], it.groupValues[3]) }
        if (Regex("\\b(gpa|grade point|my grades?)\\b").containsMatchIn(l)) return gpa()

        // ---- account / appearance / settings
        m("^(?:switch|change|go) to (?:my )?(personal|professional|work)(?: account| profile| mode)?$")?.let {
            val p = if (it.groupValues[1].lowercase() == "personal") Profile.PERSONAL else Profile.PROFESSIONAL
            if (p == Profile.PROFESSIONAL && s.lockProfessional && origin == Origin.PHONE) return Reply("Switch accounts from the drawer — that one's locked.")
            app.settings.setProfile(p); return Reply(done("you're in ${app.settings.profileName(p)}."))
        }
        m("^(?:turn on |switch to |use |enable )?(dark|light|system) (?:mode|theme)$")?.let {
            app.settings.update { putString(Keys.THEME_MODE, it.groupValues[1].uppercase()) }; return Reply(done("${it.groupValues[1]} mode on."))
        }
        m("^(?:set|change|make)\\s+(?:the |my )?(.+?)\\s+(?:to|=)\\s+(.+)$")?.let { r -> setting(r.groupValues[1], r.groupValues[2], origin)?.let { return it } }
        m("^(turn on|turn off|enable|disable|switch on|switch off)\\s+(?:the |my )?(.+)$")?.let { r ->
            setting(r.groupValues[2], if (r.groupValues[1].lowercase().let { it.endsWith("on") || it == "enable" }) "on" else "off", origin)?.let { return it }
        }

        // ---- Gmail
        if (Regex("(check|read|any|summari[sz]e|what's in|whats in|new|unread).*(e-?mails?|inbox|gmail|mail)|^(e-?mails?|inbox|gmail)$").containsMatchIn(l) &&
            !Regex("^(send|write|draft)").containsMatchIn(l))
            return gmailSearch("is:unread in:inbox", "unread")
        m("^(?:search|find|look for|look up|show)(?: my)? (?:e-?mails?|gmail|mail|inbox)(?: for| about| from)? (.+)$|^(?:e-?mails?|mail) from (.+)$")
            ?.let { r -> val q = r.groupValues.drop(1).first { it.isNotBlank() }; return gmailSearch(if (l.contains(" from ")) "from:($q)" else q, q) }
        m("^(send|write|draft|compose)(?: an?)?(?: e-?mail| message)? to (\\S+@\\S+)(?: (?:about|subject|re|regarding)[:\\s]+(.+?))?(?: (?:saying|that says|body|message)[:\\s]+(.+))?$")
            ?.let { r -> return email(r.groupValues[1].lowercase(), r.groupValues[2].trimEnd(',', '.'), r.groupValues[3], r.groupValues[4]) }

        // ---- Google Drive
        m("^(?:search|find|look for|look up)(?: for)? (.+?) (?:in|on) (?:my )?(?:google )?drive$|^(?:google )?drive (?:search )?(?:for )?(.+)$")
            ?.let { r -> return driveSearch(r.groupValues.drop(1).first { it.isNotBlank() }) }
        if (Regex("(upload|back ?up|save|send|copy) .*(to|on|in) (my )?(google )?drive").containsMatchIn(l)) return driveUpload(l)

        // ---- Developer docs (Developer Knowledge API)
        m("^(?:search |look up |check )?(?:the )?(?:android|kotlin|compose|jetpack|firebase|google cloud|gcp|developer|dev) docs?(?: for| about| on)?[:\\s]+(.+)$|^how do i (.+) in (?:android|kotlin|jetpack compose|compose|firebase)$")
            ?.let { r -> return devDocs(r.groupValues.drop(1).first { it.isNotBlank() }) }

        // ---- navigation
        m("^(?:open|go to|show(?: me)?|launch|take me to|pull up)(?: the| my)? (home|dashboard|tasks|to-?do|todos|schedule|calendar|education|learn|courses|certifications|library|files|photos|videos|recordings|scans|documents|docs|journal|recorder|dictation|settings|camera|assistant)$")
            ?.let { r -> return navigate(r.groupValues[1].lowercase()) }

        // ---- feature: Ask my files
        m("^(?:search|find|look up|look for|ask my files|search my (?:files|notes|docs|documents))(?: for| about)?[:\\s]+(.+)$")?.let { return askFiles(it.groupValues[1]) }
        if (Regex("^(what|when|where|who|which|did|how)\\b.*\\b(i|my|we|our)\\b.*\\b(write|wrote|say|said|note|noted|record|recorded|mention|mentioned|decide|decided|discuss|discussed|journal|meeting)").containsMatchIn(l))
            return askFiles(t)

        // ---- fall through to Gemini Nano
        if (allowNano && s.asstUseNano) nano(t, origin)?.let { return it }
        val hits = run { index.ensure(); index.search(t, 3, profile.name) }
        if (hits.isNotEmpty() && hits.first().score > 2.5) return filesReply(t, hits, extractive = true)
        return Reply(
            "I didn't catch a command in that. " + (if (Nano.status.value != Nano.Status.AVAILABLE && s.asstUseNano) "On-device AI is ${Nano.status.value.label.lowercase()}, so I'm on rules only right now. " else "") +
                "Try “add task…”, “what's on my calendar”, “brief me”, “search my files for…”, or say “help”.",
        )
    }

    // ------------------------------------------------------------------ Gemini Nano

    private suspend fun nano(text: String, origin: Origin): Reply? {
        if (Nano.status.value == Nano.Status.UNKNOWN) Nano.refresh()
        if (!Nano.ready) return null
        val today = Time.today()
        val ctx = buildString {
            append("Today is ${Time.longDate(today)}, ${today.year}, ${Time.time(Time.now())}. User: ${s.userName.ifBlank { "the user" }}")
            if (s.jobTitle.isNotBlank()) append(", ${s.jobTitle}")
            append(". Account: ${app.settings.profileName(profile)}.\n")
            val agenda = Briefing.agenda(app, today, today.plusDays(1)).take(5)
            if (agenda.isNotEmpty()) append("Upcoming: " + agenda.joinToString("; ") { "${it.title} ${Time.relativeDay(it.start.toLocalDate())} ${Time.time(it.start)}" } + "\n")
            val tasks = Briefing.openTasks(app).sortedBy { it.dueDay ?: Long.MAX_VALUE }.take(5)
            if (tasks.isNotEmpty()) append("Open tasks: " + tasks.joinToString("; ") { it.title } + "\n")
        }
        val persona = when (s.asstPersona) {
            "LAIDBACK" -> "You are ${s.asstName}, a laid-back, warm, funny personal assistant with an easygoing, chuckling vibe. Keep it short, friendly and a little playful, but always useful."
            "PROFESSIONAL" -> "You are ${s.asstName}, a polished, professional executive assistant. Be brief and precise."
            else -> "You are ${s.asstName}. Answer in as few words as possible."
        }
        val prompt = """$persona
You live inside the BlackLab app on the user's phone and can run these commands:
add task <text with date/time> | remind me to <text> | schedule <event> <day> at <time> | what's on my calendar <today/tomorrow/this week> | what tasks are due |
complete <task> | brief me | note <text> | journal <text> | start dictation | start recording | stop recording | take a photo | scan document |
nameplate | start focus | search my files for <words> | action items from my last meeting | check email | search drive for <words> |
android docs <question> | switch to personal/professional | open <screen> | log <n> CEUs for <certification>.

If the user's message is a request one of these commands can do, reply with exactly one line: COMMAND: <the command>
Otherwise answer the user directly in 1-4 sentences, starting with: ANSWER:

$ctx
User: $text"""
        val out = Nano.generate(prompt, s.asstNanoTemp / 100f, s.asstNanoTokens) ?: return null
        val cmd = Regex("COMMAND:\\s*(.+)", RegexOption.IGNORE_CASE).find(out)?.groupValues?.get(1)?.lineSequence()?.first()?.trim()
        if (!cmd.isNullOrBlank()) {
            val r = route(cmd, origin, allowNano = false)
            return r.copy(source = "Gemini Nano → ${r.source}")
        }
        val answer = out.substringAfter("ANSWER:", out).trim().removePrefix("ANSWER").trim()
        return if (answer.isBlank()) null else Reply(answer, source = "Gemini Nano · on-device")
    }

    // ------------------------------------------------------------------ actions

    private fun help() = Reply(
        """Here's what I can do — all of it works offline unless marked 🌐:
• Tasks — “remind me to call the chiller vendor tomorrow 9am”, “what's due”, “complete boiler inspection”
• Calendar — “schedule safety walkthrough Friday at 2pm for 90 minutes”, “what's on my calendar this week”
• Briefing — “brief me” for today's events, tasks, deadlines and renewals
• Notes & docs — “note: east stairwell light out”, “create a work order”, “journal: …”
• Ask my files — “search my files for roof warranty”, “what did we decide about the RTU replacement?”
• Meeting → Actions — “action items from my last meeting”
• Snap-to-Work-Order — “nameplate” and point the camera at equipment
• Recorder & dictation — “start dictation”, “start recording”, “stop recording”, “mark that”
• Camera & scans — “take a photo”, “scan a document”, “scan a QR code”
• Learning — “start focus”, “log 2 CEUs for CFM”, “what's my GPA”
• Settings — “dark mode”, “switch to professional”, “set focus length to 45”
• 🌐 Gmail — “check email”, “search email for invoice”, “draft email to x@y.com about … saying …”
• 🌐 Drive — “search drive for budget”, “back up to drive”
• 🌐 Google Keep — “save to keep: …” · 🌐 Android docs — “android docs camera extensions”""",
        speak = false,
    )

    private suspend fun addTask(rawText: String, remind: Boolean, myDay: Boolean = false): Reply {
        val p = QuickParse.parse(rawText)
        val lists = app.db.tasks().lists(profile.name)
        val listId = p.listName?.let { n -> lists.firstOrNull { it.name.replace(" ", "").startsWith(n.replace(" ", ""), true) }?.id }
        val minute = p.dueMinute ?: if (remind && p.dueDay != null) s.defaultTaskReminderMinute else null
        var day = p.dueDay ?: if (remind && p.dueMinute != null) Time.today() else null
        // "remind me at 8am" after 8am means tomorrow
        if (day != null && minute != null && p.dueDay == null && Time.millis(day, minute) <= Time.now()) day = day.plusDays(1)
        val reminderAt = if (day != null && minute != null) Time.millis(day, minute) else null
        val finalDay = day
        val title = p.title.ifBlank { rawText }.replaceFirstChar { it.uppercase() }
        val id = TaskOps.save(app, TaskEntity(
            profile = profile.name, title = title, listId = listId, dueDay = finalDay?.toEpochDay(), dueMinute = minute, reminderAt = reminderAt,
            priority = p.priority, important = p.important, tags = p.tags.joinToString(","), repeatRule = p.repeat,
            myDayDay = if (myDay || finalDay == Time.today()) Time.today().toEpochDay() else null,
        ))
        val whenText = finalDay?.let { " for ${Time.relativeDay(it).lowercase()}" + (minute?.let { m -> " at ${Time.time(m)}" } ?: "") } ?: ""
        val extra = listOfNotNull(
            if (reminderAt != null) "I'll ping you" else null,
            p.repeat?.let { "repeats" }, if (p.priority >= 3) "high priority" else null,
        ).joinToString(", ")
        return Reply(done("added “$title”$whenText.") + if (extra.isNotBlank()) " ($extra)" else "", listOf(Link("Open task", "task/$id")))
    }

    private suspend fun findTask(query: String): TaskEntity? {
        val q = LocalIndex.tokens(query).toSet()
        if (q.isEmpty()) return null
        return Briefing.openTasks(app).map { t -> t to LocalIndex.tokens(t.title).count { it in q }.toDouble() / q.size }
            .filter { it.second >= 0.5 }.maxByOrNull { it.second }?.first
    }

    private suspend fun completeTask(query: String): Reply {
        val t = findTask(query) ?: return Reply("Couldn't find an open task matching “$query”.", listOf(Link("To-Do", "tasks")))
        TaskOps.setCompleted(app, t, true)
        return Reply(done("checked off “${t.title}”.") + casual(" Nice.", " One less thing.", ""), listOf(Link("Open task", "task/${t.id}")))
    }

    private suspend fun listTasks(l: String): Reply {
        val today = Time.today().toEpochDay()
        val open = Briefing.openTasks(app)
        val (label, list) = when {
            l.contains("overdue") -> "overdue" to open.filter { it.dueDay != null && it.dueDay < today }
            l.contains("tomorrow") -> "due tomorrow" to open.filter { it.dueDay == today + 1 }
            l.contains("week") -> "due this week" to open.filter { it.dueDay != null && it.dueDay <= today + 7 }
            l.contains("important") -> "important" to open.filter { it.important }
            else -> "due today or overdue" to open.filter { (it.dueDay != null && it.dueDay <= today) || it.myDayDay == today }
        }
        if (list.isEmpty()) return Reply("Nothing $label. " + casual("You're crushing it.", "Clean slate.", "") + if (open.isNotEmpty()) " (${open.size} open overall.)" else "", listOf(Link("To-Do", "tasks")))
        val lines = list.sortedWith(compareBy<TaskEntity> { it.dueDay ?: Long.MAX_VALUE }.thenByDescending { it.priority }).take(10).joinToString("\n") { t ->
            "• ${t.title}" + (t.dueDay?.let { d -> " — ${Time.relativeDay(LocalDate.ofEpochDay(d))}" + (t.dueMinute?.let { " ${Time.time(it)}" } ?: "") } ?: "")
        }
        return Reply("${list.size} task(s) $label:\n$lines", listOf(Link("To-Do", "tasks")))
    }

    private suspend fun createEvent(kind: String, rest: String): Reply {
        var text = rest.replace(Regex("\\bon (my )?calendar\\b", RegexOption.IGNORE_CASE), "").trim()
        var minutes = s.defaultEventMinutes
        Regex("\\bfor (\\d+(?:\\.\\d+)?|an?|half an?) ?(hours?|hrs?|minutes?|mins?)\\b", RegexOption.IGNORE_CASE).find(text)?.let { r ->
            val n = when (r.groupValues[1].lowercase()) { "a", "an" -> 1.0; "half a", "half an" -> 0.5; else -> r.groupValues[1].toDouble() }
            minutes = if (r.groupValues[2].startsWith("h", true)) (n * 60).toInt() else n.toInt()
            text = text.removeRange(r.range).trim()
        }
        var location = ""
        Regex("\\b(?:at|in) ((?:the |room |building |bldg )[^,]+?)(?= on | at |$)", RegexOption.IGNORE_CASE).find(text)?.let { r ->
            location = r.groupValues[1].trim(); text = text.removeRange(r.range).trim()
        }
        val p = QuickParse.parse(text)
        val day = p.dueDay ?: Time.today()
        val startMinute = p.dueMinute ?: ((LocalTime.now().hour + 1) * 60).coerceAtMost(23 * 60)
        val title = (p.title.ifBlank { kind.ifBlank { "Event" } }).let { tt ->
            if (kind.isNotBlank() && !tt.contains(kind, true) && kind.lowercase() !in setOf("event", "appointment")) "${kind.replaceFirstChar { it.uppercase() }}: $tt" else tt
        }.replaceFirstChar { it.uppercase() }
        val start = Time.millis(day, startMinute)
        val calendars = app.db.events().calendars(profile.name)
        val cal = calendars.firstOrNull { c -> kind.isNotBlank() && c.name.contains(kind, true) } ?: calendars.firstOrNull()
        val id = app.db.events().insert(EventEntity(
            profile = profile.name, calendarId = cal?.id, title = title, location = location, start = start, end = start + minutes * 60_000L,
            reminders = s.defaultEventReminder.toString(),
        ))
        app.requestReminderSync()
        val conflicts = Briefing.agenda(app, day, day).filter { it.route != "event/$id" && it.kind != "Due" &&
            it.start < Time.toLdt(start + minutes * 60_000L) && it.end > Time.toLdt(start) }
        return Reply(
            done("“$title” is on the calendar ${Time.relativeDay(day).lowercase()} at ${Time.time(startMinute)} for ${Time.minutesLabel(minutes)}.") +
                if (conflicts.isNotEmpty()) " Heads up — it overlaps ${conflicts.joinToString { "“${it.title}”" }}." else "",
            listOf(Link("Open event", "event/$id"), Link("Schedule", "schedule")),
        )
    }

    private suspend fun agenda(l: String): Reply {
        val today = Time.today()
        val (from, to, label) = when {
            l.contains("tomorrow") -> Triple(today.plusDays(1), today.plusDays(1), "tomorrow")
            l.contains("next week") -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).let { Triple(it, it.plusDays(6), "next week") }
            l.contains("week") -> Triple(today, today.plusDays(6), "the next 7 days")
            else -> Regex("(monday|tuesday|wednesday|thursday|friday|saturday|sunday)").find(l)?.let { r ->
                val d = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.valueOf(r.value.uppercase())))
                Triple(d, d, Time.dayFull(d.dayOfWeek))
            } ?: Triple(today, today, "today")
        }
        val items = Briefing.agenda(app, from, to)
        if (items.isEmpty()) return Reply("Nothing on the calendar $label. " + casual("Wide open.", "Free and clear.", ""), listOf(Link("Schedule", "schedule")))
        val text = if (from == to) Briefing.describe(items, from)
        else items.groupBy { it.start.toLocalDate() }.entries.joinToString("\n\n") { (d, list) -> Time.relativeDay(d) + ":\n" + Briefing.describe(list, d) }
        return Reply("${items.size} item(s) $label:\n$text", listOf(Link("Schedule", "schedule")))
    }

    private suspend fun note(text: String): Reply {
        val title = text.split(Regex("\\s+")).take(7).joinToString(" ").trimEnd(',', '.', ':').replaceFirstChar { it.uppercase() }
        val html = "<p>${ContentStore.escape(text.replaceFirstChar { it.uppercase() })}</p><p><em>Noted ${Time.dateTime(Time.now())} via ${ContentStore.escape(s.asstName)}</em></p>"
        val rel = app.content.writeDocument(profile, title, "Notes", "", html)
        val id = app.db.documents().insert(DocumentEntity(profile = profile.name, title = title, folder = "Notes", filePath = rel,
            preview = ContentStore.preview(text), wordCount = ContentStore.wordCount(text), tags = "note"))
        return Reply(done("saved a note: “$title”."), listOf(Link("Open note", "doc/$id")))
    }

    private fun template(name: String): Reply {
        val id = when (name.lowercase()) {
            "memo" -> "memo"; "work order" -> "workorder"; "incident report" -> "incident"; "meeting minutes" -> "meeting"; "sop" -> "sop"
            "inspection" -> "inspection"; "pm log" -> "pmlog"; "project brief" -> "brief"; "vendor evaluation" -> "vendor"; "budget request" -> "budget"
            "weekly status", "status report" -> "weekly"; "shift handover" -> "shift"; "letter" -> "letter"; "checklist" -> "checklist"
            "cornell notes" -> "cornell"; "travel plan" -> "travel"; else -> "blank"
        }
        return Reply("Starting a new ${name.lowercase()}.", route = "doc/0?template=$id")
    }

    private suspend fun journal(text: String): Reply {
        val today = Time.today()
        val html = "<p>${ContentStore.escape(text.replaceFirstChar { it.uppercase() })}</p>"
        val rel = app.content.writeJournal(profile, today, "", "", html)
        val id = app.db.journal().insert(JournalEntity(profile = profile.name, entryDay = today.toEpochDay(), filePath = rel,
            preview = ContentStore.preview(text), wordCount = ContentStore.wordCount(text)))
        return Reply(done("added to today's journal.") + casual(" Proud of you for writing it down.", "", ""), listOf(Link("Open entry", "journal/$id")))
    }

    private suspend fun meetingActions(): Reply {
        val src = MeetingActions.latestSource(app)
            ?: return Reply("I need a transcript first — record a meeting with Dictation (or Recorder with live transcription) and ask again.", listOf(Link("Dictation", "dictation")))
        val r = MeetingActions.run(app, src)
        val text = buildString {
            append("From “${src.title}” (${Time.dateTime(src.time)}):\n\n")
            append(r.summary.ifBlank { "No summary." })
            if (r.decisions.isNotEmpty()) append("\n\nDecisions:\n" + r.decisions.take(6).joinToString("\n") { "• $it" })
            append(if (r.actions.isEmpty()) "\n\nNo action items jumped out." else "\n\nAction items → ${r.taskIds.size} task(s):\n" + r.actions.take(8).joinToString("\n") { "• $it" })
        }
        return Reply(text, listOf(Link("Minutes", "doc/${r.docId}"), Link("Source", src.route), Link("To-Do", "tasks")),
            source = if (Nano.ready && s.asstUseNano) "Gemini Nano · on-device" else "On-device extractor")
    }

    private suspend fun keep(text: String, origin: Origin): Reply {
        if (text.isBlank()) return Reply("What should I put in Keep? Say “save to keep: …”.")
        val title = text.split(Regex("\\s+")).take(6).joinToString(" ")
        if (origin != Origin.PHONE || !Keep.installed(app.context)) {
            // Fall back to a BlackLab note so nothing is lost
            val r = note(text)
            return r.copy(text = if (origin != Origin.PHONE) "Saved it as a BlackLab note — Keep can only be reached from the phone app." else "Keep isn't installed, so I saved it as a BlackLab note instead.")
        }
        return Reply("Sending to Google Keep — tap Save.", intent = Keep.intent(title, text), source = "Google Keep")
    }

    private fun canOnline(): String? = when {
        s.asstOfflineOnly -> "Offline-only mode is on, so I can't reach Google right now."
        !app.context.isOnline() -> "No connection right now — that one needs the internet."
        else -> null
    }

    private suspend fun googleToken(): String? = GoogleAuth.token(app.context)

    private fun signInReply() = Reply("Sign in with Google first (Settings › Assistant › Google account) so I can reach Gmail and Drive.", ui = UiAction.GOOGLE_SIGN_IN,
        links = listOf(Link("Assistant settings", "settings")))

    private suspend fun gmailSearch(query: String, label: String): Reply {
        canOnline()?.let { return Reply(it) }
        val token = googleToken() ?: return signInReply()
        val mails = Gmail.search(token, query, s.asstGmailMax).getOrElse { return Reply("Gmail said: ${it.message}", source = "Gmail") }
        if (mails.isEmpty()) return Reply(if (label == "unread") "Inbox zero on unread. " + casual("Look at you.", "Beautiful.", "") else "No emails matching “$label”.", source = "Gmail")
        val lines = mails.joinToString("\n") { "• ${it.from}: ${it.subject.ifBlank { "(no subject)" }} — ${it.snippet.take(90)}" }
        val head = if (label == "unread") "${mails.size}${if (mails.size >= s.asstGmailMax) "+" else ""} unread:" else "${mails.size} email(s) for “$label”:"
        val summary = if (Nano.ready && s.asstUseNano && mails.size > 2) Nano.generate("Summarize these emails in two short sentences for a busy person. Mention anything urgent.\n$lines", 0.2f, 120) else null
        return Reply(listOfNotNull(summary, "$head\n$lines").joinToString("\n\n"), listOf(Link("Open Gmail", url = "https://mail.google.com/mail/u/0/#search/" + android.net.Uri.encode(query))), source = "Gmail")
    }

    private suspend fun email(verb: String, to: String, subjectIn: String, bodyIn: String): Reply {
        canOnline()?.let { return Reply(it) }
        val token = googleToken() ?: return signInReply()
        val body = bodyIn.ifBlank { subjectIn }
        val subject = subjectIn.ifBlank { body.split(' ').take(6).joinToString(" ") }.replaceFirstChar { it.uppercase() }
        val signed = body.replaceFirstChar { it.uppercase() } + (s.userName.takeIf { it.isNotBlank() }?.let { "\n\n— $it" } ?: "")
        if (verb == "draft" || verb == "compose" || verb == "write") {
            Gmail.draft(token, to, subject, signed).getOrElse { return Reply("Couldn't save the draft: ${it.message}", source = "Gmail") }
            return Reply(done("draft to $to saved in Gmail: “$subject”."), listOf(Link("Drafts", url = "https://mail.google.com/mail/u/0/#drafts")), source = "Gmail")
        }
        val send: suspend () -> Reply = {
            Gmail.send(token, to, subject, signed).fold(
                { Reply(done("sent to $to: “$subject”."), source = "Gmail") },
                { Reply("Gmail wouldn't send it: ${it.message}", source = "Gmail") },
            )
        }
        if (!s.asstConfirm) return send()
        pending.value = Confirm("Send to $to?", send)
        return Reply("Ready to send to $to —\nSubject: $subject\n\n$signed\n\nSay “yes” to send or “cancel”.", source = "Gmail", speak = true)
    }

    private suspend fun driveSearch(q: String): Reply {
        canOnline()?.let { return Reply(it) }
        val token = googleToken() ?: return signInReply()
        val files = Drive.search(token, q).getOrElse { return Reply("Drive said: ${it.message}", source = "Google Drive") }
        if (files.isEmpty()) return Reply("Nothing in Drive matching “$q”.", source = "Google Drive")
        return Reply("${files.size} Drive file(s) for “$q”:\n" + files.joinToString("\n") { "• ${it.name}" },
            files.take(5).map { Link(it.name.take(28), url = it.link) }, source = "Google Drive")
    }

    private suspend fun driveUpload(l: String): Reply {
        canOnline()?.let { return Reply(it) }
        val token = googleToken() ?: return signInReply()
        val (file, mime) = when {
            l.contains("backup") || l.contains("back up") -> app.backups.backupNow() to "application/zip"
            l.contains("photo") || l.contains("picture") -> latest(app.storage.dir(profile, Folder.PHOTOS)) to "image/jpeg"
            l.contains("scan") -> latest(app.storage.dir(profile, Folder.SCANS), "pdf") to "application/pdf"
            l.contains("record") -> latest(app.storage.dir(profile, Folder.RECORDINGS)) to "audio/mp4"
            else -> app.db.documents().all().filter { it.profile == profile.name }.maxByOrNull { it.updatedAt }?.let { app.content.file(it.filePath) } to "text/html"
        }
        if (file == null || !file.exists()) return Reply("Couldn't find a file to upload for that.")
        val up = Drive.upload(token, file, s.asstDriveFolder, mime).getOrElse { return Reply("Upload failed: ${it.message}", source = "Google Drive") }
        return Reply(done("uploaded “${up.name}” to Drive › ${s.asstDriveFolder}."), listOf(Link("Open in Drive", url = up.link)), source = "Google Drive")
    }

    private fun latest(dir: File, ext: String? = null): File? = dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && (ext == null || it.extension.equals(ext, true)) }
        .maxByOrNull { it.lastModified() }

    private suspend fun devDocs(q: String): Reply {
        canOnline()?.let { return Reply(it) }
        val key = app.secrets.get(Secrets.DEV_KNOWLEDGE) ?: return Reply("Add your Developer Knowledge API key in Settings › Assistant first.")
        val hits = DevDocs.search(key, q).getOrElse { return Reply("Docs search failed: ${it.message}", source = "Google developer docs") }
        if (hits.isEmpty()) return Reply("No developer docs matched “$q”.", source = "Google developer docs")
        val top = hits.first()
        val summary = if (Nano.ready && s.asstUseNano) Nano.generate("Answer the question using only this documentation excerpt, in 2-3 sentences.\nQuestion: $q\nExcerpt: ${top.text.take(1800)}", 0.2f, 160) else null
        return Reply((summary ?: top.text.take(420) + "…") + "\n\nSources: " + hits.joinToString(", ") { it.title },
            hits.map { Link(it.title.take(30), url = it.url) }, source = "Google developer docs", speak = summary != null)
    }

    private suspend fun logCeu(hours: Double, activity: String, certName: String): Reply {
        val certs = app.db.education().allCerts().filter { it.profile == profile.name }
        val q = certName.lowercase().trim()
        val cert = certs.firstOrNull { it.name.lowercase().startsWith(q) || it.name.lowercase().contains(q) }
            ?: return Reply("Couldn't find a certification called “$certName”." + if (certs.isNotEmpty()) " You have: ${certs.joinToString { it.name }}." else "", listOf(Link("Certifications", "education?tab=Certifications")))
        app.db.education().insertCeu(CeuEntryEntity(certificationId = cert.id, day = Time.today().toEpochDay(), activity = activity.ifBlank { "Logged via ${s.asstName}" }, hours = hours))
        return Reply(done("logged $hours CEU hour(s) toward ${cert.name}."), listOf(Link("Certifications", "education?tab=Certifications")))
    }

    private suspend fun gpa(): Reply {
        val courses = app.db.education().allCourses().filter { it.profile == profile.name }
        val g = Grades.gpa(courses)
        val active = courses.count { it.status == "IN_PROGRESS" }
        return Reply(
            (g.gpa?.let { "Cumulative GPA: ${"%.2f".format(it)} over ${"%.1f".format(g.credits)} credits." } ?: "No completed, graded college courses yet.") +
                " $active course(s) in progress; ${"%.0f".format(g.credits)} of ${"%.0f".format(s.targetCredits)} degree credits done.",
            listOf(Link("Education", "education")),
        )
    }

    private suspend fun setting(name: String, value: String, origin: Origin): Reply? {
        val q = LocalIndex.tokens(name).toSet()
        if (q.isEmpty()) return null
        val def = SettingsSchema.sections.flatMap { it.items }.filter { !it.readOnly }
            .map { it to LocalIndex.tokens(it.label).count { w -> w in q }.toDouble() / maxOf(q.size, LocalIndex.tokens(it.label).size) }
            .filter { it.second >= 0.5 }.maxByOrNull { it.second }?.first ?: return null
        val v = value.trim().lowercase()
        val json: Any = when (def.store) {
            SettingsSchema.Store.BOOL -> when (v) { "on", "true", "yes", "enabled", "enable" -> true; "off", "false", "no", "disabled", "disable" -> false; else -> return null }
            SettingsSchema.Store.INT, SettingsSchema.Store.FLOAT -> {
                if (def.ui == "choice") def.options.firstOrNull { it.second.lowercase().startsWith(v) || it.first == v }?.first?.toInt() ?: return null
                else if (def.ui == "time") parseMinute(v) ?: return null
                else Regex("-?\\d+(\\.\\d+)?").find(v)?.value?.toDouble() ?: return null
            }
            SettingsSchema.Store.STRING -> if (def.ui == "choice") def.options.firstOrNull { it.second.lowercase().startsWith(v) || it.first.lowercase() == v }?.first ?: return null else value.trim()
            SettingsSchema.Store.SET -> return null
        }
        val apply: suspend () -> Reply = {
            val ok = SettingsSchema.apply(app.settings, JSONObject().put(def.key, json)).isNotEmpty()
            app.requestReminderSync()
            if (ok) Reply(done("${def.label} → $value.")) else Reply("That value doesn't fit ${def.label}" + (def.min?.let { " (${it.toInt()}–${def.max?.toInt()})" } ?: "") + ".")
        }
        if (s.asstConfirm && origin != Origin.PHONE) { pending.value = Confirm("Change ${def.label}?", apply); return Reply("Change ${def.label} to $value? Say yes to confirm.") }
        return apply()
    }

    private fun parseMinute(v: String): Int? {
        val r = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(v) ?: return null
        var h = r.groupValues[1].toInt(); val mm = r.groupValues[2].ifBlank { "0" }.toInt()
        if (r.groupValues[3] == "pm" && h < 12) h += 12
        if (r.groupValues[3] == "am" && h == 12) h = 0
        return (h * 60 + mm).takeIf { h in 0..23 && mm in 0..59 }
    }

    private fun navigate(target: String): Reply {
        val route = when (target) {
            "home", "dashboard" -> "home"; "tasks", "to-do", "todo", "todos" -> "tasks"; "schedule", "calendar" -> "schedule"
            "education", "learn", "courses" -> "education"; "certifications" -> "education?tab=Certifications"
            "library", "files" -> "library"; "photos" -> "library?tab=PHOTOS"; "videos" -> "library?tab=VIDEOS"; "recordings" -> "library?tab=RECORDINGS"; "scans" -> "library?tab=SCANS"
            "documents", "docs" -> "documents"; "journal" -> "journal"; "recorder" -> "recorder"; "dictation" -> "dictation"
            "settings" -> "settings"; "camera" -> "camera?mode=photo"; else -> "assistant"
        }
        return Reply("Opening ${target.replaceFirstChar { it.uppercase() }}.", route = route, speak = false)
    }

    suspend fun askFiles(question: String): Reply {
        index.ensure()
        val hits = index.search(question, 6, profile.name)
        if (hits.isEmpty()) return Reply("Nothing in your files matched “$question”. (I searched ${index.size} passages on the phone.)")
        return filesReply(question, hits, extractive = !(Nano.ready && s.asstUseNano))
    }

    private suspend fun filesReply(question: String, hits: List<LocalIndex.Hit>, extractive: Boolean): Reply {
        val links = hits.distinctBy { it.passage.route }.take(4).map { Link("${it.passage.kind}: ${it.passage.title.take(26)}", it.passage.route) }
        if (!extractive) {
            val context = hits.take(5).mapIndexed { i, h -> "[${i + 1}] ${h.passage.kind} “${h.passage.title}” (${Time.date(h.passage.time.coerceAtLeast(1))}): ${h.passage.text.take(600)}" }.joinToString("\n")
            val answer = Nano.generate(
                "Answer the question using only these excerpts from the user's own files. Cite sources like [1]. If the answer isn't there, say so.\n\n$context\n\nQuestion: $question",
                0.2f, s.asstNanoTokens,
            )
            if (!answer.isNullOrBlank()) return Reply(answer, links, source = "Ask my files · Gemini Nano")
        }
        val text = "Here's what I found in your files:\n" + hits.take(3).joinToString("\n\n") { h ->
            val words = LocalIndex.tokens(question).toSet()
            val sentence = h.passage.text.split(Regex("(?<=[.!?])\\s+")).maxByOrNull { sen -> LocalIndex.tokens(sen).count { it in words } } ?: h.passage.text
            "• ${h.passage.kind} — ${h.passage.title}: “${sentence.take(220)}”"
        }
        return Reply(text, links, source = "Ask my files · on-device search", speak = false)
    }
}

private fun Msg.toJson() = JSONObject().apply {
    put("id", id); put("user", user); put("text", text); put("time", time); put("source", source)
    put("links", JSONArray().apply { links.forEach { put(JSONObject().put("label", it.label).put("route", it.route ?: "").put("url", it.url ?: "")) } })
}

fun Msg.toPublicJson(): JSONObject = toJson()
