package com.blacklab.app.assistant

import java.util.Locale

/**
 * The assistant's command grammar as an ordered table: which words mean which command. Pure (no Android,
 * no side effects), so every phrasing is unit tested in IntentsTest — when a phrasing is missed, add it to
 * the test's corpus first, then fix the rule.
 *
 * Order matters: the first rule that matches wins. Rules are split into two phases because the Facility,
 * Garage, Behavioral Health and desktop routers (which have their own vocabularies) run in between —
 * "what inspections are due?" must reach Facility before the generic "what's due?" task rule.
 *
 * [Match.groups] is like [MatchResult.groupValues]: index 0 is the whole match, then the capture groups,
 * taken from the original text so case is kept ("remind me to call Dr. Patel").
 */
object Intents {
    enum class Phase { EARLY, LATE }

    enum class Id {
        // EARLY: small talk, briefing, calendar, recorder
        HELP, THANKS, GREETING, TIME, DATE, BRIEFING, CREATE_EVENT, AGENDA,
        DICTATE_START, RECORD_START, RECORD_STOP, RECORD_PAUSE, MARKER,
        // LATE: everything after the domain routers
        WORK_ORDER_NEW, OPS_HANDOFF, TIMELINE, LIBRARY_SEARCH, VAULT_SAVE, VAULT_NODE_ADD, VAULT_LOG, VAULT_SEARCH, VAULT_STATUS, VAULT_OPEN, VAULT_SYNC,
        WEATHER, REMIND, ADD_TASK, ADD_TO_LIST, TODO_PREFIX, COMPLETE_TASK, LIST_TASKS, SUMMARIZE, MEETING_ACTIONS, NAMEPLATE,
        NAV_NEXT, NAV_NEARBY, NAV_TO, CALL, TALK_ABOUT, KEEP, NOTE, TEMPLATE, JOURNAL_WRITE, JOURNAL_OPEN,
        SCAN_DOCUMENT, SCAN_CODE, PHOTO, VIDEO, PANORAMA, FOCUS, LOG_CEU, GPA, SWITCH_PROFILE, THEME, SET_SETTING, TOGGLE_SETTING,
        GMAIL_UNREAD, GMAIL_SEARCH, EMAIL, DRIVE_SEARCH, DRIVE_UPLOAD, DEV_DOCS, NAVIGATE, ASK_FILES, ASK_FILES_QUESTION,
    }

    data class Match(val id: Id, val groups: List<String>) {
        /** The first non-blank capture group (rules with alternatives capture in different groups). */
        val firstGroup: String get() = groups.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
        operator fun get(i: Int): String = groups.getOrElse(i) { "" }
    }

    /**
     * @param onText match against the original text (case kept, case-insensitive) — the captured words are used.
     *   Otherwise the rule only tests the lower-cased text.
     * @param guard an extra condition on the lower-cased text and the match.
     */
    private class Rule(val id: Id, val phase: Phase, pattern: String, val onText: Boolean = false, val guard: ((String, MatchResult) -> Boolean)? = null) {
        val re = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    private fun has(re: String): (String) -> Boolean { val r = Regex(re); return { r.containsMatchIn(it) } }

    private val QUESTION = has("^(what|what's|whats|show|read|list|do i have|anything|any|how|am i|is there|tell)")
    private val TASK_QUESTION = has("^(what|what's|whats|show|read|list|do i have|any|how many|tell|my|tasks)")
    private val CALENDAR_WORDS = has("on (my )?calendar|^schedule|^book")
    private val CREATE_VERB = has("^(add|create|remind|schedule)")
    private val SEND_VERB = has("^(send|write|draft)")
    private val MEETING_WORDS = has("(meeting|dictation|recording|call|transcript)")
    /** Questions about what already happened in a meeting — for the notes and Ask my files, not the calendar. */
    private val ABOUT_PAST_MEETING = has("(action items?|minutes|follow[- ]?ups?|summar(y|ize|ise)|recap)|\\b(did|were|was) (we|i|they)\\b|\\b(decide|decided|discuss|discussed|said|say|agree|agreed)\\b")

