@file:Suppress("DEPRECATION") // setTitle/setHeaderAction work on every Android Auto host; Header needs Car API 7

package com.blacklab.app.car

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.blacklab.app.AppContainer
import com.blacklab.app.R
import com.blacklab.app.app
import com.blacklab.app.assistant.Briefing
import com.blacklab.app.assistant.Origin
import com.blacklab.app.brainstorm.NoteOps
import com.blacklab.app.core.Time
import com.blacklab.app.data.NoteEntity
import com.blacklab.app.data.TaskOps
import com.blacklab.app.knowledge.Knowledge
import com.blacklab.app.summarize.Summarizer
import com.blacklab.app.summarize.SummarySources
import com.blacklab.app.vehicle.Garage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

// ------------------------------------------------------------------ helpers

internal val CarContext.app: AppContainer get() = applicationContext.app

internal fun CarContext.icon(res: Int, tint: CarColor? = null): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(this, res)).apply { if (tint != null) setTint(tint) }.build()

/** How many rows/tiles this car allows right now (fewer while driving on some head units). */
internal fun CarContext.limit(type: Int, fallback: Int = 6): Int =
    if (apiLevel >= 2) runCatching { getCarService(ConstraintManager::class.java).getContentLimit(type) }.getOrDefault(fallback) else fallback

/** The host's Car App API level, or 1 before it's known. */
internal val CarContext.apiLevel: Int get() = runCatching { carAppApiLevel }.getOrDefault(1)

/** Hands a destination to the car's navigation app (Google Maps / Waze). */
internal fun CarContext.navigateTo(query: String) {
    runCatching { startCarApp(Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:0,0?q=" + Uri.encode(query)))) }
        .onFailure { CarToast.makeText(this, "Couldn't start navigation", CarToast.LENGTH_LONG).show() }
}

internal fun CarContext.dial(number: String) {
    runCatching { startCarApp(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
        .onFailure { CarToast.makeText(this, "Couldn't start the call", CarToast.LENGTH_LONG).show() }
}

internal fun CarContext.toast(text: String) = CarToast.makeText(this, text, CarToast.LENGTH_LONG).show()

private fun action(title: String, icon: CarIcon? = null, onClick: () -> Unit): Action =
    Action.Builder().setTitle(title).apply { if (icon != null) setIcon(icon) }.setOnClickListener(onClick).build()

/** Action strips allow only one titled action; the rest must be icon-only. */
private fun iconAction(icon: CarIcon, onClick: () -> Unit): Action = Action.Builder().setIcon(icon).setOnClickListener(onClick).build()

private fun row(title: String, vararg text: String): Row =
    Row.Builder().setTitle(title.ifBlank { " " }).apply { text.filter { it.isNotBlank() }.take(2).forEach { addText(it) } }.build()

/** Phone numbers in free text ("call in: 555-201-3344", "+1 (555) 201 3344"). */
internal fun findPhone(text: String): String? =
    Regex("(\\+?\\d[\\d\\s().-]{8,}\\d)").find(text)?.value?.filter { it.isDigit() || it == '+' }?.takeIf { it.count(Char::isDigit) in 10..15 }

// ------------------------------------------------------------------ home

class CarHomeScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val c = carContext
        val s = c.app.settings.current
        data class Tile(val title: String, val icon: Int, val go: () -> Screen)
        val tiles = listOf(
            Tile("Ask ${s.asstName}", R.drawable.ic_car_mic) { CarAskScreen(c) },
            Tile("Brief me", R.drawable.ic_car_brief) { CarSummariesScreen(c) },
            Tile("Agenda", R.drawable.ic_car_event) { CarAgendaScreen(c) },
            Tile("Tasks", R.drawable.ic_car_task) { CarTasksScreen(c) },
            Tile("Talk", R.drawable.ic_car_chat) { CarTalkListScreen(c) },
            Tile("Voice note", R.drawable.ic_car_note) { CarCaptureScreen(c, CarCaptureScreen.Mode.NOTE) },
            Tile(Garage.load(c).vehicle.model.ifBlank { "Garage" }, R.drawable.ic_car_car) { CarGarageScreen(c) },
        )
        val max = c.limit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID, 6)
        // "Ask" is always in the header too, so it's the first tile to give up if the car allows fewer
        val shown = if (tiles.size > max) tiles.drop(1).take(max) else tiles
        val items = ItemList.Builder().apply {
            shown.forEach { t ->
                addItem(GridItem.Builder().setTitle(t.title).setImage(c.icon(t.icon), GridItem.IMAGE_TYPE_ICON)
                    .setOnClickListener { screenManager.push(t.go()) }.build())
            }
        }.build()
        return GridTemplate.Builder()
            .setTitle("BlackLab")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items)
            .setActionStrip(ActionStrip.Builder().addAction(action("Ask", c.icon(R.drawable.ic_car_mic, CarColor.PRIMARY)) { screenManager.push(CarAskScreen(c)) }).build())
            .build()
    }
}

