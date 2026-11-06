package com.blacklab.app.bh

import com.blacklab.app.bh.Forms.Field
import com.blacklab.app.bh.Forms.Section
import com.blacklab.app.bh.Forms.Spec
import com.blacklab.app.bh.Forms.Type

/** Every structured form in the Behavioral Health workspace. Options are plain lists, so they're easy to extend. */
object Specs {
    const val PEER_NOTE = "peer_note"
    const val BEHAVIOR = "behavior_note"
    const val INCIDENT = "incident"
    const val DEBRIEF = "debrief"
    const val HANDOFF = "handoff"
    const val GROUP_NOTE = "group_note"
    const val REFERRAL = "referral"
    const val SAFETY_PLAN = "wellness_plan"
    const val CHECKIN = "checkin"
    const val SUPERVISION = "supervision"
    const val SELFCARE = "selfcare"

    private fun t(k: String, l: String, hint: String = "", show: Pair<String, Set<String>>? = null, default: String = "", internal: Boolean = false) =
        Field(k, l, Type.TEXT, hint = hint, showIf = show, default = default, internal = internal)
    private fun long(k: String, l: String, hint: String = "", show: Pair<String, Set<String>>? = null, default: String = "") = Field(k, l, Type.LONG, hint = hint, showIf = show, default = default)
    private fun num(k: String, l: String, hint: String = "", show: Pair<String, Set<String>>? = null) = Field(k, l, Type.NUMBER, hint = hint, showIf = show)
    private fun dd(k: String, l: String, o: List<String>, default: String = "", show: Pair<String, Set<String>>? = null, hint: String = "") =
        Field(k, l, Type.DROPDOWN, o, default = default, showIf = show, hint = hint)
    private fun mm(k: String, l: String, o: List<String>, show: Pair<String, Set<String>>? = null, hint: String = "", default: String = "") =
        Field(k, l, Type.MULTI, o, showIf = show, hint = hint, default = default)
    private fun sl(k: String, l: String, hint: String = "", max: Int = 10) = Field(k, l, Type.SLIDER, hint = hint, max = max)
    private fun sw(k: String, l: String, hint: String = "", show: Pair<String, Set<String>>? = null) = Field(k, l, Type.SWITCH, hint = hint, showIf = show)
    private fun time(k: String, l: String) = Field(k, l, Type.TIME)

    // ---------------------------------------------------------------- shared option lists

    val RECOVERY_DOMAINS = listOf("Housing", "Employment & education", "Physical health & wellness", "Mental health", "Substance use recovery", "Relationships & family",
        "Social connection & community", "Legal", "Finances & benefits", "Transportation", "Spirituality & meaning", "Recreation & leisure", "Self-advocacy & rights",
        "Daily living skills", "Parenting", "Cultural identity")
    val STAGES = listOf("Precontemplation", "Contemplation", "Preparation", "Action", "Maintenance", "Recurrence / return to use", "Not assessed")
    val AFFECT = listOf("Calm", "Bright", "Euthymic", "Anxious", "Sad", "Tearful", "Irritable", "Angry", "Flat", "Guarded", "Labile", "Hopeful", "Expansive", "Fearful")
    val COPING = listOf("Deep breathing", "Grounding (5-4-3-2-1)", "Walk / movement", "Music", "Journaling", "Talking with someone", "Prayer / meditation", "Shower or bath",
        "Exercise", "Art / drawing", "Reading", "Sensory / comfort item", "Cold water / ice", "Progressive muscle relaxation", "Mutual-aid meeting", "Time outdoors", "Rest / nap", "Humor")
    val SHIFTS = listOf("Day", "Evening", "Night", "Double", "Mid", "Weekend", "On-call")
    val LOCATIONS = listOf("Patient room", "Bathroom", "Hallway", "Day room", "Dining room", "Group room", "Nurses' station", "Courtyard / outdoor", "Quiet / comfort room",
        "Seclusion room", "Entry / sally port", "Laundry", "Gym / activity room", "Off unit / transport", "Visiting area")