    private val rules: List<Rule> = listOf(
        // ---------------------------------------------------------------- EARLY
        Rule(Id.HELP, Phase.EARLY, "^(help|what can you do|commands|what do you do|how do you work)"),
        Rule(Id.THANKS, Phase.EARLY, "^(thanks|thank you|thx|cheers|appreciate)"),
        Rule(Id.GREETING, Phase.EARLY, "^(hi|hello|hey|yo|sup|what's up|good (afternoon|evening))$"),
        Rule(Id.TIME, Phase.EARLY, "what time is it|current time|time is it"),
        Rule(Id.DATE, Phase.EARLY, "what('s| is) (the |today's )?date|what day is (it|today)"),
        Rule(Id.BRIEFING, Phase.EARLY, "^(brief me|briefing|(daily|morning) brief(ing)?|give me (my |the |a )?(daily |morning )?brief(ing)?)|good morning|morning report|how does my day look|what's my day|daily summary|catch me up"),
        // before AGENDA so "schedule a meeting" isn't read as a question
        Rule(Id.CREATE_EVENT, Phase.EARLY, "^(?:schedule|book|set up|add|create|put|plan)\\s+(?:an?\\s+)?(?:new\\s+)?(meeting|event|appointment|call|lunch|session|interview|inspection|walkthrough|class)?\\s*(.*)$",
            onText = true, guard = { l, m -> m.groupValues[1].isNotBlank() || CALENDAR_WORDS(l) }),
        Rule(Id.AGENDA, Phase.EARLY, "(agenda|calendar|schedule|meetings?|events?|appointments?)", guard = { l, _ -> QUESTION(l) && !ABOUT_PAST_MEETING(l) }),
        Rule(Id.DICTATE_START, Phase.EARLY, "^(start|begin) (dictation|dictating)|^dictate"),
        Rule(Id.RECORD_START, Phase.EARLY, "^(start|begin) (an? )?(audio )?(recording|recorder)|^record (audio|this|the meeting|a meeting|a voice memo)"),
        Rule(Id.RECORD_STOP, Phase.EARLY, "^(stop|end|finish|save) (the )?(recording|recorder|dictation|dictating)"),
        Rule(Id.RECORD_PAUSE, Phase.EARLY, "^(pause) (the )?(recording|recorder|dictation)"),
        Rule(Id.MARKER, Phase.EARLY, "^(add a |drop a |set a )?(marker|bookmark|mark that)"),

        // ---------------------------------------------------------------- LATE
        // "work order: leak under the sink in 214" — a filed work order, not a blank template (that needs no text after it)
        Rule(Id.WORK_ORDER_NEW, Phase.LATE, "^(?:new |create |open |write up |submit |file |put in )?(?:a |an )?(?:work order|wo|maintenance request|service request)(?: for| about)?[:,\\s-]+(.{4,})$|^report (?:a |an )?((?:leak|problem|issue|outage|broken|clog).{3,})$",
            onText = true, guard = { l, _ -> !Regex("name ?plate|data ?plate").containsMatchIn(l) }),
        Rule(Id.LIBRARY_SEARCH, Phase.LATE, "^(?:look (?:it |that )?up|search|check|find)(?: it| that)? in (?:the |my )?(?:offline )?library(?: for)?[:,\\s-]+(.{3,})$|^(?:search|ask) (?:the |my )?(?:offline )?library(?: for| about)?[:,\\s-]+(.{3,})$", onText = true),
        Rule(Id.OPS_HANDOFF, Phase.LATE, "\\b(ops|operations|facility|maintenance) hand ?off\\b|^hand ?off (report|summary)|^end of (the )?day (report|summary|handoff)"),
        Rule(Id.TIMELINE, Phase.LATE, "^(?:show |open )?(?:my |the |today's )?(?:day )?timeline$|^what (?:all )?happened today"),
        // Obsidian vault on the computer (first, so "note to the vault: …" isn't a phone note)
        Rule(Id.VAULT_SAVE, Phase.LATE, "^(?:save|add|put|capture|send|note|write)(?: this| that| a note)? (?:to|in|into|on) (?:my |the )?(?:obsidian|vault)(?: vault)?[:,\\s-]+(.{3,})$", onText = true),
        Rule(Id.VAULT_NODE_ADD, Phase.LATE, "^(?:add|save|put)(?: this| that)? (?:to|in|into) (?:the |my )?(.+?) node[:,\\s-]+(.{3,})$", onText = true),
        Rule(Id.VAULT_LOG, Phase.LATE, "^log (?:this |that )?(?:to|in|on) (?:my |the )?(?:obsidian|vault|timeline)[:,\\s-]+(.{3,})$", onText = true),
        Rule(Id.VAULT_SEARCH, Phase.LATE, "^(?:search|find|look (?:up|for)|what(?:'s| is| do i have)) (?:in )?(?:my |the )?(?:obsidian|vault)(?: vault)?(?: for| about)?[:\\s]+(.+)$", onText = true),
        Rule(Id.VAULT_STATUS, Phase.LATE, "what'?s new in (?:my |the )?(?:obsidian|vault)|^(?:obsidian|vault) status|how(?:'s| is) (?:my |the )?(?:obsidian|vault)"),
        Rule(Id.VAULT_OPEN, Phase.LATE, "^open (?:my |the )?(?:obsidian|vault)(?: vault)?(?: on (?:my |the )?(?:computer|desktop|pc))?$"),
        Rule(Id.VAULT_SYNC, Phase.LATE, "^(?:sync|update|refresh) (?:my |the )?(?:obsidian|vault)(?: vault)?(?: now)?$"),
        Rule(Id.WEATHER, Phase.LATE, "\\b(weather|forecast|temperature outside|how (hot|cold) is it|will it (freeze|rain|snow|storm)|freeze warning|weather alerts?)\\b",
            guard = { l, _ -> !CREATE_VERB(l) }),
        Rule(Id.REMIND, Phase.LATE, "^(?:remind me(?: to)?|don't let me forget(?: to)?)\\s+(.+)$", onText = true),
        Rule(Id.ADD_TASK, Phase.LATE, "^(?:add|create|new|make)(?: a| an)?(?: new)? (?:task|to-?do|todo|reminder)(?: to| for| called| named)?[:\\s]+(.+)$", onText = true),
        Rule(Id.ADD_TO_LIST, Phase.LATE, "^(?:add|put) (.+?) (?:to|on) (?:my )?(?:to-?do|todo|tasks|task list|list|my day)$", onText = true),
        Rule(Id.TODO_PREFIX, Phase.LATE, "^(?:to-?do|todo|task)[:\\s]+(.+)$", onText = true),
        Rule(Id.COMPLETE_TASK, Phase.LATE, "^(?:complete|finish|mark|check off|tick off|close|i (?:finished|completed|did))(?: the)?(?: task)? (.+?)(?: as)?(?: done| complete| completed| finished)?$", onText = true),
        Rule(Id.LIST_TASKS, Phase.LATE, "(tasks?|to-?dos?|to do list|what's due|whats due|overdue|my day)", guard = { l, _ -> TASK_QUESTION(l) }),
        Rule(Id.SUMMARIZE, Phase.LATE, "^(?:summari[sz]e|sum up|recap|give me (?:a |the )?(?:summary|recap|rundown) of|what's the rundown on)\\b\\s*(?:my |the |all |of )*(.*)$", onText = true),
        Rule(Id.MEETING_ACTIONS, Phase.LATE, "(action items?|actions|minutes|summar(y|ize|ise)|follow[- ]?ups?)", guard = { l, _ -> MEETING_WORDS(l) }),
        Rule(Id.NAMEPLATE, Phase.LATE, "(name ?plate|data ?plate|equipment (label|tag)|work order from|snap .*work order|scan (the |this |an? )?(equipment|unit|motor|boiler|chiller|rtu|pump))"),
        Rule(Id.NAV_NEXT, Phase.LATE, "^(?:navigate|directions|take me|drive|get me) to (?:my )?next (?:meeting|event|appointment|class)$", onText = true),
        Rule(Id.NAV_NEARBY, Phase.LATE, "^(?:find|navigate to|take me to|where'?s|directions to)(?: me)? (?:a |the |an )?(?:nearest |closest |nearby )?(gas station|gas|fuel|hyundai (?:dealer(?:ship)?|service(?: center)?)|car wash|coffee|ev charger|charging station|tire shop|auto parts|hardware store|home depot|lowe'?s|pharmacy|urgent care|parking)$", onText = true),
        Rule(Id.NAV_TO, Phase.LATE, "^(?:navigate|directions|drive|get me) to (.+)$", onText = true),
        Rule(Id.CALL, Phase.LATE, "^(?:call|dial) (?:the )?(?:number )?([+\\d][\\d\\s().-]{6,})$", onText = true),
        Rule(Id.TALK_ABOUT, Phase.LATE, "^(?:let'?s talk(?: about)?|talk to me about|can we talk about|i want to talk about|let'?s chat about|let'?s discuss|start (?:a |an |the )?(.+?) (?:conversation|chat|talk))\\s*(.*)$", onText = true),
        Rule(Id.KEEP, Phase.LATE, "^(?:save|add|put|make|create|take)?\\s*(?:a )?(?:note )?(?:to|in|on) (?:google )?keep[:\\s]*(.*)$|^keep note[:\\s]+(.+)$|^(?:note|remember) (.+?) (?:in|to|on) (?:google )?keep$", onText = true),
        Rule(Id.NOTE, Phase.LATE, "^(?:take a note|make a note|note to self|note|jot(?: down)?|write down|save (?:a )?note|new note|create (?:a )?(?:note|document|doc))(?: that| about| saying)?[:\\s]+(.+)$", onText = true),
        Rule(Id.TEMPLATE, Phase.LATE, "^(?:create|new|start|write|draft) (?:an? )?(memo|work order|incident report|meeting minutes|sop|inspection|pm log|project brief|vendor evaluation|budget request|weekly status|status report|shift handover|letter|checklist|cornell notes|travel plan)\\b", onText = true),
        Rule(Id.JOURNAL_WRITE, Phase.LATE, "^(?:journal|dear diary|diary|log (?:in|to) (?:my )?journal|add (?:a )?journal entry|new journal entry)(?: that| about)?[:,\\s]+(.+)$", onText = true),
        Rule(Id.JOURNAL_OPEN, Phase.LATE, "^(open|new|start) (a |my )?journal"),
        Rule(Id.SCAN_DOCUMENT, Phase.LATE, "^(scan|digitize) (a |this |the )?(document|page|paper|receipt|form)"),
        Rule(Id.SCAN_CODE, Phase.LATE, "^(scan) (a |the |this )?(qr|barcode|code|asset tag)"),
        Rule(Id.PHOTO, Phase.LATE, "(take|snap|grab) (a |an )?(photo|picture|pic|shot)|open (the )?camera"),
        Rule(Id.VIDEO, Phase.LATE, "(record|shoot|take) (a )?video"),
        Rule(Id.PANORAMA, Phase.LATE, "panorama|pano\\b"),
        Rule(Id.FOCUS, Phase.LATE, "^(start|begin) (a )?(focus|pomodoro|study)( session| timer)?"),
        Rule(Id.LOG_CEU, Phase.LATE, "^log (\\d+(?:\\.\\d+)?) (?:ceus?|pdhs?|hours?|credit hours?)(?: of (.+?))? (?:for|toward|towards|to) (.+)$", onText = true),
        Rule(Id.GPA, Phase.LATE, "\\b(gpa|grade point|my grades?)\\b"),
        Rule(Id.SWITCH_PROFILE, Phase.LATE, "^(?:switch|change|go) to (?:my )?(personal|professional|work)(?: account| profile| mode)?$", onText = true),
        Rule(Id.THEME, Phase.LATE, "^(?:turn on |switch to |use |enable )?(dark|light|system) (?:mode|theme)$", onText = true),
        Rule(Id.SET_SETTING, Phase.LATE, "^(?:set|change|make)\\s+(?:the |my )?(.+?)\\s+(?:to|=)\\s+(.+)$", onText = true),
        Rule(Id.TOGGLE_SETTING, Phase.LATE, "^(turn on|turn off|enable|disable|switch on|switch off)\\s+(?:the |my )?(.+)$", onText = true),
        Rule(Id.GMAIL_UNREAD, Phase.LATE, "(check|read|any|summari[sz]e|what's in|whats in|new|unread).*(e-?mails?|inbox|gmail|mail)|^(e-?mails?|inbox|gmail)$",
            guard = { l, _ -> !SEND_VERB(l) }),
        Rule(Id.GMAIL_SEARCH, Phase.LATE, "^(?:search|find|look for|look up|show)(?: my)? (?:e-?mails?|gmail|mail|inbox)(?: for| about| from)? (.+)$|^(?:e-?mails?|mail) from (.+)$", onText = true),
        Rule(Id.EMAIL, Phase.LATE, "^(send|write|draft|compose)(?: an?)?(?: e-?mail| message)? to (\\S+@\\S+)(?: (?:about|subject|re|regarding)[:\\s]+(.+?))?(?: (?:saying|that says|body|message)[:\\s]+(.+))?$", onText = true),
        Rule(Id.DRIVE_SEARCH, Phase.LATE, "^(?:search|find|look for|look up)(?: for)? (.+?) (?:in|on) (?:my )?(?:google )?drive$|^(?:google )?drive (?:search )?(?:for )?(.+)$", onText = true),
        Rule(Id.DRIVE_UPLOAD, Phase.LATE, "(upload|back ?up|save|send|copy) .*(to|on|in) (my )?(google )?drive"),
        Rule(Id.DEV_DOCS, Phase.LATE, "^(?:search |look up |check )?(?:the )?(?:android|kotlin|compose|jetpack|firebase|google cloud|gcp|developer|dev) docs?(?: for| about| on)?[:\\s]+(.+)$|^how do i (.+) in (?:android|kotlin|jetpack compose|compose|firebase)$", onText = true),
        Rule(Id.NAVIGATE, Phase.LATE, "^(?:open|go to|show(?: me)?|launch|take me to|pull up)(?: the| my)? (home|dashboard|tasks|to-?do|todos|schedule|calendar|education|learn|courses|certifications|library|files|photos|videos|recordings|scans|documents|docs|journal|notes|keep|brainstorm|recorder|dictation|settings|camera|assistant)$", onText = true),
        Rule(Id.ASK_FILES, Phase.LATE, "^(?:search|find|look up|look for|ask my files|search my (?:files|notes|docs|documents))(?: for| about)?[:\\s]+(.+)$", onText = true),
        Rule(Id.ASK_FILES_QUESTION, Phase.LATE, "^(what|when|where|who|which|did|how)\\b.*\\b(i|my|we|our)\\b.*\\b(write|wrote|say|said|note|noted|record|recorded|mention|mentioned|decide|decided|discuss|discussed|journal|meeting)"),
    )

