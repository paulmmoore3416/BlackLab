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
import com.blacklab.app.summarize.Summarizer
import com.blacklab.app.dictation.DictationService
import com.blacklab.app.recorder.RecState
import com.blacklab.app.recorder.RecorderService
import com.blacklab.app.study.StudyTimer
import com.blacklab.app.ui.tasks.QuickParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
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
    /** Address or place to drive to (phone: Google Maps; Android Auto: the car's navigation app). */
    val nav: String? = null,
    /** Phone number to dial. */
    val dial: String? = null,
)

class Confirm(val prompt: String, val run: suspend () -> Reply)

data class Msg(val id: Long, val user: Boolean, val text: String, val time: Long, val links: List<Link> = emptyList(), val source: String = "")

enum class Origin { PHONE, DESKTOP, CLOUD, CAR }

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
        // The phone screen, background work and the desktop link all add messages at once: update atomically
        messages.update { (it + m).takeLast(s.asstHistory.coerceAtLeast(20)) }
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

    @Synchronized
    private fun saveHistory() {
        runCatching {
            historyFile.parentFile?.mkdirs()
            val tmp = File(historyFile.path + ".tmp")
            tmp.writeText(JSONArray().apply { messages.value.forEach { put(it.toJson()) } }.toString())
            if (!tmp.renameTo(historyFile)) tmp.delete()
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
            if (Intents.YES.containsMatchIn(l)) { pending.value = null; return p.run() }
            if (Intents.NO.containsMatchIn(l)) { pending.value = null; return Reply("Cancelled. " + casual("No worries.", "All good.", "")) }
        }

        // ---- commands from the grammar (Intents.kt): small talk, briefing, calendar, recorder
        Intents.matches(t, Intents.Phase.EARLY).forEach { m -> perform(m, t, l, origin)?.let { return it } }

        // ---- Reverse engineering (REA + Ghidra on the computer) — before Facility so "inspect the apk" isn't an inspection
        reverse(t)?.let { return it }

        // ---- Facility: inspections & PMs — before tasks so "what inspections are due" lands here
        facility(l)?.let { return it }
        // ---- Garage: the Santa Fe (fuel, service, odometer, what's due) — before tasks so "what's due on the car" lands here
        garage(t, l)?.let { return it }

        // ---- Behavioral Health: rounds, shift, crisis lines, notes (counts only — never names or rooms)
        behavioral(l)?.let { return it }

        // ---- the desktop: its local AI and its own assistant (files, apps, system status on the computer)
        desktop(t, l)?.let { return it }

        // ---- everything else in the grammar: tasks, notes, camera, settings, Google, navigation, Ask my files
        Intents.matches(t, Intents.Phase.LATE).forEach { m -> perform(m, t, l, origin)?.let { return it } }

        // ---- Expert knowledge bases (software, AI, GitHub, everyday topics) — offline, grounded
        knowledgeAnswer(t)?.let { return it }

        // ---- fall through to Gemini Nano
        if (allowNano && (s.asstUseNano || DesktopAi.usable)) nano(t, origin)?.let { return it }
        val hits = run { index.ensure(); index.search(t, 3, profile.name) }
        if (hits.isNotEmpty() && hits.first().score > 2.5) return filesReply(t, hits, extractive = true)
        return Reply(
            "I didn't catch a command in that. " + (if (Nano.status.value != Nano.Status.AVAILABLE && s.asstUseNano) "On-device AI is ${Nano.status.value.label.lowercase()}, so I'm on rules only right now. " else "") +
                "Try “add task…”, “what's on my calendar”, “brief me”, “search my files for…”, or say “help”.",
        )
    }

    /** Carries out one matched command. Null means "not mine after all" and the next match is tried. */
    private suspend fun perform(m: Intents.Match, t: String, l: String, origin: Origin): Reply? = when (m.id) {
        Intents.Id.HELP -> help()
        Intents.Id.THANKS -> Reply(casual("Anytime, man.", "You got it.", "Happy to help.").ifBlank { "You're welcome." }, speak = true)
        Intents.Id.GREETING -> Reply("${Time.greeting()}${s.userName.substringBefore(' ').takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""}. " +
            casual("What are we getting into?", "What's the plan?", "What do you need?").ifBlank { "How can I help?" })
        Intents.Id.TIME -> Reply("It's ${Time.time(Time.now())}.")
        Intents.Id.DATE -> Reply("Today is ${Time.longDate(Time.today())}, ${Time.today().year}.")
        Intents.Id.BRIEFING -> Reply(Briefing.build(app, s.asstPersona), listOf(Link("Schedule", "schedule"), Link("To-Do", "tasks")))
        Intents.Id.CREATE_EVENT -> createEvent(m[1], m[2])
        Intents.Id.AGENDA -> agenda(l)
        Intents.Id.DICTATE_START -> { DictationService.send(app.context, DictationService.ACTION_START); Reply(done("dictation's rolling. Say “stop recording” when you're done."), route = "dictation") }
        Intents.Id.RECORD_START -> { RecorderService.send(app.context, RecorderService.ACTION_START); Reply(done("recording started."), route = "recorder") }
        Intents.Id.RECORD_STOP -> {
            val wasDict = DictationService.state.value.active
            if (wasDict) DictationService.send(app.context, DictationService.ACTION_STOP)
            if (RecorderService.state.value.status != RecState.Status.IDLE) RecorderService.send(app.context, RecorderService.ACTION_STOP)
            Reply(done("stopped and saved."), route = if (wasDict) "dictation" else "recorder")
        }
        Intents.Id.RECORD_PAUSE -> {
            if (DictationService.state.value.active) DictationService.send(app.context, DictationService.ACTION_PAUSE) else RecorderService.send(app.context, RecorderService.ACTION_PAUSE)
            Reply("Paused.")
        }
        Intents.Id.MARKER -> {
            if (DictationService.state.value.active) DictationService.send(app.context, DictationService.ACTION_MARK) else RecorderService.send(app.context, RecorderService.ACTION_MARK)
            Reply("Marked.")
        }
        Intents.Id.WORK_ORDER_NEW -> workOrder(m.firstGroup)
        Intents.Id.LIBRARY_SEARCH -> library(m.firstGroup)
        Intents.Id.SKILLS -> facilityOps(m, t)
        Intents.Id.SKILL_USE -> {
            val r = DesktopAi.ask(t) ?: return Reply("Skills run on your computer, and I can't reach it right now. Plug in the phone or use Wireless debugging, then run ./daddyshome.", source = "Agent skills")
            Reply(r.text.ifBlank { "Done." }, source = r.source.ifBlank { "Desktop · agent skill" })
        }
        Intents.Id.VAULT_ASK, Intents.Id.SHIFT_PREP, Intents.Id.ROUNDS_START, Intents.Id.PANEL_LOOKUP, Intents.Id.SDS_LOOKUP, Intents.Id.CREDENTIALS -> facilityOps(m, t)
        Intents.Id.OPS_HANDOFF -> Reply(com.blacklab.app.ui.home.DayTimelineData.handoff(app), listOf(Link("Timeline", "timeline")), source = "Timeline")
        Intents.Id.TIMELINE -> com.blacklab.app.ui.home.DayTimelineData.inputs(app, java.time.LocalDate.now()).let { i ->
            val items = com.blacklab.app.core.DayTimeline.build(i).filter { it.kind != com.blacklab.app.core.DayTimeline.Kind.BH }
            Reply(if (items.isEmpty()) "Nothing logged today yet." else "Today so far: " + items.takeLast(8).joinToString("; ") { "${com.blacklab.app.core.DayTimeline.clock(it.time, i.zone)} ${it.kind.label.lowercase()} — ${it.title}" } + ".",
                listOf(Link("Timeline", "timeline")), route = "timeline", source = "Timeline")
        }
        Intents.Id.PRIVATE_DRIVE_SAVE -> privateDrive(t)
        Intents.Id.VAULT_SAVE, Intents.Id.VAULT_NODE_ADD, Intents.Id.VAULT_LOG, Intents.Id.VAULT_SEARCH, Intents.Id.VAULT_STATUS,
        Intents.Id.VAULT_SHOW, Intents.Id.VAULT_OPEN, Intents.Id.VAULT_SYNC -> vault(m)
        Intents.Id.WEATHER -> weather(l)
        Intents.Id.REMIND -> addTask(m[1], remind = true)
        Intents.Id.ADD_TASK, Intents.Id.TODO_PREFIX -> addTask(m[1], remind = false)
        Intents.Id.ADD_TO_LIST -> addTask(m[1], remind = false, myDay = l.endsWith("my day"))
        Intents.Id.COMPLETE_TASK -> completeTask(m[1])
        Intents.Id.LIST_TASKS -> listTasks(l)
        Intents.Id.SUMMARIZE -> summarize(m[1], origin)
        Intents.Id.MEETING_ACTIONS -> meetingActions()
        Intents.Id.NAMEPLATE -> if (origin == Origin.PHONE) Reply("Point the camera at the nameplate — I'll read it and write up the work order.", ui = UiAction.NAMEPLATE)
            else Reply("That one needs the phone camera. Open BlackLab on your phone and say “nameplate”.")
        Intents.Id.NAV_NEXT -> navigateNext()
        Intents.Id.NAV_NEARBY -> m[1].let { q0 -> val q = if (q0 == "gas" || q0 == "fuel") "gas station" else q0; Reply("Finding the nearest $q.", nav = q) }
        Intents.Id.NAV_TO -> Reply("Heading to ${m[1]}.", nav = m[1])
        Intents.Id.CALL -> Reply("Calling ${m[1].trim()}.", dial = m[1].filter { it.isDigit() || it == '+' })
        Intents.Id.TALK_ABOUT -> startConversation(m.groups.drop(1).filter { it.isNotBlank() }.joinToString(" "), origin)
        Intents.Id.KEEP -> keep(m.firstGroup, origin)
        Intents.Id.NOTE -> note(m[1])
        Intents.Id.TEMPLATE -> template(m[1])
        Intents.Id.JOURNAL_WRITE -> journal(m[1])
        Intents.Id.JOURNAL_OPEN -> Reply("Opening a fresh journal page.", route = "journal/0")
        Intents.Id.SCAN_DOCUMENT -> if (origin == Origin.PHONE) Reply("Opening the document scanner.", ui = UiAction.SCAN_DOCUMENT) else Reply("Scanning needs the phone.")
        Intents.Id.SCAN_CODE -> if (origin == Origin.PHONE) Reply("Opening the code scanner.", ui = UiAction.SCAN_CODE) else Reply("Scanning needs the phone.")
        Intents.Id.PHOTO -> Reply("Camera's up.", route = "camera?mode=photo")
        Intents.Id.VIDEO -> Reply("Video mode.", route = "camera?mode=video")
        Intents.Id.PANORAMA -> Reply("Panorama mode — sweep slow and steady.", route = "camera?mode=pano")
        Intents.Id.FOCUS -> {
            StudyTimer.get(app.context).start(profile.name)
            Reply(done("${s.pomodoroWork}-minute focus timer started. " + casual("Phone down, brain on.", "Lock in.", "")), route = "education?tab=Study")
        }
        Intents.Id.LOG_CEU -> logCeu(m[1].toDouble(), m[2], m[3])
        Intents.Id.GPA -> gpa()
        Intents.Id.SWITCH_PROFILE -> {
            val p = if (m[1].lowercase() == "personal") Profile.PERSONAL else Profile.PROFESSIONAL
            if (p == Profile.PROFESSIONAL && s.lockProfessional && origin == Origin.PHONE) Reply("Switch accounts from the drawer — that one's locked.")
            else { app.settings.setProfile(p); Reply(done("you're in ${app.settings.profileName(p)}.")) }
        }
        Intents.Id.THEME -> { app.settings.update { putString(Keys.THEME_MODE, m[1].uppercase()) }; Reply(done("${m[1]} mode on.")) }
        Intents.Id.SET_SETTING -> setting(m[1], m[2], origin)
        Intents.Id.TOGGLE_SETTING -> setting(m[2], if (m[1].lowercase().let { it.endsWith("on") || it == "enable" }) "on" else "off", origin)
        Intents.Id.GMAIL_UNREAD -> gmailSearch("is:unread in:inbox", "unread")
        Intents.Id.GMAIL_SEARCH -> m.firstGroup.let { q -> gmailSearch(if (l.contains(" from ")) "from:($q)" else q, q) }
        Intents.Id.EMAIL -> email(m[1].lowercase(), m[2].trimEnd(',', '.'), m[3], m[4])
        Intents.Id.DRIVE_SEARCH -> driveSearch(m.firstGroup)
        Intents.Id.DRIVE_UPLOAD -> driveUpload(l)
        Intents.Id.DEV_DOCS -> devDocs(m.firstGroup)
        Intents.Id.NAVIGATE -> navigate(m[1].lowercase())
        Intents.Id.ASK_FILES -> askFiles(m[1])
        Intents.Id.ASK_FILES_QUESTION -> askFiles(t)
    }

    // ------------------------------------------------------------------ work-order intake

    private suspend fun workOrder(text: String): Reply {
        val ctx = app.context
        val d = com.blacklab.app.facility.Intake.parse(text, com.blacklab.app.facility.Facility.load(ctx))
        val id = com.blacklab.app.facility.FacilityReminders.addIntake(ctx, d, s.userName)
        val where = listOfNotNull(d.location.ifBlank { null }, d.asset?.name).joinToString(" · ")
        return Reply(done("work order filed — ${d.priority.name}, ${d.priority.label.substringBefore(" ·").lowercase()}: ${d.title}" +
            (if (where.isNotBlank()) " ($where)" else "") + ". First: ${d.checklist.first()}"),
            listOf(Link("Open work order", "task/$id"), Link("Facility", "facility")), source = "Facility", speak = true)
    }

    private suspend fun library(q: String): Reply {
        if (!DesktopAi.usable) return Reply("The offline library lives on your computer, and I can't reach it right now.")
        val r = com.blacklab.app.link.VaultFeed.library(q)
        val hits = r.json?.optJSONArray("results")
        if (!r.ok || hits == null) return Reply(r.message.ifBlank { "The library didn't answer." })
        if (hits.length() == 0) return Reply("Nothing in the offline library for “$q”.")
        hits.optJSONObject(0)?.optString("error")?.takeIf { it.isNotBlank() }?.let { return Reply(it) }
        val lines = (0 until minOf(4, hits.length())).map { hits.getJSONObject(it) }.joinToString("\n") { h -> "• ${h.optString("title")} (${h.optString("book")}) — ${h.optString("snippet").take(160)}" }
        return Reply("From your offline library:\n$lines", source = "Library")
    }

    // ------------------------------------------------------------------ Obsidian vault (on the computer)

    /** Ask my notes, shift prep, rounds, panels, SDS and credentials — all answered by the computer from the vault. */
    private suspend fun facilityOps(m: Intents.Match, t: String): Reply {
        val V = com.blacklab.app.link.VaultFeed
        if (!s.vaultEnabled) return Reply("The Obsidian vault is off. Turn it on in Settings › Obsidian vault.", route = "settings")
        if (!DesktopAi.usable) return Reply("I can't reach your computer right now, and your vault lives there. Plug in the phone or check the desktop link.")
        val q = m.firstGroup.trim()
        if (Intents.CLINICAL.containsMatchIn(t))
            return Reply("That sounds like someone's care information — it stays on the phone. Use the Behavioral Health workspace instead.")
        val r = when (m.id) {
            Intents.Id.VAULT_ASK -> V.ask(q).let { st ->
                val src = st.json?.optJSONArray("sources")
                if (!st.ok || src == null || src.length() == 0) st
                else com.blacklab.app.link.VaultFeed.Status(true, st.message + "\n\nFrom: " + (0 until minOf(3, src.length())).joinToString(", ") { src.getString(it).substringAfterLast('/').removeSuffix(".md") }, st.json)
            }
            Intents.Id.SHIFT_PREP -> V.facility("shift_prep", org.json.JSONObject())
            Intents.Id.ROUNDS_START -> V.facility("round", org.json.JSONObject().put("say", t))
            Intents.Id.PANEL_LOOKUP -> V.facility("panel", org.json.JSONObject().put("query", q))
            Intents.Id.SDS_LOOKUP -> V.facility("sds", org.json.JSONObject().put("query", q))
            Intents.Id.SKILLS -> V.facility("skills", org.json.JSONObject().put("query", q))
            else -> V.facility("credentials", org.json.JSONObject().put("query", q))
        }
        return Reply(r.message.ifBlank { "Your computer didn't have an answer for that." }, listOf(Link("Facility", "facility")), source = "Vault", speak = true)
    }

    /** "save to my private drive: …" — the computer files it into Google Drive › BlackLab › Paul's Private. */
    private suspend fun privateDrive(said: String): Reply {
        if (!DesktopAi.usable) return Reply("I can't reach your computer right now — it's the one that saves to your private drive. Plug in the phone or check the desktop link.")
        if (Intents.CLINICAL.containsMatchIn(said))
            return Reply("That sounds like someone's care information — it stays in the Behavioral Health workspace on the phone, not in Drive.")
        val r = com.blacklab.app.link.VaultFeed.privateDrive(said)
        return Reply(r.message, source = "Private drive")
    }

    private suspend fun vault(m: Intents.Match): Reply {
        val V = com.blacklab.app.link.VaultFeed
        if (!s.vaultEnabled) return Reply("The Obsidian vault is off. Turn it on in Settings › Obsidian vault.", route = "settings")
        if (!DesktopAi.usable) return Reply("I can't reach your computer right now, and the vault lives there. Plug in the phone or check the desktop link.")
        val text = m.groups.drop(1).lastOrNull { it.isNotBlank() }.orEmpty()
        if (m.id in setOf(Intents.Id.VAULT_SAVE, Intents.Id.VAULT_NODE_ADD, Intents.Id.VAULT_LOG) && Intents.CLINICAL.containsMatchIn(text))
            return Reply("That sounds like someone's care information — it stays on the phone and never goes to the vault. Use the Behavioral Health workspace instead.")
        val r = when (m.id) {
            Intents.Id.VAULT_SAVE -> V.capture(text)
            Intents.Id.VAULT_NODE_ADD -> V.addToNode(m[1], m[2])
            Intents.Id.VAULT_LOG -> V.log(text)
            Intents.Id.VAULT_SHOW -> V.showInGraph(m.firstGroup)
            Intents.Id.VAULT_OPEN -> V.openOnComputer()
            Intents.Id.VAULT_SYNC -> V.sync(app, force = true)
            Intents.Id.VAULT_SEARCH -> V.search(text).let { st ->
                val hits = st.json?.optJSONArray("results")
                if (!st.ok || hits == null) st else com.blacklab.app.link.VaultFeed.Status(true, if (hits.length() == 0) "Nothing in the vault matches “$text”." else
                    "From your vault:\n" + (0 until minOf(5, hits.length())).joinToString("\n") { i -> hits.getJSONObject(i).let { h -> "• ${h.optString("title")} — ${h.optString("snippet").take(140)}" } })
            }
            else -> V.status()?.let { j ->
                val who = j.optJSONObject("last_visitor")?.let { " Last AI visitor: ${it.optString("agent")} (${it.optString("when")})." } ?: ""
                com.blacklab.app.link.VaultFeed.Status(true, "Your vault has ${j.optInt("notes")} notes, ${j.optInt("commits")} saved versions and ${j.optInt("today")} things logged today.$who")
            } ?: com.blacklab.app.link.VaultFeed.Status(false, "Couldn't reach the vault.")
        }
        return Reply(done(r.message), speak = true)
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
            // So the model never guesses about the computer link (it used to say "offline" when it was up)
            val desk = DesktopAi.status.value
            append("Desktop AI (the user's computer): " + if (DesktopAi.usable) "connected, model ${desk.model.ifBlank { "local model" }} — you are running on it right now.\n" else desk.state.label.lowercase() + ".\n")
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
android docs <question> | switch to personal/professional | open <screen> | log <n> CEUs for <certification> |
summarize <today/tomorrow/this week/my recent notes/my open tasks/my journal> as <kind of summary> | let's talk about <topic> |
log fuel <gallons> gallons <dollars> dollars at <miles> miles | log <service> at <miles> miles | what's due on the car |
navigate to my next meeting | find a gas station | navigate to <place> |
desktop <request for the user's computer: open a folder or app, find files on the computer, system status, disk space, save a desktop note>.
reverse engineer <file> [and <question>] — analyze a program, APK, library or firmware on the computer (REA + Ghidra).

If the user's message is a request one of these commands can do, reply with exactly one line: COMMAND: <the command>
Otherwise answer the user directly in 1-4 sentences, starting with: ANSWER:

$ctx
User: $text"""
        val out = Nano.generate(prompt, s.asstNanoTemp / 100f, s.asstNanoTokens) ?: return null
        val cmd = Regex("COMMAND:\\s*(.+)", RegexOption.IGNORE_CASE).find(out)?.groupValues?.get(1)?.lineSequence()?.first()?.trim()
        if (!cmd.isNullOrBlank()) {
            val r = route(cmd, origin, allowNano = false)
            return r.copy(source = "${Nano.engineShort} → ${r.source}")
        }
        val answer = out.substringAfter("ANSWER:", out).trim().removePrefix("ANSWER").trim()
        return if (answer.isBlank()) null else Reply(answer, source = Nano.engine)
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
• Summaries — “summarize this week as action items”, “recap my recent notes for my boss”, or pick items in Summarize
• Knowledge — “what is retrieval-augmented generation?”, “explain GitHub Actions caching”, “let's talk about system design”
• Weather — “what's the weather”, “will it freeze tonight?”, “weather alerts” (facility prep checklists)
• Desktop — “on my computer open my downloads”, “ask my desktop how much disk space is left”, “desktop AI status”
• Reverse engineering (on the computer) — “reverse engineer ~/Downloads/tool.bin and find the license check”, “decompile 0x401000 in tool.bin”, “how do I inspect an APK with REA?”, or any file › ⋮ › Reverse-engineer on computer
• Behavioral Health — “start rounds”, “when are rounds due?”, “start my shift”, “new peer note”, “SBAR handoff”, “crisis lines”, “grounding”
• Facility — “what inspections are due?”, “any PMs overdue?”
• Garage — “log fuel 13.2 gallons $48 at 31,250 miles”, “I got an oil change at 31,000”, “what's due on the car?”
• Driving — “navigate to my next meeting”, “find a gas station”, “call 555-0100”
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
            source = if (Nano.ready && s.asstUseNano) Nano.engine else "On-device extractor")
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
            l.contains("backup") || l.contains("back up") -> app.backups.backupNow() to "application/octet-stream"
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
            "documents", "docs" -> "documents"; "journal" -> "journal"; "notes", "keep" -> "notes"; "brainstorm" -> "brainstorm"; "recorder" -> "recorder"; "dictation" -> "dictation"
            "settings" -> "settings"; "camera" -> "camera?mode=photo"; else -> "assistant"
        }
        return Reply("Opening ${target.replaceFirstChar { it.uppercase() }}.", route = route, speak = false)
    }

    // ------------------------------------------------------------------ summaries

    private suspend fun summarize(rest: String, origin: Origin): Reply? {
        // Pasted text (from the desktop's Summarize page): "summarize this as action items: <text>"
        Regex("^this(?:\\s+as\\s+([^:]{2,60}))?\\s*:\\s*(.{40,})$", RegexOption.IGNORE_CASE).find(rest.trim())?.let { p ->
            val how = p.groupValues[1].trim()
            val text = p.groupValues[2].replace(" ¶ ", "\n").take(30_000)
            val style = Summarizer.styleFrom(how) ?: if (how.isBlank()) Summarizer.Style.KEY_POINTS else Summarizer.Style.CUSTOM
            val r = Summarizer.run(Summarizer.Request(listOf(Summarizer.Source("Pasted text", "Pasted text", text, Time.now())), style,
                Summarizer.lengthFrom(how) ?: Summarizer.Length.MEDIUM, if (style == Summarizer.Style.CUSTOM) how else ""), s.asstUseNano)
            return Reply("${r.style.label}:\n\n${r.text}", listOf(Link("Summarize more", "summarize")), source = "Summaries · ${r.engine}")
        }
        val l = rest.lowercase(Locale.US)
        val target = l.substringBefore(" as ").substringBefore(" for my ").substringBefore(" in ")
        val preset = when {
            Regex("\\b(today|my day|the day)\\b").containsMatchIn(target) -> com.blacklab.app.summarize.SummarySources.Preset.TODAY
            Regex("\\btomorrow\\b").containsMatchIn(target) -> com.blacklab.app.summarize.SummarySources.Preset.TOMORROW
            Regex("\\bweek\\b").containsMatchIn(target) && !target.contains("journal") -> com.blacklab.app.summarize.SummarySources.Preset.WEEK
            Regex("journal").containsMatchIn(target) -> com.blacklab.app.summarize.SummarySources.Preset.JOURNAL_WEEK
            Regex("(meeting|recording|dictation|call|transcript)").containsMatchIn(target) -> {
                // Plain "summarize my last meeting" keeps the full Meeting → Actions flow (minutes + tasks)
                if (Summarizer.styleFrom(rest) == null) return null
                com.blacklab.app.summarize.SummarySources.Preset.LAST_MEETING
            }
            Regex("(notes?|pages?|documents?|docs|wrote|writing)").containsMatchIn(target) -> com.blacklab.app.summarize.SummarySources.Preset.RECENT_NOTES
            Regex("(tasks?|to-?dos?|list)").containsMatchIn(target) -> com.blacklab.app.summarize.SummarySources.Preset.OPEN_TASKS
            else -> null
        }
        if (preset == null) {
            if (Regex("(e-?mails?|inbox)").containsMatchIn(l)) return null
            return if (origin == Origin.PHONE) Reply("Pick what to summarize — files, documents, notes, events, tasks, anything — then choose the kind of summary.", route = "summarize", speak = false)
            else Reply("Tell me what to summarize: today, tomorrow, this week, my recent notes, my open tasks, my journal, or my latest meeting.")
        }
        val sources = com.blacklab.app.summarize.SummarySources.preset(app, preset)
        if (sources.isEmpty()) return Reply("Nothing to summarize for ${preset.label.lowercase()}. " + casual("Quiet one.", "Clean slate.", ""), listOf(Link("Summarize", "summarize")))
        val style = Summarizer.styleFrom(rest) ?: if (origin == Origin.CAR) Summarizer.Style.SPOKEN else Summarizer.Style.KEY_POINTS
        val length = Summarizer.lengthFrom(rest) ?: Summarizer.Length.MEDIUM
        val custom = if (Summarizer.styleFrom(rest) == null && rest.contains(" as ")) rest.substringAfter(" as ") else ""
        val r = Summarizer.run(Summarizer.Request(sources, if (custom.isNotBlank()) Summarizer.Style.CUSTOM else style, length, custom), s.asstUseNano)
        return Reply("${preset.label} — ${r.style.label.lowercase()} (${r.sourceCount} item${if (r.sourceCount == 1) "" else "s"}):\n\n${r.text}",
            listOf(Link("Summarize more", "summarize")), source = "Summaries · ${r.engine}")
    }

    // ------------------------------------------------------------------ knowledge

    private suspend fun knowledgeAnswer(question: String): Reply? {
        val l = question.lowercase(Locale.US)
        val asking = Regex("^(what|what's|whats|how|why|when should|should i|explain|define|describe|tell me about|teach me|give me|compare|difference|best practices?|is it|are there|which|can you explain|walk me through)\\b").containsMatchIn(l) || l.endsWith("?")
        if (!asking) return null
        val hits = com.blacklab.app.knowledge.Knowledge.search(app.context, question, 3)
        val top = hits.firstOrNull() ?: return null
        val q = LocalIndex.tokens(question).toSet()
        val titleOverlap = (LocalIndex.tokens(top.entry.title) + LocalIndex.tokens(top.entry.tags.joinToString(" "))).count { it in q }
        if (top.score < 7.0 || titleOverlap == 0) return null
        val base = com.blacklab.app.knowledge.Knowledge.base(app.context, top.entry.kbId)
        val links = hits.take(3).map { Link(it.entry.title.take(28), "kbentry?id=" + android.net.Uri.encode(it.entry.id)) }
        if (s.asstUseNano && Nano.ready) {
            val notes = hits.mapIndexed { i, h -> "[${i + 1}] ${h.entry.title}: ${com.blacklab.app.knowledge.Text.bestSentences(h.entry.body, question, 6).joinToString(" ").take(800)}" }.joinToString("\n")
            val out = Nano.generate("Answer the question in 2-5 plain sentences using these expert notes (cite [n] when used). If they don't cover it, say what you know and that it's general knowledge.\n\n$notes\n\nQuestion: $question", 0.25f, s.asstNanoTokens)
            if (!out.isNullOrBlank()) return Reply(out, links, source = "Knowledge · ${base?.title ?: ""} · ${Nano.engineShort}")
        }
        val text = com.blacklab.app.knowledge.Text.bestSentences(top.entry.body, question, 4).joinToString(" ")
        return Reply(text + "\n\n— ${base?.title ?: "Knowledge"} › ${top.entry.title}", links, source = "Knowledge · offline")
    }

    private suspend fun startConversation(topic: String, origin: Origin): Reply {
        val ctx = app.context
        val convs = com.blacklab.app.knowledge.Knowledge.conversations(ctx)
        if (convs.isEmpty()) return Reply("The conversation library isn't available.")
        val t = topic.trim().removePrefix("about ").trim()
        // Name match first ("start the health conversation"), then whichever conversation draws on the best-matching notes
        val byName = convs.firstOrNull { c -> t.isNotBlank() && LocalIndex.tokens(c.title).any { it in LocalIndex.tokens(t).toSet() } }
        val chosen = byName ?: run {
            val hit = com.blacklab.app.knowledge.Knowledge.search(ctx, t, 1).firstOrNull()?.entry?.kbId
            convs.firstOrNull { it.id == hit } ?: convs.firstOrNull { hit != null && hit in it.draws } ?: convs.first()
        }
        val q = if (byName != null) "" else t
        val route = "talk/${chosen.id}" + if (q.isNotBlank()) "?q=" + android.net.Uri.encode(q) else ""
        if (origin == Origin.DESKTOP || origin == Origin.CLOUD) {
            if (q.isBlank()) return Reply(chosen.opener)
            val a = app.conversations.ask(chosen.id, q)
            return Reply(a.text, source = a.engine)
        }
        return Reply(if (q.isBlank()) chosen.opener else "Let's get into it — ${chosen.title}.", route = route, source = chosen.title, speak = origin == Origin.CAR || q.isBlank())
    }

    // ------------------------------------------------------------------ weather

    private suspend fun weather(l: String): Reply {
        if (!app.secrets.has(Secrets.OPENWEATHER)) return Reply("Add a free OpenWeatherMap key in Assistant settings and I'll give you the forecast and facility weather alerts.", listOf(Link("Assistant settings", "assistant/settings")))
        val r = Weather.refresh() ?: Weather.report.value ?: return Reply(Weather.error.value ?: "Couldn't get the weather right now.")
        val alerts = Weather.alerts(r)
        val focus = when {
            Regex("freeze|frost|cold").containsMatchIn(l) -> alerts.filter { it.kind.contains("FREEZE") }
            Regex("storm|thunder|rain|wind").containsMatchIn(l) -> alerts.filter { it.kind in setOf("STORM", "RAIN", "WIND") }
            Regex("snow").containsMatchIn(l) -> alerts.filter { it.kind == "SNOW" }
            Regex("hot|heat").containsMatchIn(l) -> alerts.filter { it.kind == "HEAT" }
            else -> alerts
        }
        val text = Weather.summary(r) + if (focus.isEmpty()) (if (Regex("freeze|storm|snow|heat|alert|rain|wind").containsMatchIn(l)) " No ${if (Regex("freeze").containsMatchIn(l)) "freeze" else "weather"} risk in the next two days." else "")
            else "\n\n" + focus.joinToString("\n\n") { a -> "⚠ ${a.title}. ${a.detail}\n" + a.checklist.joinToString("\n") { "• $it" } }
        return Reply(text, listOf(Link("To-Do", "tasks")), source = "OpenWeatherMap")
    }

    // ------------------------------------------------------------------ desktop AI

    private suspend fun desktop(t: String, l: String): Reply? {
        if (DesktopAi.isStatusQuestion(l)) {
            val st = DesktopAi.probe()
            return Reply(when (st.state) {
                DesktopAi.State.ONLINE -> "Your computer's AI is connected — ${st.model.ifBlank { "local model" }}, ${st.latencyMs} ms away. I'll use it for anything that needs real thinking."
                DesktopAi.State.NOT_PAIRED -> "Not paired yet. On the computer, run ./daddyshome with the phone plugged in, then open BlackLab once."
                DesktopAi.State.OFF -> "Desktop AI is turned off in Assistant settings."
                else -> "I can't reach the computer right now. " + st.note.ifBlank { "Plug in the phone (or turn on Wireless debugging) and run ./daddyshome." }
            }, listOf(Link("Assistant settings", "assistant/settings")), source = "Desktop AI")
        }
        jobs(t, l)?.let { return it }
        val m = Regex("^(?:on (?:my |the )?(?:desktop|computer|pc|laptop)[,:]?\\s+|(?:desktop|computer)[:,]\\s*|desktop\\s+|(?:ask|tell|have) (?:my |the )?(?:desktop|computer|pc)(?: to)?\\s+)(.+)$", RegexOption.IGNORE_CASE).find(t) ?: return null
        val req = m.groupValues[1].trim()
        val r = DesktopAi.ask(req) ?: return Reply("I can't reach the computer right now. Plug in the phone (or use Wireless debugging) and run ./daddyshome on the computer.", source = "Desktop AI")
        return Reply(r.text.ifBlank { "Done." }, source = r.source.ifBlank { "Desktop" })
    }

    /**
     * Reverse engineering runs on the computer (see [ReverseEngineering]). A file named here that's on the phone (in
     * BlackLab's folders) is uploaded with the job; anything else is a path on the computer.
     */
    private suspend fun reverse(t: String): Reply? {
        val route = ReverseEngineering.route(t) ?: return null
        val off = "Reverse engineering runs on your computer, and I can't reach it right now (or hand-off is off in Assistant settings). Plug in the phone or use Wireless debugging, then run ./daddyshome."
        if (!DesktopAi.usable && DesktopAi.probe().state != DesktopAi.State.ONLINE) return Reply(off, source = "Reverse engineering")
        return when (route) {
            is ReverseEngineering.Route.Quick -> {
                val r = DesktopAi.ask(t) ?: return Reply(off, source = "Reverse engineering")
                Reply(r.text.ifBlank { "No answer." }, source = r.source.ifBlank { "Desktop · REA" })
            }
            is ReverseEngineering.Route.Background -> Reply(
                if (DesktopJobs.start(app, "ask", t.take(80), org.json.JSONObject().put("text", t))) "Working on it on your computer — Ghidra takes a minute or two. I'll notify you with the answer." else off,
                source = "Reverse engineering")
            is ReverseEngineering.Route.Report -> {
                val name = route.target.substringAfterLast('/')
                val onPhone = if (route.target.startsWith("/") || route.target.startsWith("~")) null else withContext(Dispatchers.IO) {
                    app.storage.root.walkTopDown().maxDepth(6).firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
                }
                val params = org.json.JSONObject().put("goal", route.goal)
                if (onPhone != null) {
                    DesktopJobs.enqueue(app, "reverse", onPhone, "Reverse-engineer $name", params)
                    Reply("Sending $name to your computer for a reverse-engineering report. It shows up in Documents › Reverse Engineering when it's done.", source = "Reverse engineering")
                } else Reply(
                    if (DesktopJobs.start(app, "reverse", "Reverse-engineer $name", params.put("path", route.target)))
                        "Your computer is reverse-engineering $name" + (if (route.goal.isNotBlank()) ", aimed at: ${route.goal}" else "") + ". I'll notify you when the report is ready."
                    else off, source = "Reverse engineering")
            }
        }
    }

    /** Hand-off jobs and the equipment registry, both on the computer. */
    private suspend fun jobs(t: String, l: String): Reply? {
        val off = "Your computer isn't reachable (or hand-off is off in Assistant settings). Plug in the phone or use Wireless debugging, then run ./daddyshome."
        val later = "On it. I'll let you know when your computer finishes."
        if (Regex("^(?:send |write |make |run |start )?(?:the |my )?(?:weekly (?:ops |operations )?(?:summary|report)|week(?:ly)? in review)\\b").containsMatchIn(l))
            return Reply(if (DesktopJobs.start(app, "weekly", "Weekly summary")) "Your computer is writing the weekly operations summary. I'll notify you when it's ready." else off, source = "Desktop jobs")
        if (Regex("^(?:(?:desktop|computer|overnight|ops|operations) brief(?:ing)?|brief me from (?:my |the )?(?:computer|desktop))\\b").containsMatchIn(l))
            return Reply(if (DesktopJobs.start(app, "brief", "Morning brief")) "Your computer is putting the brief together. It takes about a minute." else off, source = "Desktop jobs")
        if (Regex("^transcribe (?:my |the )?(?:last|latest|newest|most recent) (?:recording|meeting)").containsMatchIn(l)) {
            val rec = app.db.recordings().allFor(app.settings.current.activeProfile.name).firstOrNull() ?: return Reply("You don't have any recordings yet.", listOf(Link("Recorder", "recorder")))
            if (!DesktopAi.usable) return Reply(off, source = "Desktop jobs")
            DesktopJobs.enqueue(app, "transcribe", app.storage.resolve(rec.filePath), rec.title, org.json.JSONObject().put("recording_id", rec.id))
            return Reply("Sending “${rec.title}” to your computer. The meeting notes and tasks will show up here when it's done.", listOf(Link("Recording", "recording/${rec.id}")), source = "Desktop jobs")
        }
        Regex("^(?:in the background|when you can|later|(?:computer|desktop),? work on)[:,]?\\s+(.{6,})$", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            val req = m.groupValues[1].trim()
            return Reply(if (DesktopJobs.start(app, "ask", req.take(80), org.json.JSONObject().put("text", req))) later else off, source = "Desktop jobs")
        }
        if (Regex("^(?:(?:what'?s|show(?: me)?) (?:the )?)?(?:history|nameplate|record) (?:on|for|of) [a-z]{1,6}[- ]?\\d|^which (?:units|equipment)\\b|^(?:show |list )?(?:my |all )?equipment\\b").containsMatchIn(l)) {
            val r = DesktopAi.ask(t) ?: return Reply(off, source = "Desktop AI")
            return Reply(r.text.ifBlank { "Nothing on file." }, source = r.source.ifBlank { "Desktop · equipment" })
        }
        return null
    }

    // ------------------------------------------------------------------ behavioral health

    private fun behavioral(l: String): Reply? {
        val ctx = app.context
        val bh = com.blacklab.app.bh.BhStore
        val link = listOf(Link("Behavioral Health", "bh"))
        if (Regex("\\b(crisis (line|number|text)s?|suicide (hotline|line)|988)\\b").containsMatchIn(l)) {
            val call = Regex("^(call|dial|ring)\\b").containsMatchIn(l)
            return Reply("988 Suicide & Crisis Lifeline: call or text 988, any time. Crisis Text Line: text HOME to 741741. Veterans: dial 988 and press 1." +
                if (call) " Dialing 988." else "", listOf(Link("Resources", "bh/resources")), dial = if (call) "988" else null)
        }
        if (Regex("\\b(rounds?|q ?15s?|observation checks?)\\b").containsMatchIn(l) && !Regex("safety|ligature|eoc|environment|golf|ammo|drinks").containsMatchIn(l)) {
            val st = bh.load(ctx)
            when {
                Regex("^(start|begin|turn on|resume)\\b").containsMatchIn(l) -> {
                    if (st.census.isEmpty()) return Reply("There's no one on the census yet — add people by initials or room in Observation Rounds.", route = "bh/rounds")
                    bh.setRounds(ctx, true); return Reply(done("rounds are on for ${st.census.size} on the census. I'll alert you when checks are due."), link)
                }
                Regex("^(stop|end|pause|turn off)\\b").containsMatchIn(l) -> { bh.setRounds(ctx, false); return Reply(done("rounds alerts are off."), link) }
                Regex("^(log|open|show|do)\\b").containsMatchIn(l) -> return Reply("Opening rounds.", route = "bh/rounds")
                Regex("^(when|what|how|are|is|any|next|status|rounds? status|check)").containsMatchIn(l) && !Regex("^what (is|are) (a |an )?(q ?15|rounds|observation)").containsMatchIn(l) -> {
                    val now = System.currentTimeMillis()
                    if (!st.roundsOn) return Reply("Rounds are off. Say “start rounds” to turn on the timers.", link)
                    val over = com.blacklab.app.bh.Rounds.overdue(st, now).size
                    val next = com.blacklab.app.bh.Rounds.nextDue(st, now)
                    return Reply((if (over > 0) "$over overdue. " else "") + (next?.let { if (it <= now) "Checks are due now. " else "Next check in ${(it - now) / 60_000 + 1} minutes. " } ?: "") +
                        "${st.census.size} on the census.", link)
                }
                else -> Unit
            }
        }
        if (Regex("^(start|begin|clock in to|starting) (my |a |the )?shift").containsMatchIn(l)) {
            val st = bh.load(ctx)
            if (st.activeShift != null) return Reply("You're already on shift since ${com.blacklab.app.bh.Writer.clock(st.activeShift!!.start)}.", route = "bh/shift")
            val type = when (LocalTime.now().hour) { in 5..10 -> "Day"; in 11..17 -> "Evening"; else -> "Night" }
            bh.upsertShift(ctx, com.blacklab.app.bh.Bh.Shift(type = type, role = if (st.prefs.role == "PSS") "Peer Support" else "MHT", unit = st.prefs.unit))
            return Reply(done("$type shift started. Your checklist is ready."), route = "bh/shift")
        }
        if (Regex("^(end|finish|clock out of|ending) (my |the )?shift").containsMatchIn(l)) {
            return Reply("Let's wrap up — the end-of-shift list and handoff are open.", route = "bh/shift")
        }
        // Anchored so "remind me to finish my peer note" still becomes a task and questions still reach the knowledge bases
        val lead = "^(?:(?:new|write|start|open|create|do|begin|draft|make|log|record|add|show|go to)\\s+)?(?:(?:a|an|my|the)\\s+)?"
        fun says(body: String) = Regex(lead + "(?:$body)$").containsMatchIn(l)
        val form = when {
            says("(?:peer|session|peer support|peer session) note") -> com.blacklab.app.bh.Specs.PEER_NOTE
            says("(?:observation|shift observation|behavior) note") -> com.blacklab.app.bh.Specs.BEHAVIOR
            says("sbar(?: handoff| report)?|(?:shift )?handoff|shift report") -> com.blacklab.app.bh.Specs.HANDOFF
            says("(?:behavioral|patient|unit|aggression|restraint|seclusion) incident(?: report| draft)?|incident draft") -> com.blacklab.app.bh.Specs.INCIDENT
            says("(?:post[- ]incident |staff |patient )?debrief") -> com.blacklab.app.bh.Specs.DEBRIEF
            says("group (?:session )?note") -> com.blacklab.app.bh.Specs.GROUP_NOTE
            says("(?:after[- ]shift |wellness )?check[- ]?in") -> com.blacklab.app.bh.Specs.CHECKIN
            says("supervision(?: session| hours| log)?") -> com.blacklab.app.bh.Specs.SUPERVISION
            says("(?:wellness|crisis|wrap) plan") -> com.blacklab.app.bh.Specs.SAFETY_PLAN
            else -> null
        }
        if (form != null) return Reply("Opening a new ${com.blacklab.app.bh.Specs.spec(form)?.title?.lowercase()}.", route = "bh/form/$form")
        val screen = when {
            says("de-?escalation(?: guide| tips)?|crisis guide") -> "bh/crisis"
            says("grounding(?: exercise)?|breathing exercise|box breathing|breathing coach") -> "bh/crisis?tab=ground"
            says("(?:safety|ligature|ligature risk|eoc|environment of care) rounds?") -> "bh/eoc"
            says("group (?:plans?|ideas|topics?|activities|library)|plan a group") -> "bh/groups"
            says("(?:peer |recovery |community )?resources|referrals?") && Regex("peer|referral|community|recovery").containsMatchIn(l) -> "bh/resources"
            says("behavioral health|mht|peer support|census|participants") -> "bh"
            else -> null
        }
        return screen?.let { Reply("Opening it.", route = it) }
    }

    // ------------------------------------------------------------------ facility

    /** Questions only ("what inspections are due?") — never a task like "remind me at 3 pm the facility report is due". */
    private val facilityAsk = Regex("^(what|which|any|are|is|show|list|how many|tell me|do i have)\\b.*\\b(inspections?|pms|preventive maintenance|life[- ]safety|facility)\\b.*\\b(due|overdue|coming up|behind)\\b")

    private fun facility(l: String): Reply? {
        if (!facilityAsk.containsMatchIn(l)) return null
        val f = com.blacklab.app.facility.Facility
        val due = f.due(f.load(app.context))
        if (due.isEmpty()) return Reply("No facility schedules yet. Add your life-safety program in Facility.", listOf(Link("Facility", "facility")), source = "Facility")
        val open = due.filter { it.status != com.blacklab.app.facility.Facility.Status.OK }
        if (open.isEmpty()) return Reply("All ${due.size} facility schedules are current. Next up: ${due.first().pm.title} for ${due.first().asset.name}, ${due.first().describe()}.",
            listOf(Link("Facility", "facility")), source = "Facility")
        val over = open.count { it.status == com.blacklab.app.facility.Facility.Status.OVERDUE }
        val head = if (over > 0) "$over overdue" + (if (open.size > over) " and ${open.size - over} due soon" else "") else "${open.size} due soon"
        return Reply("Facility: $head. " + open.take(4).joinToString("; ") { "${it.pm.title}, ${it.asset.name} — ${it.describe()}" } + if (open.size > 4) "; and ${open.size - 4} more." else ".",
            listOf(Link("Facility", "facility")), source = "Facility")
    }

    // ------------------------------------------------------------------ garage & driving

    private fun garage(t: String, l: String): Reply? {
        val ctx = app.context
        val g = com.blacklab.app.vehicle.Garage
        val st = g.load(ctx)
        if (Regex("^(log|add|record)?\\s*(fuel|gas|fill[- ]?up)|^(i )?(just )?(filled up|got gas|fueled up|put in)").containsMatchIn(l) && Regex("gal").containsMatchIn(l)) {
            val f = g.parseFuel(t) ?: return Reply("How many gallons? Say something like “log fuel 13 gallons for 48 dollars at 31,250 miles”.")
            g.addFuel(ctx, f)
            val stats = g.fuelStats(g.state.value.fuel)
            return Reply(done("logged ${f.gallons} gal" + (f.cost?.let { " for $${"%.2f".format(it)}" } ?: "") + (f.miles?.let { " at ${g.fmtMiles(it)} mi" } ?: "") + ".") +
                (stats.lastMpg?.let { " That tank: ${"%.1f".format(it)} mpg." } ?: ""), listOf(Link("Garage", "garage")), source = "Garage")
        }
        if (Regex("^(log|record|mark)\\b|^(i )?(just )?(got|had|did)\\b|^(the )?(car|santa fe) (got|had)\\b").containsMatchIn(l) &&
            Regex("oil|rotat|filter|brake|wiper|battery|coolant|spark|plugs|transmission|differential|transfer case|belt|service").containsMatchIn(l)) {
            val ids = g.matchItems(t, st.items)
            if (ids.isEmpty()) return null
            val miles = Regex("(\\d{1,3}(?:,\\d{3})+|\\d{3,6})\\s*(?:miles|mi\\b|on the clock)|at (\\d{1,3}(?:,\\d{3})+|\\d{3,6})").find(l)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.replace(",", "")?.toIntOrNull()
            val cost = Regex("\\$\\s*(\\d+(?:\\.\\d{1,2})?)|(\\d+(?:\\.\\d{1,2})?) dollars").find(l)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toDoubleOrNull()
            g.logService(ctx, ids, miles, cost = cost)
            val names = st.items.filter { it.id in ids }.joinToString(", ") { it.name.lowercase() }
            val next = g.due(g.state.value).firstOrNull { it.item.id in ids }
            return Reply(done("logged $names" + (miles?.let { " at ${g.fmtMiles(it)} mi" } ?: "") + ".") + (next?.dueMiles?.let { " Next one around ${g.fmtMiles(it)} mi." } ?: ""),
                listOf(Link("Garage", "garage")), source = "Garage")
        }
        Regex("(?:odometer|mileage|odo)(?: is| reads| at| to| of)? (\\d{1,3}(?:,\\d{3})+|\\d{3,6})").find(l)?.let { r ->
            val miles = r.groupValues[1].replace(",", "").toInt()
            g.setOdometer(ctx, miles)
            return Reply(done("odometer set to ${g.fmtMiles(miles)} mi.") + " " + g.summary(g.state.value).substringAfter(". ", ""), listOf(Link("Garage", "garage")), source = "Garage")
        }
        carData(l)?.let { return it }
        val carTopic = Regex("(oil change|tire rotation|mpg|gas mileage|fuel economy|due on (the|my) (car|santa fe|suv)|car (status|report|health)|how('s| is) (the|my) (car|santa fe))").containsMatchIn(l) ||
            (Regex("(maintenance|service)").containsMatchIn(l) && Regex("\\b(car|santa fe|vehicle|suv)\\b").containsMatchIn(l))
        if (carTopic &&
            Regex("^(when|what|what's|whats|how|is|any|car|santa fe|my car|status|check)").containsMatchIn(l)) {
            val due = g.due(st).filter { it.status == com.blacklab.app.vehicle.Garage.Status.OVERDUE || it.status == com.blacklab.app.vehicle.Garage.Status.DUE_SOON }.take(3)
            val extra = if (due.isEmpty()) "" else "\n" + due.joinToString("\n") { "• ${it.item.name} — ${it.describe()}" }
            val live = com.blacklab.app.vehicle.VehicleLink.summary(ctx)
            return Reply(g.summary(st) + (if (live.isNotBlank()) " $live" else "") + extra, listOf(Link("Garage", "garage")), source = "Garage")
        }
        return null
    }

    /** Questions answered from what the car itself reported (OBD-II, Bluelink, Android Auto) and the trip log. */
    private fun carData(l: String): Reply? {
        val ctx = app.context
        val T = com.blacklab.app.vehicle.Telemetry
        val vl = com.blacklab.app.vehicle.VehicleLink
        val s = vl.load(ctx)
        val links = listOf(Link("Garage", "garage"))
        fun fresh(k: String) = s[k]?.let { "${T.format(it)} (${it.source.label}, ${T.ago(it.time)})" }
        if (Regex("where('?s| is| did i (park|leave))( my)? (car|santa fe|suv|park)|where did i park|find my car").containsMatchIn(l)) {
            val p = s.parked ?: return Reply("I don't have a parking spot yet — it's saved when a drive ends.", links, source = "Garage")
            return Reply("Parked at ${p.place.ifBlank { "%.5f, %.5f".format(p.lat, p.lon) }}, ${T.ago(p.time)}.", links, source = "Garage", nav = "${p.lat},${p.lon}")
        }
        if (Regex("recalls?\\b|safety (notice|campaign)").containsMatchIn(l)) {
            val n = com.blacklab.app.vehicle.Nhtsa.load(ctx)
            val v = com.blacklab.app.vehicle.Garage.load(ctx).vehicle
            val text = when {
                n.checkedAt == 0L -> "I haven't checked NHTSA yet — open the Garage and I'll look up recalls for the ${v.year} ${v.model}."
                n.open.isEmpty() && n.recalls.isEmpty() -> "No recalls are listed for the ${v.year} ${v.make} ${v.model} (checked ${T.ago(n.checkedAt)})."
                n.open.isEmpty() -> "All ${n.recalls.size} listed recalls are marked done or not for your car."
                else -> "${n.open.size} recall${if (n.open.size > 1) "s" else ""} to check: " + n.open.joinToString("; ") { "${it.component.lowercase()} (${it.campaign})" } +
                    ". Recalls are listed by model year, so confirm with the VIN check on nhtsa.gov or your dealer."
            }
            return Reply(text, links, source = "NHTSA")
        }
        if (Regex("when('?s| is) (my |the )?(next )?(oil change|service|maintenance|tire rotation)|next (oil change|service)|service due").containsMatchIn(l)) {
            val d = com.blacklab.app.vehicle.ServiceReminders.due(ctx).filter { it.status != com.blacklab.app.vehicle.Garage.Status.UNKNOWN }
            val pick = d.firstOrNull { l.contains("oil") && it.item.id == "oil" || l.contains("rotation") && it.item.id == "rotate" } ?: d.firstOrNull()
            return Reply(pick?.let { "${it.item.name}: ${it.describe()}." } ?: "Set the odometer in the Garage and I'll work out what's due.", links, source = "Garage")
        }
        if (Regex("check engine|engine light|trouble codes?|dtcs?\\b|diagnostic|error codes?|codes? on (the|my) car").containsMatchIn(l)) {
            val c = s.activeDtcs
            val text = when {
                c.isNotEmpty() -> "${c.size} active code${if (c.size > 1) "s" else ""}: " + c.joinToString("; ") { "${it.code} — ${com.blacklab.app.vehicle.Dtcs.describe(it.code)}" } + "."
                s["mil"]?.bool == true -> "The check-engine light is on, but no stored codes were read."
                s["mil"] != null || s["dtc_count"] != null -> "No trouble codes — the check-engine light is off (as of ${T.ago((s["mil"] ?: s["dtc_count"])!!.time)})."
                else -> "I haven't read the car's codes yet. That needs a Bluetooth OBD-II adapter set up in Garage › Car connection."
            }
            return Reply(text, links, source = "Garage")
        }
        if (Regex("(how much|what'?s (my|the)) (gas|fuel)|fuel level|gas level|miles (of range|to empty|left)|range (left|remaining)|how far can i (go|drive)").containsMatchIn(l)) {
            val parts = listOfNotNull(fresh("fuel_pct")?.let { "Fuel $it" }, fresh("range")?.let { "range $it" })
            return Reply(if (parts.isEmpty()) "The car hasn't reported its fuel level yet — it comes from Android Auto, Bluelink or the OBD-II adapter." else parts.joinToString(", ") + ".", links, source = "Garage")
        }
        if (Regex("(is|are) (my|the) (car|doors?|santa fe)( doors?)? locked|did i lock|tire pressure|tyre pressure|car battery|battery voltage|coolant temp").containsMatchIn(l)) {
            val parts = listOfNotNull(fresh("locked")?.let { "Doors: $it" }, fresh("doors_open")?.takeIf { s["doors_open"]?.text != "None" }?.let { "open: $it" },
                fresh("tire_warning")?.let { "tire pressure: $it" }, fresh("battery_v")?.let { "battery $it" }, fresh("battery_soc")?.let { "12V charge $it" }, fresh("coolant")?.let { "coolant $it" })
            return Reply(if (parts.isEmpty()) "I don't have that from the car yet. Set up Bluelink or an OBD-II adapter in Garage › Car connection." else parts.joinToString("; ").replaceFirstChar { it.uppercase() } + ".", links, source = "Garage")
        }
        Regex("how (many|far)( miles)? (did|have) i (drive|driven|drove)|miles (did i drive|driven|this (week|month|year)|last (week|month))|business miles|mileage (log|this (week|month|year))|trip log").find(l)?.let {
            val now = java.time.LocalDate.now()
            val zone = java.time.ZoneId.systemDefault()
            val (label, from, to) = when {
                l.contains("last week") -> Triple("last week", T.weekStart(now.minusWeeks(1)), T.weekStart(now))
                l.contains("last month") -> Triple("last month", T.monthStart(now.minusMonths(1)), T.monthStart(now))
                l.contains("year") || l.contains("business") -> Triple("this year", T.yearStart(now), Long.MAX_VALUE)
                l.contains("today") -> Triple("today", now.atStartOfDay(zone).toInstant().toEpochMilli(), Long.MAX_VALUE)
                l.contains("week") -> Triple("this week", T.weekStart(now), Long.MAX_VALUE)
                else -> Triple("this month", T.monthStart(now), Long.MAX_VALUE)
            }
            val t = T.totals(s.trips, from, to, s.link.ratePerMile)
            if (t.trips == 0) return Reply("No drives logged $label.", links, source = "Garage")
            val biz = t.byPurpose[com.blacklab.app.vehicle.Telemetry.Purpose.BUSINESS] ?: 0.0
            return Reply("%.1f miles in %d drive%s %s (%s behind the wheel)".format(t.miles, t.trips, if (t.trips == 1) "" else "s", label, T.duration(t.driveSec)) +
                (if (biz > 0) ", %.1f of them business — about $%,.0f at your rate.".format(biz, t.deductible) else "."), links, source = "Garage")
        }
        return null
    }


    private suspend fun navigateNext(): Reply {
        val now = java.time.LocalDateTime.now()
        val next = Briefing.agenda(app, Time.today(), Time.today().plusDays(7)).firstOrNull { it.start > now.minusMinutes(15) && it.where.isNotBlank() }
            ?: return Reply("I don't see an upcoming event with a location this week.", listOf(Link("Schedule", "schedule")))
        return Reply("Heading to “${next.title}” at ${next.where} — ${Time.relativeDay(next.start.toLocalDate()).lowercase()} at ${Time.time(next.start)}.", nav = next.where)
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
            if (!answer.isNullOrBlank()) return Reply(answer, links, source = "Ask my files · ${Nano.engineShort}")
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