    // ---------------------------------------------------------------- 4 · peer support session note

    val FORMATS = listOf("DAP", "SOAP", "BIRP", "GIRP", "Narrative")

    val peerNote = Spec(PEER_NOTE, "Peer support session note", listOf(
        Section("Session", listOf(
            dd("format", "Note format", FORMATS, "DAP"),
            dd("type", "Contact type", listOf("Individual 1:1", "Group", "Phone", "Telehealth / video", "Bedside (inpatient)", "Community outreach", "Warm handoff",
                "Discharge planning", "Post-discharge follow-up", "Accompaniment", "Drop-in / recovery center", "Text / message check-in"), "Individual 1:1"),
            dd("setting", "Setting", listOf("Inpatient unit", "Residential", "Crisis unit", "Emergency department", "Outpatient clinic", "PHP / IOP", "Recovery community center",
                "Community", "Home visit", "Phone / virtual", "Court / legal setting", "Shelter"), "Inpatient unit"),
            time("when", "Date & time"),
            num("minutes", "Duration (minutes)"),
            dd("initiated", "Initiated by", listOf("Participant", "Peer specialist (scheduled)", "Peer specialist (outreach)", "Clinical staff referral", "Family / support person", "Self-referral walk-in")),
        )),
        Section("Focus", listOf(
            t("goal", "Participant's goal for today", "In their words"),
            mm("domains", "Recovery domains", RECOVERY_DOMAINS),
            mm("topics", "Topics discussed", listOf("Coping with symptoms", "Cravings & triggers", "Relapse / recurrence prevention", "Discharge planning", "Building a support network",
                "Hope & motivation", "Stigma & self-stigma", "Grief & loss", "Trauma (supportive listening only)", "Boundaries", "Self-care", "Navigating services",
                "Mutual-aid groups", "Medication concerns (referred to prescriber)", "Housing search", "Work or school", "Family relationships", "Spiritual wellness",
                "Rights & self-advocacy", "Recovery goal progress", "Wellness / crisis planning", "Daily routine & structure", "Sleep", "Anger & frustration", "Loneliness")),
        )),
        Section("Peer support provided", listOf(
            mm("interventions", "Peer interventions", listOf("Active listening & reflection", "Purposeful sharing of lived experience", "Motivational interviewing (OARS)",
                "Strengths-based exploration", "Goal setting & action planning", "WRAP / wellness planning", "Resource linkage", "Warm handoff", "Advocacy (with consent)",
                "Role-modeling recovery skills", "Coping skill practice", "Peer psychoeducation", "Recovery storytelling", "Systems navigation", "Accompanied to appointment",
                "Encouraged self-advocacy", "Mutual-aid meeting support", "Validation & normalizing", "Decision support", "Problem solving", "Celebrated progress")),
            sw("disclosed", "Used self-disclosure"),
            dd("disclosurePurpose", "Purpose of the disclosure", listOf("Instill hope", "Build trust & rapport", "Normalize the experience", "Model a coping skill",
                "Show that recovery is possible", "Reduce shame"), show = "disclosed" to setOf("true")),
            mm("skills", "Skills practiced", COPING),
        )),
        Section("Response", listOf(
            dd("engagement", "Engagement", listOf("Fully engaged", "Engaged", "Partially engaged", "Guarded but present", "Declined / not engaged", "Asleep / unavailable")),
            t("mood", "Mood (as they described it)", "e.g. “a little better today”"),
            mm("affect", "Presentation observed", AFFECT),
            long("statements", "What they said", "Quote key words exactly"),
            long("summary", "What happened in the session", "Facts, in order. Observations, not diagnoses."),
            dd("progress", "Progress toward goals", listOf("Progressing", "Maintaining", "Emerging", "Setback", "Goal met", "Not addressed today")),
            dd("stage", "Readiness for change (peer impression)", STAGES),
            mm("strengths", "Strengths noticed", listOf("Insight", "Humor", "Motivation", "Family support", "Faith", "Resilience", "Honesty", "Creativity", "Work history",
                "Sense of purpose", "Peer connections", "Help-seeking", "Persistence")),
        )),
        Section("Safety", listOf(
            dd("safety", "Safety", listOf("No safety concerns expressed or observed", "Concern expressed — reported to clinical staff", "Concern observed — reported to clinical staff",
                "Ongoing concern — clinical team already aware"), "No safety concerns expressed or observed"),
            t("reportedTo", "Reported to (role)", "e.g. charge RN, therapist", show = "safety" to setOf("Concern expressed — reported to clinical staff", "Concern observed — reported to clinical staff")),
            t("reportedAt", "Time reported", show = "safety" to setOf("Concern expressed — reported to clinical staff", "Concern observed — reported to clinical staff")),
        ), help = "Peers listen for safety concerns and report them right away — assessing risk belongs to the clinical team."),
        Section("Plan", listOf(
            long("plan", "Next steps"),
            t("commitment", "Participant's commitment", "What they chose to do next"),
            t("peerTask", "Peer follow-up", "What you'll do"),
            dd("next", "Next contact", listOf("Later today", "Tomorrow", "Within 2–3 days", "Next week", "Next group", "At discharge", "Post-discharge call", "As requested", "To be scheduled")),
            t("referrals", "Referrals / linkages"),
        )),
    ))