// ------------------------------------------------------------------ voice turn (ask / talk)

/**
 * A hands-free, two-row voice screen: what you said, and the reply (also spoken). The template
 * keeps the same title, row titles and actions on every update so Android Auto treats updates
 * as refreshes rather than new steps (hosts cap how many new screens an app may show in a row).
 */
abstract class VoiceTurnScreen(carContext: CarContext, private val title: String, private val replyTitle: String, private val autoStart: Boolean) : Screen(carContext) {
    protected val app = carContext.app
    private var heard = "Tap Talk, or just start speaking."
    private var reply = ""
    private var listening = false
    private var thinking = false
    private var started = false
    private val speakerDone: () -> Unit = { carContext.mainExecutor.execute { onSpoken() } }

    private val voice = CarVoice(carContext, continuous = false,
        onPartial = { heard = it; invalidate() },
        onFinal = { text -> listening = false; heard = text; respondTo(text) },
        onError = { msg -> listening = false; heard = msg; invalidate() },
        onStopped = { listening = false })

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                app.speaker.doneListeners += speakerDone
                if (!started) { started = true; onOpened() }
            }
            override fun onStop(owner: LifecycleOwner) {
                app.speaker.doneListeners -= speakerDone
                voice.stop()
            }
            override fun onDestroy(owner: LifecycleOwner) { app.speaker.stop() }
        })
    }

    /** First reply text to show and speak when the screen opens (blank = nothing). */
    protected open fun intro(): String = ""
    protected abstract suspend fun respond(text: String): String
    /** Whether to listen again automatically once the reply has been spoken. */
    protected open fun keepListening(): Boolean = false

    private fun onOpened() {
        val i = intro()
        if (i.isNotBlank()) { reply = i; say(i) }
        else if (autoStart) listen()
        initial()?.let { respondTo(it) }
    }

    /** A question to answer right away (e.g. "let's talk about RAG" from the Ask screen). */
    protected open fun initial(): String? = null

    private fun onSpoken() { if (keepListening() && !listening && !thinking && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) listen() }

    protected fun say(text: String) {
        if (app.settings.current.carSpeak) app.speaker.speak(text) else carContext.mainExecutor.execute { onSpoken() }
    }

    protected fun showReply(text: String) { reply = text; invalidate() }

    fun listen() {
        app.speaker.stop()
        if (!voice.hasMicPermission()) {
            carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ -> if (Manifest.permission.RECORD_AUDIO in granted) listen() }
            heard = "Allow the microphone on your phone screen (once)."
            invalidate(); return
        }
        listening = true
        heard = "Listening…"
        invalidate()
        voice.start()
    }

    private fun respondTo(text: String) {
        thinking = true
        reply = "…"
        invalidate()
        lifecycleScope.launch {
            val out = runCatching { respond(text) }.getOrElse { "Something went wrong: ${it.message ?: it.javaClass.simpleName}" }
            thinking = false
            reply = out
            invalidate()
            if (out.isNotBlank()) say(out)
        }
    }

    override fun onGetTemplate(): Template {
        val c = carContext
        val status = when { listening -> "Listening…"; thinking -> "Thinking…"; else -> "" }
        val pane = Pane.Builder()
            .addRow(row("You", heard))
            .addRow(row(replyTitle, if (thinking) "Thinking…" else reply.ifBlank { status.ifBlank { " " } }))
            .addAction(action(if (listening) "Listening" else "Talk", c.icon(R.drawable.ic_car_mic, CarColor.PRIMARY)) { if (!listening) listen() })
            .addAction(action("Stop", c.icon(R.drawable.ic_car_stop)) { voice.stop(); app.speaker.stop(); listening = false; invalidate() })
            .build()
        return PaneTemplate.Builder(pane).setTitle(title).setHeaderAction(Action.BACK).build()
    }
}

