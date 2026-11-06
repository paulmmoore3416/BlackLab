package com.blacklab.app.bh

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BhTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L

    private fun state(vararg patients: Bh.Patient, checks: List<Bh.Check> = emptyList(), on: Boolean = true) =
        Bh.State(patients = patients.toList(), checks = checks, roundsOn = on, roundsSince = t0)

    // ---------------------------------------------------------------- rounds

    @Test fun q15IsDueFifteenMinutesAfterRoundsStartAndAfterEachCheck() {
        val p = Bh.Patient(id = "a", label = "JD", room = "12", obs = "Q15", added = t0 - 60 * min)
        val s = state(p)
        assertEquals(t0 + 15 * min, Rounds.nextDue(s, t0))
        assertTrue(Rounds.overdue(s, t0 + 16 * min).isEmpty()) // inside the 2-minute grace
        assertEquals(1, Rounds.overdue(s, t0 + 18 * min).size)

        val checked = s.copy(checks = listOf(Bh.Check(patientId = "a", time = t0 + 14 * min, location = "In room", behavior = "Calm")))
        assertEquals(t0 + 29 * min, Rounds.nextDue(checked, t0 + 15 * min))
        assertTrue(Rounds.overdue(checked, t0 + 18 * min).isEmpty())
    }

    @Test fun lateAdmissionStartsItsOwnClockAndContinuousHasNoTimer() {
        val late = Bh.Patient(id = "b", label = "RT", obs = "Q30", added = t0 + 10 * min)
        val oneToOne = Bh.Patient(id = "c", label = "MK", obs = "1:1 arm's length", added = t0)
        val s = state(late, oneToOne)
        val due = Rounds.due(s, t0 + 11 * min)
        assertEquals(t0 + 40 * min, due.first { it.patient.id == "b" }.dueAt)
        assertNull(due.first { it.patient.id == "c" }.dueAt)
        assertTrue(Rounds.continuous("1:1 arm's length"))
        assertEquals(t0 + 40 * min, Rounds.nextDue(s, t0 + 11 * min))
        assertNull(Rounds.nextDue(s.copy(roundsOn = false), t0))
    }

    @Test fun complianceCountsLateGaps() {
        val p = Bh.Patient(id = "a", label = "JD", obs = "Q15", added = t0)
        val checks = listOf(0L, 15L, 31L, 55L).map { Bh.Check(patientId = "a", time = t0 + it * min, location = "In room", behavior = "Calm") }
        val (ok, late) = Rounds.compliance(state(p, checks = checks))
        assertEquals(2, ok)   // 15 and 16 minutes (within grace)
        assertEquals(1, late) // 24 minutes
    }

    // ---------------------------------------------------------------- retention

    @Test fun purgeErasesOldRoundsAndDraftsAboutOthersButKeepsMyCheckIns() {
        val now = t0 + 30 * 3_600_000L
        val p = Bh.Patient(id = "a", label = "JD", added = t0)
        val gone = Bh.Patient(id = "b", label = "RT", added = t0, active = false)
        val s = Bh.State(
            prefs = Bh.Prefs(retentionHours = 24, noteDays = 14),
            patients = listOf(p, gone),
            checks = listOf(Bh.Check(patientId = "a", time = t0, location = "x", behavior = "y"), Bh.Check(patientId = "a", time = now - min, location = "x", behavior = "y")),
            records = listOf(
                Forms.Rec(kind = Specs.PEER_NOTE, created = now - 20 * 86_400_000L, updated = now - 20 * 86_400_000L),
                Forms.Rec(kind = Specs.PEER_NOTE, created = now - 86_400_000L, updated = now - 86_400_000L),
                Forms.Rec(kind = Specs.CHECKIN, created = now - 200 * 86_400_000L, updated = now - 200 * 86_400_000L),
            ),
        )
        val out = BhStore.purge(s, now)
        assertEquals(listOf("a"), out.patients.map { it.id })
        assertEquals(1, out.checks.size)
        assertEquals(1, out.records.count { it.kind == Specs.PEER_NOTE })
        assertEquals(1, out.records.count { it.kind == Specs.CHECKIN })
        assertEquals(3, BhStore.purge(s.copy(prefs = s.prefs.copy(noteDays = 0)), now).records.size)
    }

    // ---------------------------------------------------------------- JSON

    @Test fun stateRoundTripsThroughJson() {
        val s = Bh.State(
            prefs = Bh.Prefs(facility = "Test", unit = "Adult acute", signature = "P. M., CPSS", noteDays = 7, hideOnHome = true),
            patients = listOf(Bh.Patient(id = "a", label = "JD", room = "12", obs = "Q30", precautions = listOf("Fall precautions"))),
            checks = listOf(Bh.Check(id = "c1", patientId = "a", time = t0, location = "Day room", behavior = "Calm", note = "watching TV")),
            roundsOn = true, roundsSince = t0,
            shifts = listOf(Bh.Shift(id = "s1", start = t0, end = t0 + 8 * 3_600_000L, done = listOf("s_report"))),
            participants = listOf(Bh.Participant(id = "p1", alias = "Blue Jay", goals = listOf(Bh.Goal(id = "g1", domain = "Housing", text = "Find a room",
                steps = listOf(Bh.Step(id = "st", text = "Call the office", done = true)), target = t0)), capital = mapOf("Housing" to 2), capitalAt = t0)),
            plans = listOf(Bh.GroupPlan(id = "gp", title = "Mine", category = "Coping skills", objective = "o", opener = "x", activity = "y", discussion = listOf("q1", "q2"), closing = "z")),
            resources = listOf(Bh.Resource(id = "r", name = "County crisis team", category = "Crisis", phone = "555-0100")),
            favorites = setOf("r_988"),
            eoc = listOf(Bh.EocRound(id = "e", time = t0, areas = listOf(Bh.AreaCheck(id = "ar", area = "Bathroom", results = mapOf("b_shower" to "ISSUE"), issues = mapOf("b_shower" to "loose"), taskId = 9)))),
            records = listOf(Forms.Rec(id = "rec", kind = Specs.PEER_NOTE, created = t0, ref = "p1", values = mapOf("format" to "SOAP"), multi = mapOf("topics" to listOf("Sleep")))),
        )
        val back = Bh.fromJson(JSONObject(Bh.toJson(s).toString()))
        assertEquals(s, back)
    }

    // ---------------------------------------------------------------- forms & notes

    @Test fun specsAreWellFormed() {
        Specs.all.forEach { spec ->
            val keys = spec.fields.map { it.key }
            assertEquals("${spec.id} has unique keys", keys.size, keys.toSet().size)
            spec.fields.forEach { f ->
                if (f.type == Forms.Type.DROPDOWN || f.type == Forms.Type.MULTI) assertTrue("${spec.id}.${f.key} has options", f.options.isNotEmpty())
                f.showIf?.let { (k, _) -> assertTrue("${spec.id}.${f.key} depends on a real field", k in keys) }
            }
        }
    }

    @Test fun conditionalFieldsAppearOnlyWhenTriggered() {
        val f = Specs.peerNote.field("disclosurePurpose")!!
        val r = Forms.blank(Specs.peerNote)
        assertFalse(Forms.visible(f, r))
        assertTrue(Forms.visible(f, r.with("disclosed", "true")))
    }

    private fun peer() = Forms.blank(Specs.peerNote)
        .with("minutes", "30").with("goal", "feel less anxious before discharge").with("mood", "a little better").with("engagement", "Engaged")
        .with("disclosed", "true").with("disclosurePurpose", "Instill hope").with("commitment", "Call my sister tonight").with("next", "Tomorrow")
        .withMulti("topics", listOf("Discharge planning", "Sleep")).withMulti("interventions", listOf("Active listening & reflection", "Goal setting & action planning"))

    @Test fun peerNoteWritesEveryFormat() {
        val prefs = Bh.Prefs(signature = "P. M., CPSS")
        val dap = Writer.peerNote(peer(), "Blue Jay", prefs)
        assertTrue(dap.startsWith("PEER SUPPORT NOTE — Blue Jay"))
        assertTrue(dap.contains("D — Data") && dap.contains("A — Assessment") && dap.contains("P — Plan"))
        assertTrue(dap.contains("“feel less anxious before discharge”"))
        assertTrue(dap.contains("No safety concerns were expressed or observed"))
        assertTrue(dap.contains("Participant plans to call my sister tonight"))
        assertTrue(dap.contains("to instill hope"))
        assertTrue(dap.contains("P. M., CPSS"))

        assertTrue(Writer.peerNote(peer().with("format", "SOAP"), null, prefs).contains("S — Subjective"))
        assertTrue(Writer.peerNote(peer().with("format", "BIRP"), null, prefs).contains("I — Intervention"))
        assertTrue(Writer.peerNote(peer().with("format", "GIRP"), null, prefs).contains("G — Goal"))
        val narrative = Writer.peerNote(peer().with("format", "Narrative"), null, prefs)
        assertFalse(narrative.contains("— Data"))
        assertTrue(narrative.contains("Discussed discharge planning and sleep"))
    }

    @Test fun reportedSafetyConcernNamesWhoAndWhen() {
        val r = peer().with("safety", "Concern expressed — reported to clinical staff").with("reportedTo", "charge RN").with("reportedAt", "2:15 PM")
        assertTrue(Writer.peerNote(r, null, Bh.Prefs()).contains("Concern expressed — reported to clinical staff (charge RN at 2:15 PM)"))
    }

    @Test fun handoffIncludesTheCensusWithoutNames() {
        val p = Bh.Patient(id = "a", label = "JD", room = "12", obs = "Q15", precautions = listOf("Fall precautions"))
        val s = Bh.State(patients = listOf(p), checks = listOf(Bh.Check(patientId = "a", time = t0, location = "Day room", behavior = "Calm")))
        val r = Forms.blank(Specs.handoff).with("situation", "Quiet evening.").with("sharps", "Correct")
        val text = Writer.handoff(r, s, t0)
        assertTrue(text.contains("S — SITUATION\nQuiet evening."))
        assertTrue(text.contains("• Rm 12 · JD — Q15 · Fall precautions"))
        assertTrue(text.contains("Sharps count: Correct"))
    }

    @Test fun eocReportListsIssues() {
        val e = Bh.EocRound(time = t0, unit = "Adult acute", areas = listOf(Bh.AreaCheck(area = "Bathroom", label = "Rm 12", results = mapOf("b_shower" to "ISSUE", "b_faucet" to "OK"),
            issues = mapOf("b_shower" to "loose escutcheon"), risk = "High")))
        val text = Writer.eocReport(e)
        assertTrue(text.contains("1 issue found"))
        assertTrue(text.contains("✗ Shower head ligature-resistant / flush — loose escutcheon"))
        assertTrue(text.contains("Risk: High"))
    }

    // ---------------------------------------------------------------- library

    @Test fun libraryIdsAreUniqueAndCrisisLinesPresent() {
        val eocIds = Library.EOC_ITEMS.values.flatten().map { it.id }
        assertEquals(eocIds.size, eocIds.toSet().size)
        assertEquals(Library.RESOURCES.size, Library.RESOURCES.map { it.id }.toSet().size)
        assertEquals(Library.GROUPS.size, Library.GROUPS.map { it.id }.toSet().size)
        assertTrue(Library.GROUPS.size >= 18)
        assertEquals("988", Library.RESOURCES.first { it.id == "r_988" }.phone)
        assertTrue(Library.GROUPS.all { it.category in Library.GROUP_CATEGORIES })
        assertTrue(Library.RESOURCES.all { it.category in Library.RESOURCE_CATEGORIES })
    }

    @Test fun textLinesParse() {
        assertEquals("741741" to "HOME", Library.smsTarget("Text HOME to 741741"))
        assertEquals("678678" to "START", Library.smsTarget("Text START to 678-678"))
        assertEquals("62640" to "helpline", Library.smsTarget("Text “helpline” to 62640"))
        assertEquals("838255" to "", Library.smsTarget("838255"))
        assertNull(Library.smsTarget(""))
        assertNotNull(Library.smsTarget("988"))
    }
}