    // ---------------------------------------------------------------- 1 · MHT shift observation note

    private val meals = listOf("100%", "75%", "50%", "25%", "Less than 25%", "Refused", "Off unit / NPO", "Not observed")

    val behaviorNote = Spec(BEHAVIOR, "Shift observation note", listOf(
        Section("Presentation", listOf(
            t("mood", "Stated mood", "Their words"),
            mm("affect", "Affect", AFFECT),
            mm("behavior", "Behavior", listOf("Calm & cooperative", "Pleasant & social", "Withdrawn / isolative", "Restless / pacing", "Intrusive", "Irritable", "Demanding",
                "Verbally aggressive", "Physically aggressive", "Responding to internal stimuli", "Disorganized", "Hypervigilant", "Impulsive", "Tearful",
                "Sleeping most of shift", "Med-seeking", "Exit-seeking", "Sexually inappropriate", "Redirectable", "Not redirectable")),
            dd("speech", "Speech", listOf("Normal rate & volume", "Pressured", "Slow", "Loud", "Soft", "Minimal", "Mute", "Slurred", "Tangential")),
            dd("orientation", "Orientation (observed)", listOf("Appears oriented", "Confused at times", "Disoriented", "Not assessed")),
        )),
        Section("Milieu & groups", listOf(
            num("groupsAttended", "Groups attended"),
            num("groupsOffered", "Groups offered"),
            dd("participation", "Group participation", listOf("Active", "Appropriate", "Minimal", "Attended, did not participate", "Disruptive", "Declined all groups")),
            dd("milieu", "With peers", listOf("Social & appropriate", "Minimal interaction", "Isolative", "Conflict with a peer", "Boundary issues", "Supportive of peers")),
            dd("staff", "With staff", listOf("Cooperative", "Cooperative with prompting", "Guarded", "Hostile", "Seeks staff often")),
        )),
        Section("ADLs, intake & sleep", listOf(
            dd("hygiene", "Hygiene / ADLs", listOf("Independent", "Needed prompting", "Needed assistance", "Refused", "Not observed")),
            dd("breakfast", "Breakfast", meals), dd("lunch", "Lunch", meals), dd("dinner", "Dinner", meals),
            dd("fluids", "Fluids", listOf("Adequate", "Encouraged", "Poor", "Refused", "On restriction")),
            dd("sleep", "Sleep (night shift)", listOf("Slept through", "Slept with brief waking", "Restless", "Awake most of night", "Up at times, returned to bed", "N/A — day shift")),
            num("sleepHours", "Approx. hours slept"),
        )),
        Section("Vital signs (if delegated)", listOf(
            t("bp", "Blood pressure", "e.g. 124/78"), num("pulse", "Pulse"), t("temp", "Temperature"), num("resp", "Respirations"), num("spo2", "SpO₂ %"),
            t("weight", "Weight"), num("glucose", "Fingerstick glucose"),
            sw("vitalsReported", "Abnormal values reported to the nurse"),
        )),
        Section("Safety", listOf(
            mm("precautions", "Precautions maintained", Library.PRECAUTIONS),
            dd("incident", "Incidents this shift", listOf("None", "Yes — see incident report", "Near-miss reported")),
            sw("concernReported", "Concerns reported to the nurse"),
            t("concernWho", "Reported to / time", show = "concernReported" to setOf("true")),
        )),
        Section("Narrative", listOf(long("narrative", "Shift narrative", "Objective observations in time order"))),
    ))