/** "Ask BlackLab": the full assistant by voice — tasks, calendar, notes, Gmail, summaries, Garage, knowledge, navigation. */
class CarAskScreen(carContext: CarContext) : VoiceTurnScreen(carContext, "Ask ${carContext.app.settings.current.asstName}", carContext.app.settings.current.asstName, autoStart = true) {
    override fun keepListening() = app.assistant.pending.value != null // waiting for a yes/no
    override suspend fun respond(text: String): String {
        val r = app.assistant.handle(text, Origin.CAR)
        withContext(Dispatchers.Main) {
            r.nav?.let { carContext.navigateTo(it) }
            r.dial?.let { carContext.dial(it) }
            r.route?.let { route ->
                when {
                    route.startsWith("talk/") -> {
                        val id = route.removePrefix("talk/").substringBefore('?')
                        val q = route.substringAfter("?q=", "").let { Uri.decode(it) }
                        screenManager.push(CarTalkScreen(carContext, id, q.ifBlank { null }))
                    }
                    route == "garage" -> screenManager.push(CarGarageScreen(carContext))
                    route == "tasks" || route.startsWith("task/") -> {}
                }
            }
        }
        return r.text.replace(Regex("\\n+• "), "\n").take(900)
    }
}

/** A knowledge conversation by voice. Hands-free: it listens again after each answer (setting). */
class CarTalkScreen(carContext: CarContext, private val id: String, private val first: String? = null) :
    VoiceTurnScreen(carContext, Knowledge.base(carContext, id)?.title ?: "Talk", "BlackLab", autoStart = false) {
    override fun intro(): String {
        if (first != null) return ""
        val h = app.conversations.open(id)
        return if (h.size <= 1) h.lastOrNull()?.text.orEmpty() else "Back to ${Knowledge.base(carContext, id)?.title ?: "it"}. What's next?"
    }
    override fun initial(): String? = first
    override fun keepListening() = app.settings.current.carAutoListen
    override suspend fun respond(text: String): String {
        if (Regex("^(that's all|that is all|we're done|i'm done|stop|goodbye|bye|end conversation|exit)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())) {
            withContext(Dispatchers.Main) { screenManager.pop() }
            return "Good talk. Catch you later."
        }
        return app.conversations.ask(id, text).text
    }
}

class CarTalkListScreen(carContext: CarContext, private val group: String? = null) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val c = carContext
        val convs = Knowledge.conversations(c)
        val tech = convs.filter { it.draws.any { d -> d.startsWith("software") || d.startsWith("ai-") || d == "github" } }
        val life = convs - tech.toSet()
        fun rowFor(b: Knowledge.Base) = Row.Builder().setTitle(b.title).addText(b.summary.ifBlank { " " }).setImage(c.icon(R.drawable.ic_car_chat))
            .setOnClickListener { screenManager.push(CarTalkScreen(c, b.id)) }.setBrowsable(true).build()
        val limit = c.limit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST, 6)
        val t = ListTemplate.Builder().setTitle(group ?: "Talk").setHeaderAction(Action.BACK)
        when {
            group == "Tech talk" -> t.setSingleList(ItemList.Builder().apply { tech.take(limit).forEach { addItem(rowFor(it)) } }.build())
            group == "Everyday" -> t.setSingleList(ItemList.Builder().apply { life.take(limit).forEach { addItem(rowFor(it)) } }.build())
            convs.size <= limit -> {
                t.addSectionedList(SectionedItemList.create(ItemList.Builder().apply { tech.forEach { addItem(rowFor(it)) } }.build(), "Tech talk"))
                t.addSectionedList(SectionedItemList.create(ItemList.Builder().apply { life.forEach { addItem(rowFor(it)) } }.build(), "Everyday"))
            }
            else -> t.setSingleList(ItemList.Builder()
                .addItem(Row.Builder().setTitle("Tech talk").addText("Software, AI and GitHub — ${tech.size} conversations").setBrowsable(true).setOnClickListener { screenManager.push(CarTalkListScreen(c, "Tech talk")) }.build())
                .addItem(Row.Builder().setTitle("Everyday").addText("Health, money & home, downtime — ${life.size} conversations").setBrowsable(true).setOnClickListener { screenManager.push(CarTalkListScreen(c, "Everyday")) }.build())
                .build())
        }
        return t.build()
    }
}

