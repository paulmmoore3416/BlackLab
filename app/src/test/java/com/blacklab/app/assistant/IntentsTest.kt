package com.blacklab.app.assistant

import com.blacklab.app.assistant.Intents.Id
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The assistant's phrasing corpus. When a phrasing is misunderstood, add it here first (it fails),
 * then fix the rule in Intents.kt.
 */
class IntentsTest {
    private val corpus = listOf(
        "help" to Id.HELP,
        "what can you do" to Id.HELP,
        "thanks" to Id.THANKS,
        "hey" to Id.GREETING,
        "what time is it" to Id.TIME,
        "what's the date" to Id.DATE,
        "what day is it" to Id.DATE,
        "brief me" to Id.BRIEFING,
        "good morning" to Id.BRIEFING,
        "how does my day look" to Id.BRIEFING,
        "schedule a meeting with facilities tomorrow at 2" to Id.CREATE_EVENT,
        "book lunch with Sam friday at noon" to Id.CREATE_EVENT,
        "add an inspection on my calendar monday" to Id.CREATE_EVENT,
        "what's on my calendar tomorrow" to Id.AGENDA,
        "do i have any meetings today" to Id.AGENDA,
        "what meetings do i have this week" to Id.AGENDA,
        "start dictation" to Id.DICTATE_START,
        "record the meeting" to Id.RECORD_START,
        "stop recording" to Id.RECORD_STOP,
        "pause the recording" to Id.RECORD_PAUSE,
        "drop a marker" to Id.MARKER,

        "work order: leak under the sink in room 214, B-wing" to Id.WORK_ORDER_NEW,
        "report a leak in the kitchen ceiling" to Id.WORK_ORDER_NEW,
        "look it up in the library: how long to pressure can green beans" to Id.LIBRARY_SEARCH,
        "search the library for sourdough hydration" to Id.LIBRARY_SEARCH,
        "add to the sourdough node: rye starters are faster" to Id.VAULT_NODE_ADD,
        "ops handoff" to Id.OPS_HANDOFF,
        "end of day report" to Id.OPS_HANDOFF,
        "what happened today" to Id.TIMELINE,
        "save this to obsidian: chiller vendor prefers Tuesday visits" to Id.VAULT_SAVE,
        "save to my private drive: renew the boiler license in March" to Id.PRIVATE_DRIVE_SAVE,
        "save this to my private drive under taxes: W-2 arrives late January" to Id.PRIVATE_DRIVE_SAVE,
        "add to the facility node: AHU-2 belts are B-section, 2 per unit" to Id.VAULT_NODE_ADD,
        "log to the vault: finished the EOC rounds" to Id.VAULT_LOG,
        "search the vault for generator load test" to Id.VAULT_SEARCH,
        "what do my notes say about the boiler vendor" to Id.VAULT_ASK,
        "what skills do you have" to Id.SKILLS,
        "list your skills" to Id.SKILLS,
        "find a skill for filling out pdf forms" to Id.SKILLS,
        "which skill should i use for a powerpoint deck" to Id.SKILLS,
        "use the xlsx skill to build a PM tracker" to Id.SKILL_USE,
        "with the frontend-design skill, restyle the dashboard" to Id.SKILL_USE,
        "ask my vault: when is the elevator inspection" to Id.VAULT_ASK,
        "according to my notes, who services the generator" to Id.VAULT_ASK,
        "shift prep" to Id.SHIFT_PREP,
        "prep me for my shift" to Id.SHIFT_PREP,
        "start EOC rounds" to Id.ROUNDS_START,
        "begin the fire and life safety audit" to Id.ROUNDS_START,
        "start a daily ops report" to Id.ROUNDS_START,
        "start a plan of correction" to Id.ROUNDS_START,
        "which breaker feeds room 428" to Id.PANEL_LOOKUP,
        "what circuit is AHU-5 on" to Id.PANEL_LOOKUP,
        "find the breaker for 312" to Id.PANEL_LOOKUP,
        "sds for isopropyl alcohol" to Id.SDS_LOOKUP,
        "pull up the safety data sheet for virex" to Id.SDS_LOOKUP,
        "when does my BLS expire" to Id.CREDENTIALS,
        "are any of my certifications expiring" to Id.CREDENTIALS,
        "what's new in the vault" to Id.VAULT_STATUS,
        "show RTU-4 in my graph" to Id.VAULT_SHOW,
        "show me the facility node in the graph" to Id.VAULT_SHOW,
        "open my graph" to Id.VAULT_SHOW,
        "open obsidian on my computer" to Id.VAULT_OPEN,
        "sync the vault" to Id.VAULT_SYNC,
        "what's the weather" to Id.WEATHER,
        "will it freeze tonight" to Id.WEATHER,
        "remind me to call Dr. Patel at 3" to Id.REMIND,
        "add a task to order MERV 13 filters" to Id.ADD_TASK,
        "put replace ballast on my to-do" to Id.ADD_TO_LIST,
        "todo: check the roof drains" to Id.TODO_PREFIX,
        "mark order filters as done" to Id.COMPLETE_TASK,
        "what's due today" to Id.LIST_TASKS,
        "show my overdue tasks" to Id.LIST_TASKS,
        "summarize this week as action items" to Id.SUMMARIZE,
        "what were the action items from the meeting" to Id.MEETING_ACTIONS,
        "read the nameplate" to Id.NAMEPLATE,
        "navigate to my next meeting" to Id.NAV_NEXT,
        "find the nearest gas station" to Id.NAV_NEARBY,
        "navigate to 100 Main Street" to Id.NAV_TO,
        "call 555-201-3344" to Id.CALL,
        "let's talk about RAG" to Id.TALK_ABOUT,
        "save to keep: gate code changed" to Id.KEEP,
        "note to self: the chiller tech comes Thursday" to Id.NOTE,
        "draft a work order" to Id.TEMPLATE,
        "journal: long day, good outcome" to Id.JOURNAL_WRITE,
        "open my journal" to Id.JOURNAL_OPEN,
        "scan a receipt" to Id.SCAN_DOCUMENT,
        "scan the asset tag" to Id.SCAN_CODE,
        "take a photo" to Id.PHOTO,
        "record a video" to Id.VIDEO,
        "panorama" to Id.PANORAMA,
        "start a focus session" to Id.FOCUS,
        "log 2 ceus of NFPA 101 for CFM" to Id.LOG_CEU,
        "what's my gpa" to Id.GPA,
        "switch to professional" to Id.SWITCH_PROFILE,
        "dark mode" to Id.THEME,
        "set the assistant name to Todd" to Id.SET_SETTING,
        "turn off widget privacy" to Id.TOGGLE_SETTING,
        "check my email" to Id.GMAIL_UNREAD,
        "search my email for invoice" to Id.GMAIL_SEARCH,
        "send an email to alex@example.com about the roof saying it's done" to Id.EMAIL,
        "find budget in my drive" to Id.DRIVE_SEARCH,
        "upload my latest scan to drive" to Id.DRIVE_UPLOAD,
        "android docs for foreground services" to Id.DEV_DOCS,
        "open settings" to Id.NAVIGATE,
        "search my files for roof warranty" to Id.ASK_FILES,
        "what did we decide in the budget meeting" to Id.ASK_FILES_QUESTION,
    )