    // ---------------------------------------------------------------- 3 · incident report draft

    val INTERVENTIONS = listOf("Verbal de-escalation", "Active listening", "Offered choices", "Reduced stimulation", "Offered space & time", "Offered comfort / sensory items",
        "Redirected to an activity", "Moved peers from the area", "Called for team support", "Show of support (staff presence)", "PRN offered by RN",
        "Physical hold (per policy)", "Seclusion (per order)", "Restraint (per order)", "Emergency medication (RN, per order)", "Security called", "911 / EMS", "Code called")

    val incident = Spec(INCIDENT, "Incident report draft", listOf(
        Section("Event", listOf(
            time("when", "Date & time of event"),
            dd("type", "Type of event", listOf("Verbal aggression", "Physical aggression — toward staff", "Physical aggression — toward peer", "Self-harm attempt",
                "Self-injurious behavior", "Ligature / strangulation attempt", "Elopement attempt", "Elopement / AWOL", "Fall", "Property destruction", "Contraband found",
                "Medical emergency", "Seizure", "Sexual behavior / boundary violation", "Refusal of care", "Intoxication / withdrawal concern", "Allegation of abuse",
                "Medication event (RN reports)", "Environmental hazard", "Other")),
            dd("location", "Location", LOCATIONS),
            t("involved", "Patients involved (initials only)"),
            t("staffPresent", "Staff present (roles)", "e.g. 2 MHTs, charge RN"),
            dd("severity", "Harm level", listOf("No harm", "Near miss", "Minor harm", "Moderate harm", "Major harm", "Sentinel event")),
            dd("code", "Code called", listOf("None", "Behavioral emergency / show of support", "Medical emergency", "Elopement", "Fire", "Security", "Facility-specific code")),
        )),
        Section("Before", listOf(
            mm("antecedents", "What came before", listOf("Request denied", "Limit set", "Peer conflict", "Phone call / visit", "Bad news", "Medication time", "Shift change / transition",
                "Noise / overstimulation", "Crowding", "Unmet need (hunger, pain, sleep)", "Trauma reminder", "Responding to internal stimuli", "Intoxication / withdrawal",
                "New admission", "Discharge news", "Boredom / no structure", "Unknown")),
            mm("warning", "Early warning signs", listOf("Pacing", "Raised voice", "Clenched fists", "Staring", "Withdrawal", "Swearing", "Threats", "Throwing objects",
                "Crying", "Rapid breathing", "Invading space", "Refusing redirection", "Banging / kicking")),
        )),
        Section("Response", listOf(
            mm("interventions", "Interventions used (in order where possible)", INTERVENTIONS),
            dd("phase", "Highest phase reached", Library.CRISIS_PHASES.map { it.name }),
            sw("restrictive", "Restrictive intervention used"),
            dd("restrictiveType", "Type", listOf("Physical hold", "Seclusion", "Mechanical restraint", "Emergency medication", "Hold + seclusion", "Hold + emergency medication"),
                show = "restrictive" to setOf("true")),
            t("restrictiveStart", "Start time", show = "restrictive" to setOf("true")),
            t("restrictiveEnd", "End time", show = "restrictive" to setOf("true")),
            sw("monitoring", "Continuous monitoring documented per policy", show = "restrictive" to setOf("true")),
        )),
        Section("Outcome", listOf(
            dd("injuries", "Injuries", listOf("None", "Patient — minor", "Patient — needs evaluation", "Staff — minor", "Staff — needs evaluation", "Peer injured", "Multiple")),
            sw("firstAid", "First aid given"),
            mm("notified", "Notified", listOf("Charge nurse", "Attending provider", "Nursing supervisor", "Administrator on call", "Family / guardian (per clinical staff)",
                "Security", "Risk management", "Facilities / maintenance", "Police", "Director of nursing")),
            t("damage", "Property damage"),
            dd("debrief", "Debrief", listOf("Not yet", "Scheduled", "Done with patient", "Done with staff", "Done with both")),
        )),
        Section("Narrative", listOf(
            long("narrative", "Objective narrative", "What you saw and heard, in order. Quote words exactly. No opinions or diagnoses."),
            long("followup", "Follow-up / recommendations"),
        )),
    ), help = "A draft to copy into your facility's incident reporting system — that system is the official report.")