// ------------------------------------------------------------------ agenda

class CarAgendaScreen(carContext: CarContext) : Screen(carContext) {
    private var items: List<Briefing.AgendaItem>? = null

    init {
        lifecycleScope.launch {
            val app = carContext.app
            items = withContext(Dispatchers.IO) {
                val now = LocalDateTime.now()
                Briefing.agenda(app, Time.today(), Time.today().plusDays(1)).filter { it.end >= now || it.kind == "Due" }
            }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val c = carContext
        val list = items ?: return ListTemplate.Builder().setTitle("Agenda").setHeaderAction(Action.BACK).setLoading(true).build()
        val limit = c.limit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST, 6)
        val il = ItemList.Builder().setNoItemsMessage("Nothing on the calendar today or tomorrow.")
        list.take(limit).forEach { i ->
            val whenText = Time.relativeDay(i.start.toLocalDate()) + " · " + (if (i.kind == "Due") "due " else "") + Time.time(i.start)
            il.addItem(Row.Builder().setTitle(i.title).addText(listOf(whenText, i.where).filter { it.isNotBlank() }.joinToString(" · "))
                .setImage(c.icon(R.drawable.ic_car_event)).setBrowsable(true).setOnClickListener { screenManager.push(CarEventScreen(c, i)) }.build())
        }
        val read = action("Read", c.icon(R.drawable.ic_car_speak)) {
            c.app.speaker.speak(if (list.isEmpty()) "Nothing on the calendar today or tomorrow." else
                list.take(6).joinToString(". ") { "${Time.relativeDay(it.start.toLocalDate())} at ${Time.time(it.start)}, ${it.title}" + (if (it.where.isNotBlank()) " at ${it.where}" else "") } + ".")
        }
        return ListTemplate.Builder().setTitle("Agenda").setHeaderAction(Action.BACK).setSingleList(il.build())
            .setActionStrip(ActionStrip.Builder().addAction(read).build()).build()
    }
}

class CarEventScreen(carContext: CarContext, private val item: Briefing.AgendaItem) : Screen(carContext) {
    private var notes = ""
    private var phone: String? = null