    @Test fun everyPhrasingLandsOnItsCommand() {
        val wrong = corpus.mapNotNull { (text, want) ->
            val got = Intents.classify(text)?.id
            if (got != want) "\"$text\" → $got (wanted $want)" else null
        }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test fun capturesKeepTheOriginalCase() {
        assertEquals("call Dr. Patel at 3", Intents.classify("Remind me to call Dr. Patel at 3")!![1])
        val email = Intents.classify("send an email to alex@example.com about the roof saying it's done")!!
        assertEquals(listOf("alex@example.com", "the roof", "it's done"), listOf(email[2], email[3], email[4]))
        assertEquals("gate code changed", Intents.classify("save to keep: gate code changed")!!.firstGroup)
    }

    @Test fun orderAndGuardsResolveAmbiguity() {
        // A request to schedule, not a question about the schedule
        assertEquals(Id.CREATE_EVENT, Intents.classify("schedule a walkthrough friday")!!.id)
        // "add … weather" is a task, not a forecast
        assertEquals(Id.ADD_TASK, Intents.classify("add a task to check weather stripping")!!.id)
        // Writing mail is not reading the inbox
        assertEquals(Id.EMAIL, Intents.classify("send an email to alex@example.com saying new filters are in")!!.id)
    }

    @Test fun declinedMatchesFallThroughToTheNextRule() {
        // "set X to Y" that isn't a setting must still have later candidates (none here, so the sequence ends)
        val all = Intents.matches("summarize the roof project", Intents.Phase.LATE).map { it.id }.toList()
        assertEquals(Id.SUMMARIZE, all.first())
    }

    @Test fun nonCommandsMatchNothing() {
        assertNull(Intents.classify("the quick brown fox"))
        assertNull(Intents.classify("explain how a VFD saves energy"))
    }

    @Test fun clinicalTextNeverGoesToTheVault() {
        assertEquals(true, Intents.CLINICAL.containsMatchIn("save to obsidian: patient in 4 refused meds"))
        assertEquals(false, Intents.CLINICAL.containsMatchIn("save to obsidian: generator passed its monthly load run"))
    }

    @Test fun confirmations() {
        assertEquals(true, Intents.YES.containsMatchIn("yes send it"))
        assertEquals(true, Intents.NO.containsMatchIn("never mind"))
        assertEquals(false, Intents.YES.containsMatchIn("not yet"))
    }
}