    /** Every rule of [phase] that matches, in priority order (lazy: callers usually take the first). */
    fun matches(text: String, phase: Phase): Sequence<Match> {
        val l = text.lowercase(Locale.US)
        return rules.asSequence().filter { it.phase == phase }.mapNotNull { r ->
            val m = r.re.find(if (r.onText) text else l) ?: return@mapNotNull null
            if (r.guard != null && !r.guard.invoke(l, m)) return@mapNotNull null
            Match(r.id, m.groupValues)
        }
    }

    /** The command [text] means, if any (EARLY rules first, then LATE). Used by tests and the help screen. */
    fun classify(text: String): Match? = matches(text, Phase.EARLY).firstOrNull() ?: matches(text, Phase.LATE).firstOrNull()

    /** Words that mean the text is about someone's care — it never leaves the phone (the desktop refuses it too). */
    val CLINICAL = Regex("\\b(patients?|clients?|diagnos\\w*|medications?|meds|dosages?|prescri\\w*|sbar|peer notes?|behavioral health|therap(?:y|ist)|psychiatr\\w*|" +
        "psycholog\\w*|suicid\\w*|self[- ]harm|overdose|q ?15s?|mrn|hipaa|phi|date of birth|dob|ssn|social security)\\b", RegexOption.IGNORE_CASE)

    val YES = Regex("^(yes|yeah|yep|sure|confirm|do it|send it|go ahead|ok|okay|absolutely)\\b")
    val NO = Regex("^(no|nope|cancel|stop|never ?mind|don't)\\b")
}