    // ---------------------------------------------------------------- post-incident debrief

    val debrief = Spec(DEBRIEF, "Post-incident debrief", listOf(
        Section("Who & when", listOf(
            time("when", "Debrief date & time"),
            dd("kind", "Debrief type", listOf("Staff huddle (hot debrief)", "Formal staff debrief", "Patient debrief", "Staff + patient", "Peer-led debrief")),
            t("incident", "Related incident (date / type)"),
        )),
        Section("What happened", listOf(
            long("sequence", "Sequence of events"),
            mm("triggers", "Triggers identified", listOf("Request denied", "Peer conflict", "Noise", "Crowding", "Trauma reminder", "Pain / unmet need", "Medication timing",
                "Communication breakdown", "Transition", "Unknown")),
            mm("environment", "Environmental factors", listOf("Crowding", "Noise level", "Blind spot / poor sightline", "Staffing level", "Door / lock issue", "Furniture used as a weapon",
                "Ligature point involved", "Lighting", "Temperature", "Hard-to-clean area", "Nothing environmental")),
        )),
        Section("Learning", listOf(
            long("worked", "What worked"),
            long("didnt", "What didn't"),
            long("prevent", "What could prevent it next time"),
            mm("changes", "Changes to recommend", listOf("Treatment / care plan", "Observation level", "Staffing", "Training refresher", "Environment / facility repair",
                "Policy / procedure", "Communication at handoff", "Comfort-room resources", "Nothing at this time")),
            long("patientVoice", "Patient's perspective (in their words)"),
        )),
        Section("Staff wellbeing", listOf(
            sw("staffOk", "Staff checked in on"),
            sw("eap", "EAP / support offered"),
            long("staffNotes", "Notes"),
        )),
    ))

    // ---------------------------------------------------------------- 1 · SBAR shift handoff

    val handoff = Spec(HANDOFF, "Shift handoff (SBAR)", listOf(
        Section("Shift", listOf(
            dd("shift", "Shift", SHIFTS, "Day"),
            t("unit", "Unit"),
            num("census", "Census"),
            dd("acuity", "Unit acuity", listOf("Calm", "Settled with a few concerns", "Moderate", "Elevated", "High — extra support needed")),
            t("staffing", "Staffing / ratio", "e.g. 3 MHT : 22"),
        )),
        Section("S — Situation", listOf(long("situation", "Situation", "Right now on the unit"))),
        Section("B — Background", listOf(
            mm("events", "Events this shift", listOf("Admissions", "Discharges", "Transfers", "Incidents", "Restraint / seclusion", "Codes", "Elopement attempt", "Contraband search",
                "Room search", "Visitors", "Court / legal", "Maintenance issues", "Meal issues", "Groups cancelled", "Outings")),
            num("admits", "Admissions"), num("discharges", "Discharges"),
            long("background", "Background"),
        )),
        Section("A — Assessment", listOf(
            long("assessment", "Assessment", "Who needs extra eyes and why (initials / room only)"),
            t("obsChanges", "Observation level changes"),
        )),
        Section("R — Recommendation", listOf(
            long("recommendation", "Recommendation"),
            mm("pending", "Pending for next shift", listOf("Labs", "Consults", "Transport", "Court / legal", "Family meeting", "Discharge paperwork", "Work orders",
                "Supply restock", "Linen", "Room changes", "Belongings inventory", "New admission expected")),
        )),
        Section("Safety checks", listOf(
            dd("sharps", "Sharps count", listOf("Correct", "Discrepancy — reported", "Not my responsibility this shift")),
            sw("keys", "Keys, radios & duress devices accounted for"),
            dd("sweep", "Environment sweep", listOf("Done — no issues", "Done — issues reported", "Not done")),
            dd("fireDoors", "Fire doors / exits", listOf("Clear", "Issue reported")),
        )),
    ))

