package com.blacklab.app.bh

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Turns saved forms into finished, copy-ready notes. Pure functions (unit tested). */
object Writer {

    private val dayTime = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US)
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val day = DateTimeFormatter.ofPattern("EEE MMM d, yyyy", Locale.US)

    fun when_(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = dayTime.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun clock(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = clock.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun dayLabel(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = day.format(Instant.ofEpochMilli(ms).atZone(zone))

    private fun list(xs: List<String>): String = when (xs.size) {
        0 -> ""
        1 -> xs[0]
        2 -> "${xs[0]} and ${xs[1]}"
        else -> xs.dropLast(1).joinToString(", ") + ", and " + xs.last()
    }

    private fun lower(xs: List<String>) = xs.map { s -> if (s.length > 1 && s[1].isLowerCase()) s.replaceFirstChar { it.lowercase() } else s }

    private fun sentence(s: String): String = s.trim().let { t -> if (t.isEmpty()) "" else (t.replaceFirstChar { it.uppercase() } + if (t.last() in ".!?”\"") "" else ".") }

    private fun para(vararg parts: String?): String = parts.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ") { sentence(it) }

    private fun signature(prefs: Bh.Prefs, at: Long): String = listOf(prefs.signature.ifBlank { null }, when_(at)).filterNotNull().joinToString(" · ")

    // ---------------------------------------------------------------- 4 · peer support note

    fun peerNote(r: Forms.Rec, alias: String?, prefs: Bh.Prefs): String {
        val f = r.v("format").ifBlank { "DAP" }
        val minutes = r.int("minutes")
        val contact = para(
            listOfNotNull(r.v("type").ifBlank { null }, r.v("setting").ifBlank { null }?.let { "in " + lower(listOf(it)).first() }).joinToString(" ").ifBlank { null }?.let { c ->
                "Peer support contact: $c" + (minutes?.let { ", $it minutes" } ?: "") + (r.v("initiated").ifBlank { null }?.let { ", initiated by ${it.lowercase()}" } ?: "")
            },
        )
        val goal = r.v("goal").ifBlank { null }?.let { "Participant identified their goal for the session as “${it.trim().trimEnd('.')}”" }
        val focus = listOfNotNull(
            r.m("topics").takeIf { it.isNotEmpty() }?.let { "Discussed ${list(lower(it))}" },
            r.m("domains").takeIf { it.isNotEmpty() }?.let { "Recovery domains addressed: ${list(it)}" },
        )
        val said = listOfNotNull(
            r.v("mood").ifBlank { null }?.let { "Participant described their mood as “${it.trim().trim('“', '”', '"')}”" },
            r.v("statements").ifBlank { null }?.let { "Participant stated: ${it.trim()}" },
        )
        val presentation = r.m("affect").takeIf { it.isNotEmpty() }?.let { "Presented as ${list(lower(it))}" }
        val engagement = r.v("engagement").ifBlank { null }?.let { "Engagement: ${it.lowercase()}" }
        val interventions = buildList {
            r.m("interventions").takeIf { it.isNotEmpty() }?.let { add("Peer specialist provided ${list(lower(it))}") }
            if (r.on("disclosed")) add("Peer shared lived experience purposefully" + (r.v("disclosurePurpose").ifBlank { null }?.let { " to ${it.lowercase()}" } ?: ""))
            r.m("skills").takeIf { it.isNotEmpty() }?.let { add("Practiced ${list(lower(it))}") }
        }
        val response = listOfNotNull(
            r.v("progress").ifBlank { null }?.let { "Progress toward recovery goals: ${it.lowercase()}" },
            r.v("stage").ifBlank { null }?.takeIf { it != "Not assessed" }?.let { "Peer impression of readiness for change: ${it.lowercase()}" },
            r.m("strengths").takeIf { it.isNotEmpty() }?.let { "Strengths noted: ${list(lower(it))}" },
        )
        val safety = r.v("safety").ifBlank { null }?.let { s ->
            if (s.startsWith("No safety")) "No safety concerns were expressed or observed during the contact"
            else s + (r.v("reportedTo").ifBlank { null }?.let { " ($it" + (r.v("reportedAt").ifBlank { null }?.let { t -> " at $t" } ?: "") + ")" } ?: "")
        }
        val plan = listOfNotNull(
            r.v("plan").ifBlank { null },
            r.v("commitment").ifBlank { null }?.let { "Participant plans to ${it.trim().replaceFirstChar { c -> c.lowercase() }.trimEnd('.')}" },
            r.v("peerTask").ifBlank { null }?.let { "Peer specialist will ${it.trim().replaceFirstChar { c -> c.lowercase() }.trimEnd('.')}" },
            r.v("referrals").ifBlank { null }?.let { "Referrals / linkages: $it" },
            r.v("next").ifBlank { null }?.let { "Next contact: ${it.lowercase()}" },
        )
        val summary = r.v("summary").ifBlank { null }

        val title = "PEER SUPPORT NOTE" + (alias?.let { " — $it" } ?: "") + " · " + (r.v("when").ifBlank { when_(r.created) })
        val body = when (f) {
            "SOAP" -> sections(
                "S — Subjective" to para(goal, *said.toTypedArray(), *focus.toTypedArray()),
                "O — Objective" to para(contact, presentation, engagement, summary, *interventions.toTypedArray()),
                "A — Assessment (peer observations)" to para(*response.toTypedArray(), safety),
                "P — Plan" to para(*plan.toTypedArray()),
            )
            "BIRP" -> sections(
                "B — Behavior" to para(contact, presentation, *said.toTypedArray(), summary),
                "I — Intervention" to para(*focus.toTypedArray(), *interventions.toTypedArray()),
                "R — Response" to para(engagement, *response.toTypedArray(), safety),
                "P — Plan" to para(*plan.toTypedArray()),
            )
            "GIRP" -> sections(
                "G — Goal" to para(goal, r.m("domains").takeIf { it.isNotEmpty() }?.let { "Recovery domains: ${list(it)}" }),
                "I — Intervention" to para(contact, r.m("topics").takeIf { it.isNotEmpty() }?.let { "Discussed ${list(lower(it))}" }, *interventions.toTypedArray()),
                "R — Response" to para(presentation, *said.toTypedArray(), engagement, summary, *response.toTypedArray(), safety),
                "P — Plan" to para(*plan.toTypedArray()),
            )
            "Narrative" -> listOf(
                para(contact, goal, *focus.toTypedArray()),
                para(presentation, *said.toTypedArray(), summary),
                para(*interventions.toTypedArray(), engagement, *response.toTypedArray()),
                para(safety),
                para(*plan.toTypedArray()),
            ).filter { it.isNotBlank() }.joinToString("\n\n")
            else -> sections( // DAP
                "D — Data" to para(contact, goal, *focus.toTypedArray(), *said.toTypedArray(), presentation, summary),
                "A — Assessment (peer observations)" to para(engagement, *interventions.toTypedArray(), *response.toTypedArray(), safety),
                "P — Plan" to para(*plan.toTypedArray()),
            )
        }
        return "$title\n\n$body\n\n— ${signature(prefs, r.updated)}".trim()
    }

    private fun sections(vararg s: Pair<String, String>): String = s.filter { it.second.isNotBlank() }.joinToString("\n\n") { (h, b) -> "$h\n$b" }

    // ---------------------------------------------------------------- 1 · MHT observation note

    fun behaviorNote(r: Forms.Rec, who: String?, prefs: Bh.Prefs): String {
        val lines = mutableListOf<String>()
        fun add(label: String, v: String?) { if (!v.isNullOrBlank()) lines += "$label: $v" }
        add("Stated mood", r.v("mood").ifBlank { null }?.let { "“${it.trim()}”" })
        add("Affect", r.m("affect").joinToString(", ").ifBlank { null })
        add("Behavior", r.m("behavior").joinToString(", ").ifBlank { null })
        add("Speech", r.v("speech")); add("Orientation", r.v("orientation"))
        val g = listOfNotNull(r.int("groupsAttended"), r.int("groupsOffered"))
        if (g.size == 2) add("Groups", "attended ${g[0]} of ${g[1]}") else add("Groups attended", r.v("groupsAttended"))
        add("Group participation", r.v("participation")); add("With peers", r.v("milieu")); add("With staff", r.v("staff"))
        add("Hygiene / ADLs", r.v("hygiene"))
        val meals = listOf("breakfast", "lunch", "dinner").mapNotNull { k -> r.v(k).ifBlank { null }?.let { "${k.replaceFirstChar { c -> c.uppercase() }} $it" } }
        add("Meals", meals.joinToString(" · ").ifBlank { null }); add("Fluids", r.v("fluids"))
        add("Sleep", listOfNotNull(r.v("sleep").ifBlank { null }, r.v("sleepHours").ifBlank { null }?.let { "~$it hrs" }).joinToString(", ").ifBlank { null })
        val vitals = listOfNotNull(r.v("bp").ifBlank { null }?.let { "BP $it" }, r.v("pulse").ifBlank { null }?.let { "P $it" }, r.v("temp").ifBlank { null }?.let { "T $it" },
            r.v("resp").ifBlank { null }?.let { "R $it" }, r.v("spo2").ifBlank { null }?.let { "SpO₂ $it%" }, r.v("weight").ifBlank { null }?.let { "Wt $it" },
            r.v("glucose").ifBlank { null }?.let { "BG $it" })
        add("Vitals", vitals.joinToString(", ").ifBlank { null }?.let { it + if (r.on("vitalsReported")) " (abnormal values reported to RN)" else "" })
        add("Precautions maintained", r.m("precautions").joinToString(", ").ifBlank { null })
        add("Incidents", r.v("incident"))
        if (r.on("concernReported")) add("Concerns reported to nurse", r.v("concernWho").ifBlank { "yes" })
        val narrative = r.v("narrative").trim()
        val title = "SHIFT OBSERVATION NOTE" + (who?.let { " — $it" } ?: "") + " · " + (r.v("when").ifBlank { when_(r.created) })
        return buildString {
            appendLine(title); appendLine()
            lines.forEach { appendLine(it) }
            if (narrative.isNotEmpty()) { appendLine(); appendLine("Narrative:"); appendLine(narrative) }
            appendLine(); append("— ${signature(prefs, r.updated)}")
        }.trim()
    }

    // ---------------------------------------------------------------- 1 · SBAR handoff

    fun handoff(r: Forms.Rec, s: Bh.State, now: Long = System.currentTimeMillis()): String = buildString {
        appendLine("SHIFT REPORT (SBAR) · ${r.v("shift").ifBlank { "Shift" }} · ${r.v("unit").ifBlank { s.prefs.unit }.ifBlank { "Unit" }} · ${when_(r.created)}")
        val header = listOfNotNull(
            (r.int("census") ?: s.census.size.takeIf { it > 0 })?.let { "Census $it" }, r.v("acuity").ifBlank { null }?.let { "Acuity: $it" },
            r.v("staffing").ifBlank { null }?.let { "Staffing: $it" })
        if (header.isNotEmpty()) appendLine(header.joinToString(" · "))
        appendLine()
        fun block(h: String, v: String) { if (v.isNotBlank()) { appendLine(h); appendLine(v.trim()); appendLine() } }
        block("S — SITUATION", r.v("situation"))
        val bg = listOfNotNull(
            r.m("events").takeIf { it.isNotEmpty() }?.let { "Events: ${it.joinToString(", ")}" },
            listOfNotNull(r.int("admits")?.let { "$it admission${if (it == 1) "" else "s"}" }, r.int("discharges")?.let { "$it discharge${if (it == 1) "" else "s"}" })
                .joinToString(", ").ifBlank { null },
            r.v("background").ifBlank { null },
        ).joinToString("\n")
        block("B — BACKGROUND", bg)
        val census = s.census.sortedBy { it.room.padStart(6, '0') }.map { p ->
            val last = s.checks.filter { it.patientId == p.id }.maxByOrNull { it.time }
            "• ${p.display} — ${p.obs}" + (if (p.precautions.isNotEmpty()) " · ${p.precautions.joinToString(", ")}" else "") +
                (last?.let { " · last seen ${clock(it.time)} ${it.location.lowercase()}, ${it.behavior.lowercase()}" } ?: "") +
                (p.notes.ifBlank { null }?.let { " · $it" } ?: "")
        }
        block("A — ASSESSMENT", listOfNotNull(r.v("assessment").ifBlank { null }, r.v("obsChanges").ifBlank { null }?.let { "Observation changes: $it" },
            census.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "Census:\n")).joinToString("\n"))
        block("R — RECOMMENDATION", listOfNotNull(r.v("recommendation").ifBlank { null }, r.m("pending").takeIf { it.isNotEmpty() }?.let { "Pending: ${it.joinToString(", ")}" }).joinToString("\n"))
        val checks = listOfNotNull(r.v("sharps").ifBlank { null }?.let { "Sharps count: $it" }, if (r.on("keys")) "Keys / radios / duress devices accounted for" else null,
            r.v("sweep").ifBlank { null }?.let { "Environment sweep: $it" }, r.v("fireDoors").ifBlank { null }?.let { "Fire doors / exits: $it" })
        block("SAFETY CHECKS", checks.joinToString("\n"))
        append("— ${signature(s.prefs, now)}")
    }.trim()

    // ---------------------------------------------------------------- 3 · incident

    fun incident(r: Forms.Rec, prefs: Bh.Prefs): String {
        val body = Forms.render(Specs.incident, r, "INCIDENT REPORT DRAFT · ${r.v("type").ifBlank { "Event" }} · ${r.v("when").ifBlank { when_(r.created) }}")
        return "$body\n\nDraft for the facility incident system — not the official report.\n— ${signature(prefs, r.updated)}"
    }

    // ---------------------------------------------------------------- 2 · round sheet

    fun roundSheet(s: Bh.State, now: Long = System.currentTimeMillis()): String = buildString {
        val (ok, late) = Rounds.compliance(s)
        appendLine("OBSERVATION ROUND SHEET · ${s.prefs.unit.ifBlank { "Unit" }} · ${dayLabel(now)}")
        appendLine("${s.census.size} on census · ${s.checks.size} checks logged" + (if (ok + late > 0) " · ${ok * 100 / (ok + late)}% on time" else ""))
        s.census.sortedBy { it.room.padStart(6, '0') }.forEach { p ->
            appendLine()
            appendLine("${p.display} — ${p.obs}" + (if (p.precautions.isNotEmpty()) " (${p.precautions.joinToString(", ")})" else ""))
            val checks = s.checks.filter { it.patientId == p.id }.sortedBy { it.time }
            if (checks.isEmpty()) appendLine("  No checks logged")
            checks.forEach { c -> appendLine("  ${clock(c.time)}  ${c.location} · ${c.behavior}" + (c.note.ifBlank { null }?.let { " — $it" } ?: "") + (c.by.ifBlank { null }?.let { " [$it]" } ?: "")) }
        }
        appendLine(); append("— ${signature(s.prefs, now)}")
    }.trim()

    // ---------------------------------------------------------------- 7 · environment of care

    fun eocReport(e: Bh.EocRound): String = buildString {
        val issues = e.areas.sumOf { it.issueCount }
        appendLine("ENVIRONMENT OF CARE ROUND · ${e.kind}")
        appendLine(listOfNotNull(e.unit.ifBlank { null }, when_(e.time), e.inspector.ifBlank { null }?.let { "Inspector: $it" }).joinToString(" · "))
        appendLine("${e.areas.size} area${if (e.areas.size == 1) "" else "s"} inspected · $issues issue${if (issues == 1) "" else "s"} found")
        e.areas.forEach { a ->
            val items = Library.EOC_ITEMS[a.area].orEmpty()
            appendLine()
            appendLine("${a.area}${a.label.ifBlank { null }?.let { " — $it" } ?: ""}: " +
                "${a.results.count { it.value == "OK" }} OK, ${a.issueCount} issue${if (a.issueCount == 1) "" else "s"}, ${a.results.count { it.value == "NA" }} N/A, ${items.size - a.results.size} not checked")
            items.filter { a.results[it.id] == "ISSUE" }.forEach { i -> appendLine("  ✗ ${i.text}" + (a.issues[i.id]?.ifBlank { null }?.let { " — $it" } ?: "")) }
            if (a.risk.isNotBlank()) appendLine("  Risk: ${a.risk}")
            if (a.mitigation.isNotBlank()) appendLine("  Mitigation: ${a.mitigation}")
        }
        if (e.notes.isNotBlank()) { appendLine(); appendLine("Notes: ${e.notes}") }
    }.trim()

    // ---------------------------------------------------------------- generic

    fun render(r: Forms.Rec, s: Bh.State): String {
        val alias = s.participants.firstOrNull { it.id == r.ref }?.alias ?: s.patients.firstOrNull { it.id == r.ref }?.display
        return when (r.kind) {
            Specs.PEER_NOTE -> peerNote(r, alias, s.prefs)
            Specs.BEHAVIOR -> behaviorNote(r, alias, s.prefs)
            Specs.HANDOFF -> handoff(r, s)
            Specs.INCIDENT -> incident(r, s.prefs)
            else -> Specs.spec(r.kind)?.let { sp -> Forms.render(sp, r, sp.title.uppercase() + (alias?.let { " — $it" } ?: "") + " · " + r.v("when").ifBlank { when_(r.created) }) + "\n\n— ${signature(s.prefs, r.updated)}" }.orEmpty()
        }
    }
}