    init {
        lifecycleScope.launch {
            val id = item.route.substringAfter("event/", "").toLongOrNull()
            val e = if (id != null) withContext(Dispatchers.IO) { carContext.app.db.events().all().firstOrNull { it.id == id } } else null
            notes = listOfNotNull(e?.notes?.takeIf { it.isNotBlank() }, e?.attendees?.takeIf { it.isNotBlank() }?.let { "With: $it" }).joinToString(" · ")
            phone = findPhone(listOfNotNull(e?.notes, e?.location, e?.url).joinToString(" "))
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val c = carContext
        val whenText = Time.relativeDay(item.start.toLocalDate()) + ", " + Time.time(item.start) + if (item.kind == "Event" && item.end > item.start) " – " + Time.time(item.end) else ""
        val pane = Pane.Builder().addRow(row("When", whenText))
        if (item.where.isNotBlank()) pane.addRow(row("Where", item.where))
        if (notes.isNotBlank()) pane.addRow(row("Notes", notes.take(200)))
        if (item.where.isNotBlank()) pane.addAction(action("Navigate", c.icon(R.drawable.ic_car_nav, CarColor.PRIMARY)) { c.navigateTo(item.where) })
        phone?.let { p -> pane.addAction(action("Call", c.icon(R.drawable.ic_car_phone)) { c.dial(p) }) }
        val read = action("Read", c.icon(R.drawable.ic_car_speak)) {
            c.app.speaker.speak("${item.title}. $whenText." + (if (item.where.isNotBlank()) " At ${item.where}." else "") + (if (notes.isNotBlank()) " $notes" else ""))
        }
        return PaneTemplate.Builder(pane.build()).setTitle(item.title).setHeaderAction(Action.BACK)
            .setActionStrip(ActionStrip.Builder().addAction(read).build()).build()
    }
}

// ------------------------------------------------------------------ tasks

class CarTasksScreen(carContext: CarContext) : Screen(carContext) {
    private var tasks: List<com.blacklab.app.data.TaskEntity>? = null
    private val done = HashSet<Long>()

    init { reload() }

    private fun reload() {
        lifecycleScope.launch {
            val today = Time.today().toEpochDay()
            tasks = withContext(Dispatchers.IO) {
                Briefing.openTasks(carContext.app)
                    .filter { (it.dueDay != null && it.dueDay <= today + 1) || it.myDayDay == today || it.important }
                    .sortedWith(compareBy<com.blacklab.app.data.TaskEntity> { it.dueDay ?: Long.MAX_VALUE }.thenByDescending { it.priority })
            }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val c = carContext
        val list = tasks ?: return ListTemplate.Builder().setTitle("Tasks").setHeaderAction(Action.BACK).setLoading(true).build()
        val limit = c.limit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST, 6)
        val today = Time.today().toEpochDay()
        val il = ItemList.Builder().setNoItemsMessage("Nothing due. Nice.")
        list.take(limit).forEach { t ->
            val due = t.dueDay?.let { d -> if (d < today) "Overdue" else Time.relativeDay(LocalDate.ofEpochDay(d)) + (t.dueMinute?.let { " · ${Time.time(it)}" } ?: "") } ?: if (t.important) "Important" else "My Day"
            il.addItem(Row.Builder().setTitle(t.title).addText(due)
                .setToggle(Toggle.Builder { checked ->
                    if (checked) done += t.id else done -= t.id
                    c.app.io { TaskOps.setCompleted(c.app, t, checked) }
                    if (checked) c.toast("Done: ${t.title}")
                }.setChecked(t.id in done).build()).build())
        }
        val add = action("Add", c.icon(R.drawable.ic_car_add, CarColor.PRIMARY)) { screenManager.pushForResult(CarCaptureScreen(c, CarCaptureScreen.Mode.TASK)) { reload() } }
        val read = iconAction(c.icon(R.drawable.ic_car_speak)) {
            val open = list.filter { it.id !in done }
            c.app.speaker.speak(if (open.isEmpty()) "Nothing due." else "${open.size} to do: " + open.take(6).joinToString("; ") { it.title } + ".")
        }
        return ListTemplate.Builder().setTitle("Tasks").setHeaderAction(Action.BACK).setSingleList(il.build())
            .setActionStrip(ActionStrip.Builder().addAction(add).addAction(read).build()).build()
    }
}

// ------------------------------------------------------------------ capture: voice note / task / fuel / service

class CarCaptureScreen(carContext: CarContext, private val mode: Mode) : Screen(carContext) {
    enum class Mode(val title: String, val prompt: String) {
        NOTE("Voice note", "Speak your note. Tap Save when you're done."),
        TASK("New task", "Say the task — e.g. “call the roofer tomorrow at 9”."),
        FUEL("Log fuel", "Say gallons, price and miles — “13.2 gallons, 48 dollars, 31,250 miles”."),
        SERVICE("Log service", "Say what was done — “oil change and tire rotation at 31,000 miles”."),
    }