    // ---------------------------------------------------------------- 5 · group note

    val GROUP_TYPES = listOf("Psychoeducation", "Coping skills", "Peer support / sharing", "Recovery (peer-led)", "Mindfulness & relaxation", "Expressive arts", "Recreation / leisure",
        "Community meeting", "Goals group", "Wellness / health", "Relapse prevention", "Life skills", "Spirituality", "Music", "Movement")

    val groupNote = Spec(GROUP_NOTE, "Group session note", listOf(
        Section("Group", listOf(
            t("title", "Group title"),
            dd("type", "Group type", GROUP_TYPES),
            dd("role", "Your role", listOf("Lead facilitator", "Co-facilitator", "Peer facilitator", "Support staff")),
            time("when", "Date & time"),
            num("minutes", "Duration (minutes)"),
            num("attended", "Attended"), num("offered", "Offered to"),
        )),
        Section("Session", listOf(
            dd("participation", "Overall participation", listOf("Highly engaged", "Engaged", "Mixed", "Low", "Disrupted")),
            mm("dynamics", "Group dynamics", listOf("Supportive", "Members encouraged each other", "Engaged discussion", "Quiet", "Some side talk", "Conflict managed",
                "One member dominated — redirected", "Members shared lived experience", "Humor & connection", "Tearful moments supported")),
            mm("skills", "Skills practiced", COPING),
            long("summary", "Summary", "Topic, activity and key themes"),
            long("individual", "Brief individual notes (initials only)", "e.g. JD — shared a coping plan; RT — left early"),
            long("plan", "Next session / follow-up"),
        )),
    ))

    // ---------------------------------------------------------------- 6 · referral

    val referral = Spec(REFERRAL, "Referral & linkage", listOf(
        Section("Referral", listOf(
            t("resource", "Resource"),
            dd("category", "Category", Library.RESOURCE_CATEGORIES),
            t("who", "Participant (alias)"),
            t("reason", "Need / reason"),
            dd("how", "How", listOf("Information given", "Warm handoff", "Called together", "Application assistance", "Appointment scheduled", "Transportation arranged",
                "Accompanied", "Email / fax referral (per policy)")),
            dd("status", "Status", listOf("Made", "Pending", "Connected", "Not connected yet", "Declined", "Waitlisted", "Ineligible")),
            t("follow", "Follow up on"),
            long("notes", "Notes"),
        )),
    ))

    // ---------------------------------------------------------------- wellness & crisis plan (peer-supported)