    private val app = carContext.app
    private val parts = ArrayList<String>()
    private var partial = ""
    private var status = mode.prompt
    private var listening = false
    private var saved = false

    private val voice = CarVoice(carContext, continuous = mode == Mode.NOTE,
        onPartial = { partial = it; invalidate() },
        onFinal = { text ->
            partial = ""
            if (mode == Mode.NOTE) parts += text else { parts.clear(); parts += text; listening = false }
            status = if (mode == Mode.NOTE) "Keep talking, or tap Save." else "Tap Save, or Retry."
            invalidate()
        },
        onError = { msg -> listening = false; status = msg; invalidate() },
        onStopped = { listening = false })

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { if (!listening && parts.isEmpty() && !saved) start() }
            override fun onStop(owner: LifecycleOwner) { voice.stop() }
        })
    }

    private fun start() {
        if (!voice.hasMicPermission()) {
            carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ -> if (Manifest.permission.RECORD_AUDIO in granted) start() }
            status = "Allow the microphone on your phone screen (once)."; invalidate(); return
        }
        app.speaker.stop()
        listening = true
        status = "Listening…"
        invalidate()
        voice.start()
    }

    private val text: String get() = (parts + partial).filter { it.isNotBlank() }.joinToString(" ").trim()

    private fun save() {
        val t = text
        voice.stop()
        if (t.isBlank()) { status = "I didn't get anything yet."; invalidate(); return }
        saved = true
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) { runCatching { commit(t) }.getOrElse { "Couldn't save: ${it.message}" } }
            carContext.toast(msg)
            if (app.settings.current.carSpeak) app.speaker.speak(msg)
            setResult(true)
            screenManager.pop()
        }
    }

    private suspend fun commit(t: String): String { return when (mode) {
        Mode.NOTE -> {
            val p = app.settings.current.activeProfile
            NoteOps.save(app, NoteEntity(profile = p.name, title = "Car note · " + Time.dateTime(Time.now()), body = t.replaceFirstChar { it.uppercase() }, labels = ",car,"))
            "Saved your voice note."
        }
        Mode.TASK -> app.assistant.handle("add task $t", Origin.CAR).text
        Mode.FUEL -> {
            val f = Garage.parseFuel(t) ?: return "I need the gallons — try again with “13 gallons”."
            Garage.addFuel(carContext, f)
            val mpg = Garage.fuelStats(Garage.state.value.fuel).lastMpg
            "Logged ${f.gallons} gallons" + (f.cost?.let { " for $${"%.2f".format(it)}" } ?: "") + "." + (mpg?.let { " That tank was ${"%.1f".format(it)} mpg." } ?: "")
        }
        Mode.SERVICE -> {
            val st = Garage.load(carContext)
            val ids = Garage.matchItems(t, st.items)
            if (ids.isEmpty()) return "I didn't recognize the service. Try “oil change at 31,000 miles”."
            val miles = Regex("(\\d{1,3}(?:,\\d{3})+|\\d{3,6})").findAll(t).map { it.value.replace(",", "").toInt() }.filter { it >= 100 }.maxOrNull()
            Garage.logService(carContext, ids, miles)
            "Logged " + st.items.filter { it.id in ids }.joinToString(", ") { it.name.lowercase() } + (miles?.let { " at ${Garage.fmtMiles(it)} miles" } ?: "") + "."
        }
    } }

    override fun onGetTemplate(): Template {
        val c = carContext
        val pane = Pane.Builder()
            .addRow(row("Heard", text.ifBlank { "—" }.takeLast(240)))
            .addRow(row("Status", status))
            .addAction(action("Save", c.icon(R.drawable.ic_car_check, CarColor.PRIMARY)) { save() })
            .addAction(action(if (listening) "Listening" else "Retry", c.icon(R.drawable.ic_car_mic)) {
                if (!listening) { if (mode != Mode.NOTE) parts.clear(); start() }
            })
            .build()
        return PaneTemplate.Builder(pane).setTitle(mode.title).setHeaderAction(Action.BACK).build()
    }
}

// ------------------------------------------------------------------ briefing & summaries

class CarSummariesScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val c = carContext
        val limit = c.limit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST, 6)
        val il = ItemList.Builder()
        il.addItem(Row.Builder().setTitle("Daily briefing").addText("Today's schedule, tasks, deadlines and renewals").setImage(c.icon(R.drawable.ic_car_brief))
            .setBrowsable(true).setOnClickListener { screenManager.push(CarSummaryScreen(c, null)) }.build())
        SummarySources.Preset.entries.filter { it != SummarySources.Preset.TODAY }.take(limit - 1).forEach { p ->
            il.addItem(Row.Builder().setTitle(p.label).addText(p.hint).setImage(c.icon(R.drawable.ic_car_summary))
                .setBrowsable(true).setOnClickListener { screenManager.push(CarSummaryScreen(c, p)) }.build())
        }
        return ListTemplate.Builder().setTitle("Brief me").setHeaderAction(Action.BACK).setSingleList(il.build()).build()
    }
}

/** Builds a spoken summary (or the daily briefing when [preset] is null) and reads it aloud. */
class CarSummaryScreen(carContext: CarContext, private val preset: SummarySources.Preset?) : Screen(carContext) {
    private val app = carContext.app
    private var text: String? = null
    private var meta = ""

    init {
        lifecycleScope.launch {
            val s = app.settings.current
            val out = withContext(Dispatchers.IO) {
                if (preset == null) Briefing.build(app, s.asstPersona) to "On-device briefing"
                else {
                    val sources = SummarySources.preset(app, preset)
                    if (sources.isEmpty()) "Nothing to summarize for ${preset.label.lowercase()}." to ""
                    else Summarizer.run(Summarizer.Request(sources, Summarizer.Style.SPOKEN, Summarizer.Length.MEDIUM), s.asstUseNano).let { it.text to "${it.sourceCount} items · ${it.engine}" }
                }
            }
            text = out.first; meta = out.second
            invalidate()
            if (s.carSpeak) app.speaker.speak(out.first)
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver { override fun onDestroy(owner: LifecycleOwner) { app.speaker.stop() } })
    }

    override fun onGetTemplate(): Template {
        val c = carContext
        val title = preset?.label ?: "Daily briefing"
        val t = text ?: return PaneTemplate.Builder(Pane.Builder().setLoading(true).build()).setTitle(title).setHeaderAction(Action.BACK).build()
        val pane = Pane.Builder()
            .addRow(row("Summary", t.replace('\n', ' ').take(400)))
            .addRow(row("Source", meta.ifBlank { " " }))
            .addAction(action("Read again", c.icon(R.drawable.ic_car_speak, CarColor.PRIMARY)) { app.speaker.speak(t) })
            .addAction(action("Stop", c.icon(R.drawable.ic_car_stop)) { app.speaker.stop() })
            .build()
        return PaneTemplate.Builder(pane).setTitle(title).setHeaderAction(Action.BACK).build()
    }
}

// ------------------------------------------------------------------ garage (Santa Fe)