    val wellnessPlan = Spec(SAFETY_PLAN, "Wellness & crisis plan (draft)", listOf(
        Section("Daily wellness", listOf(
            long("well", "What I'm like when I'm well"),
            long("toolbox", "My wellness toolbox", "Things that help me stay well"),
            long("daily", "Daily maintenance list"),
        )),
        Section("Triggers & early signs", listOf(
            mm("triggerTypes", "Triggers", listOf("Conflict", "Being alone", "Anniversaries", "Lack of sleep", "Money stress", "Certain people / places", "Crowds", "Pain",
                "Feeling judged", "Work stress", "Holidays", "Seeing substances")),
            long("triggers", "My triggers, in my words"),
            long("signs", "Early warning signs"),
            mm("coping", "What I can do on my own", COPING),
        )),
        Section("People & places", listOf(
            long("people", "People I can reach out to"),
            long("places", "Places that help me feel better"),
            long("pros", "Professional supports", "Therapist, prescriber, case manager, peer specialist"),
            long("crisisLines", "Crisis lines", default = "988 Suicide & Crisis Lifeline — call or text 988 (24/7)\nCrisis Text Line — text HOME to 741741"),
        )),
        Section("When things get hard", listOf(
            long("crisis", "Signs that I need others to step in"),
            long("helpers", "What helps — and what doesn't — when I'm in crisis"),
            long("safer", "Making my space safer", "Planned with the clinical team"),
            long("hope", "My reasons for hope"),
            long("after", "After a crisis — how I want to recover"),
        )),
    ), help = "Written with the person, in their words. Share it with the treatment team per your facility's policy.")

    // ---------------------------------------------------------------- 8 · my check-in, supervision, self-care

    val checkin = Spec(CHECKIN, "After-shift check-in", listOf(
        Section("How I'm doing", listOf(
            sl("energy", "Energy"), sl("stress", "Stress"),
            sl("meaning", "I felt good about the help I gave today"),
            sl("drained", "Emotionally drained"),
            sl("carry", "Someone else's pain stayed with me after work"),
            dd("sleep", "Sleep last night", listOf("Great", "Good", "Okay", "Poor", "Barely slept")),
            dd("boundaries", "My boundaries today", listOf("Held well", "Mostly held", "Stretched", "Crossed — want to talk it through")),
        )),
        Section("Reflection", listOf(
            long("win", "A win from today"),
            long("hard", "The hardest moment"),
            mm("coping", "What I'm doing to recharge", COPING + listOf("Time with family", "Hobby", "Therapy / counseling", "Supervision", "Unplugging")),
            sw("support", "I'd like support with something"),
            t("gratitude", "One thing I'm grateful for"),
        )),
    ))

    val SUPERVISION_TOPICS = listOf("Ethics", "Boundaries", "Self-disclosure", "Documentation", "A difficult situation", "Trauma-informed practice", "Cultural humility", "Self-care",
        "Role clarity", "Advocacy", "Recovery planning", "Resource navigation", "Crisis response", "Skill development", "Credential / career", "Group facilitation", "Team dynamics")

    val supervision = Spec(SUPERVISION, "Supervision log", listOf(
        Section("Session", listOf(
            time("when", "Date"),
            dd("type", "Type", listOf("Individual", "Group", "Peer supervision", "Clinical consultation", "Administrative", "Mentoring")),
            t("supervisor", "Supervisor"),
            t("supervisorCred", "Supervisor credentials"),
            num("minutes", "Minutes"),
            dd("format", "Format", listOf("In person", "Video", "Phone")),
            sw("counts", "Counts toward my credential"),
        )),
        Section("Content", listOf(
            mm("topics", "Topics", SUPERVISION_TOPICS),
            long("takeaways", "Key takeaways"),
            long("actions", "Action items"),
        )),
    ))

    val selfcare = Spec(SELFCARE, "My self-care plan", listOf(
        Section("Before shift", listOf(long("before", "How I get ready"))),
        Section("During shift", listOf(long("during", "Micro-breaks & resets"), mm("resets", "Quick resets", listOf("3 slow breaths", "Step outside", "Water", "Snack",
            "Stretch", "Check in with a coworker", "Name 3 things I see", "Cold water on wrists", "Short walk")))),
        Section("After shift", listOf(long("after", "How I leave work at work"), long("ritual", "My transition ritual", "e.g. music on the drive home"))),
        Section("Warning signs I'm running low", listOf(long("signs", "Signs"), long("ask", "Who I'll tell / what I'll do"))),
    ))

    val all = listOf(peerNote, behaviorNote, incident, debrief, handoff, groupNote, referral, wellnessPlan, checkin, supervision, selfcare)
    fun spec(kind: String) = all.firstOrNull { it.id == kind }
}