class CarGarageScreen(carContext: CarContext) : Screen(carContext) {
    private val car = CarData(carContext) { invalidate() }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { car.start(); invalidate() }
            override fun onStop(owner: LifecycleOwner) { car.stop() }
        })
    }

    override fun onGetTemplate(): Template {
        val c = carContext
        val st = Garage.load(c)
        val v = st.vehicle
        val due = Garage.due(st)
        val urgent = due.filter { it.status == Garage.Status.OVERDUE || it.status == Garage.Status.DUE_SOON }
        val next = urgent.firstOrNull() ?: due.firstOrNull { it.status == Garage.Status.OK }
        val stats = Garage.fuelStats(st.fuel)
        val pane = Pane.Builder()
        val odo = if (v.odometer > 0) "${Garage.fmtMiles(v.odometer)} mi" else "Odometer not set"
        pane.addRow(row(car.detected ?: v.name, listOfNotNull(odo, car.fuelLine).joinToString(" · ")))
        pane.addRow(row(if (urgent.isNotEmpty()) "Service due" else "Next service",
            next?.let { "${it.item.name} — ${it.describe()}" } ?: "Log your last service to start tracking",
            if (urgent.size > 1) "+${urgent.size - 1} more due" else ""))
        pane.addRow(row("Fuel", listOfNotNull(stats.avgMpg?.let { "Avg ${"%.1f".format(it)} mpg" }, stats.lastMpg?.let { "last ${"%.1f".format(it)}" },
            stats.monthCost.takeIf { it > 0 }?.let { "$${"%.0f".format(it)} this month" }).joinToString(" · ").ifBlank { "Log a fill-up to see MPG" }))
        pane.addAction(action("Log fuel", c.icon(R.drawable.ic_car_fuel, CarColor.PRIMARY)) { screenManager.pushForResult(CarCaptureScreen(c, CarCaptureScreen.Mode.FUEL)) { invalidate() } })
        pane.addAction(action("Log service", c.icon(R.drawable.ic_car_wrench)) { screenManager.pushForResult(CarCaptureScreen(c, CarCaptureScreen.Mode.SERVICE)) { invalidate() } })
        val strip = ActionStrip.Builder()
            .addAction(action("Schedule", c.icon(R.drawable.ic_car_wrench)) { screenManager.push(CarScheduleScreen(c)) })
            .addAction(iconAction(c.icon(R.drawable.ic_car_fuel)) { c.navigateTo("gas station") })
            .build()
        return PaneTemplate.Builder(pane.build()).setTitle(v.nickname.ifBlank { "${v.model} · Garage" }).setHeaderAction(Action.BACK).setActionStrip(strip).build()
    }
}

class CarScheduleScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val c = carContext
        val st = Garage.load(c)
        val limit = c.limit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST, 6)
        val il = ItemList.Builder()
        Garage.due(st).take(limit).forEach { d ->
            val tag = when (d.status) { Garage.Status.OVERDUE -> "Overdue"; Garage.Status.DUE_SOON -> "Due soon"; Garage.Status.OK -> "OK"; Garage.Status.UNKNOWN -> "Not logged" }
            il.addItem(Row.Builder().setTitle(d.item.name).addText("$tag · ${d.describe()}").setImage(c.icon(R.drawable.ic_car_wrench,
                if (d.status == Garage.Status.OVERDUE) CarColor.RED else if (d.status == Garage.Status.DUE_SOON) CarColor.YELLOW else null)).build())
        }
        val dealer = st.vehicle.dealer.ifBlank { "Hyundai service center" }
        return ListTemplate.Builder().setTitle("Maintenance").setHeaderAction(Action.BACK).setSingleList(il.build())
            .setActionStrip(ActionStrip.Builder().addAction(action("Dealer", c.icon(R.drawable.ic_car_nav)) { c.navigateTo(dealer) }).build()).build()
    }
}

/** A friendly message screen used when something needs the phone (permissions, setup). */
class CarMessageScreen(carContext: CarContext, private val title: String, private val message: String) : Screen(carContext) {
    override fun onGetTemplate(): Template = MessageTemplate.Builder(message).setTitle(title).setHeaderAction(Action.BACK).build()
}
