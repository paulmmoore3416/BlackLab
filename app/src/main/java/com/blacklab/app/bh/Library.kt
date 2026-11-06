package com.blacklab.app.bh

/**
 * Built-in content for the Behavioral Health workspace: crisis phases and de-escalation guidance,
 * shift checklists, a group-plan library, national crisis & recovery resources and the
 * environment-of-care (ligature risk) checklist. This is general reference — always follow your
 * facility's policies, your training (CPI, Mandt, Safety-Care, etc.) and the nurse in charge.
 */
object Library {

    val PRECAUTIONS = listOf("Suicide precautions", "Self-harm precautions", "Assault / aggression precautions", "Elopement precautions", "Fall precautions",
        "Seizure precautions", "Sexual acting-out precautions", "Withdrawal monitoring", "Aspiration / choking precautions", "Allergy alert", "Sharps restriction",
        "Room restriction", "Close observation at meals", "Fluid restriction", "Diabetic", "Trauma-informed approach plan", "Contraband watch")

    // ---------------------------------------------------------------- rounds codes

    val CHECK_LOCATIONS = listOf("In room", "Bathroom", "Hallway", "Day room", "Dining", "Group", "Nurses' station", "Courtyard", "Quiet / comfort room", "Gym / activity",
        "Seclusion", "Off unit (with staff)", "Off unit (pass / appointment)", "Visiting", "Phone", "Shower")
    val CHECK_BEHAVIORS = listOf("Sleeping — breathing observed", "Resting / lying in bed", "Calm", "Talking with peers", "Talking with staff", "In group", "Eating",
        "Watching TV / reading", "Pacing", "Isolative", "Tearful", "Agitated", "Responding to internal stimuli", "Hygiene / ADLs", "On phone", "With visitor",
        "Refused to respond — safety confirmed", "Not seen — searching")

    // ---------------------------------------------------------------- 3 · crisis & de-escalation

    data class Phase(val name: String, val looks: List<String>, val respond: List<String>, val say: List<String>, val avoid: List<String>)

    val CRISIS_PHASES = listOf(
        Phase("1 · Early warning / anxiety",
            listOf("Pacing, fidgeting, restlessness", "Withdrawing or going quiet", "Change in tone or eye contact", "Repeated questions or requests", "Clenched jaw or fists"),
            listOf("Notice early and move toward the person calmly", "Be supportive: listen, empathize, ask what they need", "Offer choices and a quieter space", "Reduce noise, crowding and audience", "Meet an unmet need if you can (water, food, phone call, blanket)"),
            listOf("“I noticed you seem frustrated. What's going on?”", "“I'm here. Take your time.”", "“Would it help to step somewhere quieter with me?”", "“What would make the next few minutes easier?”"),
            listOf("Ignoring the signs", "Arguing over small things", "Crowding or standing over them", "Sarcasm or rushed tone")),
        Phase("2 · Escalating / defensive",
            listOf("Raised voice, swearing", "Challenging staff or rules", "Refusing redirection", "Verbal threats", "Invading personal space"),
            listOf("Stay calm and keep your own voice low and slow", "Set clear, simple, enforceable limits — with choices and consequences stated respectfully", "Give space: at least a leg's length, at an angle, hands visible", "One staff speaks; others support quietly nearby", "Call for support early — before it's needed"),
            listOf("“I want to help you. I can't do that while you're yelling. Can we sit down and talk?”", "“You have a choice: we can talk here, or take a break in the quiet room.”", "“I hear that this feels unfair. Let's figure out what we can do.”"),
            listOf("Power struggles or ultimatums you can't enforce", "Touching without warning", "Blocking the exit", "Taking it personally", "Too many people talking")),
        Phase("3 · Crisis / risk behavior",
            listOf("Physical aggression toward self, others or property", "Attempting to leave unsafely", "Loss of control"),
            listOf("Safety first — for the person, peers and staff", "Call the team / code per policy; clear the area of peers", "Use only the least-restrictive intervention you are trained and authorized to use", "Restrictive interventions only per policy and order, with continuous monitoring", "Keep talking calmly: tell them what you're doing and why"),
            listOf("“We're going to keep everyone safe.”", "“When you're ready, we'll let go / step back.”", "Short, calm directions — one at a time"),
            listOf("Any technique you aren't trained in", "Pressure on chest, neck or back", "Prone positioning unless trained & permitted", "Punitive language")),
        Phase("4 · Recovery / tension reduction",
            listOf("Energy drops, may be tearful or exhausted", "Apologizing or quiet", "Still sensitive — can re-escalate"),
            listOf("Give time and space to calm", "Offer water, a blanket, privacy", "Check for injuries; tell the nurse", "Rebuild the relationship — no lectures yet", "Return to routine gradually"),
            listOf("“You're safe now.”", "“Take all the time you need.”", "“I'm glad you're okay. We'll talk when you're ready.”"),
            listOf("Re-hashing the incident right away", "Shaming or “I told you so”", "Rushing back to demands")),
        Phase("5 · Post-crisis / debrief",
            listOf("Calm and able to reflect", "Ready to talk about what happened"),
            listOf("Debrief with the person: what led up to it, what helped, what to try next time", "Update the care plan / triggers list with the team", "Debrief as a staff team and check on each other", "Document objectively and promptly", "Look at environment & staffing factors"),
            listOf("“What was going on for you before things got hard?”", "“What helped? What made it worse?”", "“What can we both do differently next time?”"),
            listOf("Skipping the debrief", "Blame", "Leaving staff injuries or stress unaddressed")),
    )

    val TRAUMA_INFORMED = listOf(
        "Safety" to "Physical and emotional safety for everyone — predictable routines, calm voices, explaining before doing.",
        "Trustworthiness & transparency" to "Say what you'll do and do what you say. Explain rules and decisions.",
        "Peer support" to "People with lived experience build hope, connection and trust.",
        "Collaboration & mutuality" to "Power is shared. Do things with people, not to them.",
        "Empowerment, voice & choice" to "Offer real choices. Build on strengths. Believe people's experiences.",
        "Cultural, historical & gender issues" to "Recognize bias and historical trauma; respect identity, language and culture.",
    )

    val DEESCALATION_TIPS = listOf(
        "Respect personal space — about 1.5 to 3 feet, at an angle, never blocking an exit (theirs or yours).",
        "Your body talks first: open hands, relaxed posture, eyes soft, at their eye level if safe.",
        "Lower and slow your voice. Short sentences. One person speaks.",
        "Listen for the need under the behavior. Reflect it back: “It sounds like you're worried about…”",
        "Validate feelings without agreeing to unsafe behavior.",
        "Offer two acceptable choices instead of a demand.",
        "Set limits that are clear, simple, reasonable and enforceable.",
        "Allow silence — it gives time to think and calm.",
        "Ignore challenging questions; redirect to the real issue.",
        "Know when to step away and let a colleague try (tag out).",
        "Notice your own stress response and breathe before you speak.",
        "Debrief afterward — every time.",
    )

    val GROUNDING = listOf(
        "5-4-3-2-1" to "Name 5 things you can see, 4 you can touch, 3 you can hear, 2 you can smell, 1 you can taste.",
        "Box breathing" to "Breathe in 4 counts, hold 4, out 4, hold 4. Repeat four times.",
        "Feet on the floor" to "Press both feet into the floor. Notice the weight and the support under you.",
        "Cold reset" to "Hold something cold or splash cool water on your face and wrists.",
        "Name it to tame it" to "Say the feeling out loud: “I'm feeling angry right now.” Naming it lowers the intensity.",
        "Safe place" to "Picture a place where you feel safe. Notice the colors, sounds and temperature.",
        "Counting colors" to "Find everything blue in the room. Then everything green.",
        "Hand on heart" to "One hand on your chest, one on your belly. Slow your breathing until the lower hand moves more.",
    )

    // ---------------------------------------------------------------- 1 · shift checklists

    data class CheckItem(val id: String, val text: String, val phase: String)

    val SHIFT_CHECKLIST = listOf(
        CheckItem("s_report", "Received report / SBAR from the outgoing shift", "Start"),
        CheckItem("s_assign", "Know my assignment, observation levels and precautions", "Start"),
        CheckItem("s_keys", "Keys, radio and duress/panic device checked out and working", "Start"),
        CheckItem("s_sharps", "Sharps / restricted items count", "Start"),
        CheckItem("s_sweep", "Environment sweep — rooms, bathrooms, common areas", "Start"),
        CheckItem("s_exits", "Exits, fire doors and egress paths clear", "Start"),
        CheckItem("s_codes", "Know today's code team / response roles", "Start"),
        CheckItem("s_rounds", "Started observation rounds", "Start"),
        CheckItem("d_groups", "Groups run or covered as scheduled", "During"),
        CheckItem("d_meals", "Meal and fluid intake observed and recorded", "During"),
        CheckItem("d_vitals", "Delegated vitals done and abnormal values reported", "During"),
        CheckItem("d_adls", "ADLs / hygiene support offered", "During"),
        CheckItem("d_search", "Belongings / room searches per policy", "During"),
        CheckItem("d_visitors", "Visitor items checked per policy", "During"),
        CheckItem("d_milieu", "Milieu checks — tone, conflicts, who's isolating", "During"),
        CheckItem("d_doc", "Documentation kept current (observation, notes)", "During"),
        CheckItem("e_notes", "All notes completed in the EHR", "End"),
        CheckItem("e_count", "End-of-shift sharps / restricted items count", "End"),
        CheckItem("e_sweep", "End-of-shift environment sweep", "End"),
        CheckItem("e_workorders", "Maintenance issues reported / work orders entered", "End"),
        CheckItem("e_handoff", "Gave report to the oncoming shift", "End"),
        CheckItem("e_return", "Keys, radio and devices returned", "End"),
        CheckItem("e_self", "Did my after-shift check-in", "End"),
    )

    // ---------------------------------------------------------------- 5 · group library

    val GROUP_CATEGORIES = listOf("Coping skills", "Recovery & relapse prevention", "Wellness & self-care", "Emotions", "Relationships & communication", "Hope & meaning",
        "Life skills", "Mindfulness", "Expressive & creative", "Community meeting", "Goal setting", "Trauma-informed psychoeducation")

    private fun g(id: String, title: String, cat: String, min: Int, obj: String, opener: String, act: String, q: List<String>, close: String, mat: String = "", adapt: String = "", aud: String = "Any") =
        Bh.GroupPlan(id = id, title = title, category = cat, minutes = min, audience = aud, objective = obj, opener = opener, activity = act, discussion = q, closing = close, materials = mat, adaptations = adapt, builtin = true)

    val GROUPS = listOf(
        g("g_coping_toolbox", "Building a coping toolbox", "Coping skills", 45,
            "Each member leaves with at least five coping skills they're willing to try.",
            "Check-in: one word for how you're arriving today.",
            "Sort a deck of coping-skill cards into “use now”, “want to try” and “not for me”. Pairs pick two to practice for a minute each (breathing, grounding, stretching). Members write their toolbox on an index card.",
            listOf("What has helped you get through a hard day before?", "Which skills work in a hurry, and which take time?", "Where could you keep your toolbox card?"),
            "Each person names one skill they'll use before bed tonight.", "Index cards, pens, coping-skill cards", "Use pictures on the cards for members who prefer not to read aloud."),
        g("g_triggers", "Knowing my triggers", "Recovery & relapse prevention", 45,
            "Identify personal triggers and one plan for each.",
            "Share something that always calms you down.",
            "Members map triggers into people, places, things, feelings and times. In pairs, build an “if–then” plan for the top two.",
            listOf("Which trigger surprised you?", "What's an early sign that a trigger is working on you?", "Who could help you stick to your plan?"),
            "Read your if–then plan out loud (or to yourself).", "Trigger worksheet or whiteboard", "For detox or early recovery, keep it brief and concrete."),
        g("g_wrap_intro", "Wellness planning basics", "Wellness & self-care", 60,
            "Introduce the idea of a personal wellness plan: daily maintenance, triggers, early signs and a crisis plan.",
            "Describe yourself on a good day in three words.",
            "Walk through the parts of a wellness plan with examples from the facilitator's own recovery (purposeful sharing). Members draft their daily maintenance list.",
            listOf("What do you do on a good day that you forget on a hard one?", "Who should know your plan?", "What would make this plan feel like yours?"),
            "One item from your daily list you'll do tomorrow.", "Blank wellness plan pages", "Peer-led group; offer to finish plans 1:1."),
        g("g_hope", "Stories of hope", "Hope & meaning", 45,
            "Build hope through recovery stories and members' own strengths.",
            "What's something small that went right this week?",
            "Facilitator shares a short, purposeful recovery story (beginning, turning point, now). Members write a six-word story of their own journey so far.",
            listOf("What gives you hope right now?", "Who believed in you when you didn't?", "What does recovery look like for you?"),
            "Read your six-word story (pass is always okay).", "Paper, markers"),
        g("g_emotions_wave", "Riding the wave of emotions", "Emotions", 45,
            "Understand that emotions rise, peak and pass; practice tolerating intensity without acting on urges.",
            "Rate your mood 1–10 and one word for it.",
            "Draw an emotion as a wave: trigger, rising, peak, falling. Practice an urge-surfing exercise for two minutes.",
            listOf("What do you usually do at the peak?", "What helps the wave come down faster?", "What's one urge you've ridden out before?"),
            "Re-rate your mood 1–10.", "Paper and pencils"),
        g("g_communication", "Asking for what I need", "Relationships & communication", 45,
            "Practice clear, respectful requests and saying no.",
            "Share a time someone listened to you well.",
            "Teach a simple request formula: describe the situation, say how you feel, ask clearly, explain the benefit. Role-play in pairs with everyday unit situations.",
            listOf("Why is asking hard sometimes?", "How do you say no and keep the relationship?", "What changes when you stay calm?"),
            "One request you'll make this week.", "Role-play cards"),
        g("g_boundaries", "Healthy boundaries", "Relationships & communication", 45,
            "Recognize healthy and unhealthy boundaries and practice setting one.",
            "Name a personal space you like to keep.",
            "Sort examples into healthy, unhealthy and unsure. Build a boundary statement for one real situation.",
            listOf("How do you know a boundary's been crossed?", "What gets in the way of saying it?", "Who respects your boundaries?"),
            "Say your boundary statement to yourself.", "Example cards"),
        g("g_sleep", "Sleep and recovery", "Wellness & self-care", 40,
            "Share practical sleep habits that support mood and recovery.",
            "How did you sleep last night, 1–5?",
            "Brainstorm a wind-down routine. Practice a five-minute body scan.",
            listOf("What keeps you up?", "What's one change you could make tonight?", "How does sleep affect cravings or mood for you?"),
            "Pick one wind-down step for tonight.", "Optional calm music"),
        g("g_gratitude", "Gratitude and small wins", "Hope & meaning", 30,
            "Practice noticing good moments to support mood.",
            "Something you're glad you have today.",
            "Gratitude round, then each member lists three small wins from the past week. Discuss how attention shapes mood.",
            listOf("Was it hard to find wins?", "Who could you thank this week?", "How might noticing good things help on hard days?"),
            "Share one win to take with you.", "Paper"),
        g("g_mindful", "Mindful minutes", "Mindfulness", 30,
            "Experience three short mindfulness practices.",
            "Notice three sounds in the room.",
            "Mindful breathing (2 min), mindful eating with a raisin or mint (3 min), and a walking or stretching practice (3 min). Debrief after each.",
            listOf("What did you notice?", "When during the day could you use one of these?", "What made it hard to focus — and that's okay?"),
            "One word for how you feel now.", "Raisins or mints (check allergies and diet orders)", "Offer chairs; keep eyes-open option."),
        g("g_relapse_plan", "My relapse prevention plan", "Recovery & relapse prevention", 60,
            "Draft a personal relapse-prevention plan.",
            "What does a strong day in recovery look like?",
            "Members fill in high-risk situations, warning signs, coping responses, supports and a plan for a slip. Pairs review each other's plans for one idea to add.",
            listOf("What would you say to a friend after a slip?", "Who's on your support team?", "What's your plan for the first 72 hours after discharge?"),
            "Name one support you'll contact after discharge.", "Relapse-prevention worksheet", aud = "Substance use"),
        g("g_community", "Community meeting", "Community meeting", 30,
            "Set the tone for the day, review the schedule and share concerns.",
            "Name and one goal for today.",
            "Review the schedule and unit expectations; members raise concerns and suggestions; recognize positive moments.",
            listOf("What would make today go well?", "Any concerns we should solve together?"),
            "Read the day's goal list.", "Schedule board"),
        g("g_goals", "Small steps, big goals", "Goal setting", 45,
            "Turn a recovery goal into a small, specific next step.",
            "Something you've accomplished that you're proud of.",
            "Teach SMART steps. Each member picks a life area, writes one goal and breaks it into a step they can do in 24 hours.",
            listOf("What makes a goal feel doable?", "What might get in the way?", "Who can cheer you on?"),
            "Share your 24-hour step.", "Goal worksheet"),
        g("g_art_feelings", "Feelings through art", "Expressive & creative", 45,
            "Express feelings without needing words.",
            "Pick a color for your mood today.",
            "Members draw or collage “how I feel now” and “how I want to feel”. Optional sharing.",
            listOf("What was it like to make it?", "What's different between the two pictures?", "What could help you move toward the second one?"),
            "One word about your art.", "Paper, crayons, magazines, glue sticks (safety-approved, counted)", "Count supplies in and out."),
        g("g_stigma", "Stigma and self-talk", "Trauma-informed psychoeducation", 45,
            "Understand stigma and practice kinder self-talk.",
            "A label you've been called that didn't fit you.",
            "Discuss person-first language. Members rewrite three harsh self-statements into fair, kind ones.",
            listOf("Where have you seen stigma?", "What does your inner critic say?", "How would you talk to a friend?"),
            "Read one new self-statement.", "Worksheet"),
        g("g_stress_body", "Stress and the body", "Trauma-informed psychoeducation", 45,
            "Learn how stress shows up in the body and simple ways to calm it.",
            "Where do you feel stress first?",
            "Explain the fight-flight-freeze response in plain language. Practice paced breathing and progressive muscle relaxation.",
            listOf("What's your body's early stress signal?", "Which practice felt best?", "When could you use it?"),
            "Rate tension before and after, 1–10.", "Body outline handout"),
        g("g_leisure", "Leisure and fun in recovery", "Life skills", 40,
            "Plan sober / healthy leisure activities.",
            "Something fun you used to love.",
            "Brainstorm free or low-cost activities by category (outdoors, creative, social, quiet). Each member plans one for the week after discharge.",
            listOf("What gets in the way of fun?", "Who could join you?", "What did you enjoy as a kid?"),
            "Name your planned activity.", "Whiteboard"),
        g("g_discharge", "Getting ready for discharge", "Life skills", 45,
            "Prepare for the transition home.",
            "One thing you're looking forward to.",
            "Checklist: appointments, medications questions for the nurse, supports, safe place to stay, transportation, crisis numbers. Members note what's still missing.",
            listOf("What worries you about going home?", "What's your first-day plan?", "Who will you call if things get hard?"),
            "Write down 988 and one person's number.", "Discharge checklist handout"),
    )

    // ---------------------------------------------------------------- 6 · resources

    val RESOURCE_CATEGORIES = listOf("Crisis", "Substance use", "Mutual aid / recovery groups", "Mental health", "Housing & shelter", "Food", "Medical & dental",
        "Medication-assisted treatment", "Benefits & insurance", "Employment & education", "Legal", "Transportation", "Domestic violence", "Veterans", "Youth & LGBTQ+",
        "Family & caregivers", "Disability", "Phone & internet", "Clothing & hygiene", "Spiritual", "Other")

    private fun r(id: String, name: String, cat: String, phone: String = "", text: String = "", url: String = "", hours: String = "24/7", notes: String = "") =
        Bh.Resource(id = id, name = name, category = cat, phone = phone, text = text, url = url, hours = hours, notes = notes, cost = "Free", builtin = true)

    /** National US resources. Confirm numbers periodically; add your local ones in the app. */
    val RESOURCES = listOf(
        r("r_988", "988 Suicide & Crisis Lifeline", "Crisis", "988", "988", "https://988lifeline.org", notes = "Call, text or chat. Press 1 for the Veterans Crisis Line; Spanish available."),
        r("r_ctl", "Crisis Text Line", "Crisis", text = "Text HOME to 741741", url = "https://www.crisistextline.org"),
        r("r_vcl", "Veterans Crisis Line", "Veterans", "988", "838255", "https://www.veteranscrisisline.net", notes = "Dial 988 then press 1, or text 838255."),
        r("r_samhsa", "SAMHSA National Helpline", "Substance use", "1-800-662-4357", url = "https://www.samhsa.gov/find-help/helplines/national-helpline", notes = "Free, confidential treatment referral and information, English and Spanish."),
        r("r_findtx", "FindTreatment.gov", "Substance use", url = "https://findtreatment.gov", hours = "Online", notes = "Search licensed treatment facilities by location and service."),
        r("r_nami", "NAMI HelpLine", "Mental health", "1-800-950-6264", "Text “helpline” to 62640", "https://www.nami.org/help", hours = "Weekdays, 10am–10pm ET", notes = "Information, resources and support; not a crisis line."),
        r("r_211", "211 — local services", "Other", "211", url = "https://www.211.org", notes = "Housing, food, utilities, transportation and other local help."),
        r("r_trevor", "The Trevor Project", "Youth & LGBTQ+", "1-866-488-7386", "Text START to 678-678", "https://www.thetrevorproject.org", notes = "Crisis support for LGBTQ+ young people."),
        r("r_translife", "Trans Lifeline", "Youth & LGBTQ+", "1-877-565-8860", url = "https://translifeline.org", hours = "See website", notes = "Peer support run by and for trans people."),
        r("r_dv", "National Domestic Violence Hotline", "Domestic violence", "1-800-799-7233", "Text START to 88788", "https://www.thehotline.org"),
        r("r_rainn", "RAINN Sexual Assault Hotline", "Domestic violence", "1-800-656-4673", url = "https://www.rainn.org"),
        r("r_childhelp", "Childhelp National Child Abuse Hotline", "Family & caregivers", "1-800-422-4453", url = "https://www.childhelphotline.org"),
        r("r_poison", "Poison Help", "Medical & dental", "1-800-222-1222", url = "https://www.poison.org"),
        r("r_ddh", "Disaster Distress Helpline", "Crisis", "1-800-985-5990", "1-800-985-5990", "https://www.samhsa.gov/find-help/disaster-distress-helpline"),
        r("r_aa", "Alcoholics Anonymous", "Mutual aid / recovery groups", url = "https://www.aa.org", hours = "Meetings daily", notes = "Meeting Guide app lists local and online meetings."),
        r("r_na", "Narcotics Anonymous", "Mutual aid / recovery groups", url = "https://na.org", hours = "Meetings daily"),
        r("r_smart", "SMART Recovery", "Mutual aid / recovery groups", url = "https://smartrecovery.org", hours = "Online & in person"),
        r("r_alanon", "Al-Anon & Alateen", "Family & caregivers", url = "https://al-anon.org", hours = "Meetings daily", notes = "Support for families and friends."),
        r("r_recoverydharma", "Recovery Dharma", "Mutual aid / recovery groups", url = "https://recoverydharma.org", hours = "Online & in person"),
        r("r_celebrate", "Celebrate Recovery", "Spiritual", url = "https://www.celebraterecovery.com", hours = "Weekly meetings"),
        r("r_lifeline_phone", "Lifeline phone & internet discount (FCC)", "Phone & internet", url = "https://www.fcc.gov/lifeline-consumers", hours = "Online", notes = "Discounted phone or internet for eligible households."),
        r("r_benefits", "Benefits.gov eligibility finder", "Benefits & insurance", url = "https://www.benefits.gov", hours = "Online"),
        r("r_healthcare", "HealthCare.gov / Medicaid", "Benefits & insurance", "1-800-318-2596", url = "https://www.healthcare.gov"),
        r("r_hrsa", "Find a Health Center (HRSA)", "Medical & dental", url = "https://findahealthcenter.hrsa.gov", hours = "Online", notes = "Sliding-fee community health centers."),
        r("r_buprenorphine", "Buprenorphine practitioner locator", "Medication-assisted treatment", url = "https://findtreatment.gov", hours = "Online"),
        r("r_va", "VA Health Care enrollment", "Veterans", "1-877-222-8387", url = "https://www.va.gov/health-care"),
    )

    // ---------------------------------------------------------------- 7 · environment of care / ligature risk

    data class EocItem(val id: String, val text: String, val group: String)

    /** What to look at, by area. Written for behavioral-health units; adapt to your environmental risk assessment. */
    val EOC_ITEMS: Map<String, List<EocItem>> = mapOf(
        "Patient room" to listOf(
            EocItem("door_hinge", "Door hinges continuous / ligature-resistant", "Door"), EocItem("door_hw", "Lever / pull hardware ligature-resistant", "Door"),
            EocItem("door_top", "No gap or anchor point at top of door", "Door"), EocItem("door_alarm", "Door-top / over-door alarm working (if installed)", "Door"),
            EocItem("closer", "No exposed door closer arm", "Door"), EocItem("vision", "Vision panel intact, unobstructed", "Door"),
            EocItem("bed", "Bed ligature-resistant, no exposed springs or rails", "Furniture"), EocItem("furn", "Furniture weighted / anchored, intact, no loose parts", "Furniture"),
            EocItem("ward", "Wardrobe / shelving without rods or hooks", "Furniture"), EocItem("window", "Window glazing intact, limited opening, no cords", "Window"),
            EocItem("blinds", "Blinds sealed between glass (no cords)", "Window"), EocItem("light", "Light fixtures tamper-resistant, sealed lens", "Ceiling"),
            EocItem("sprinkler", "Sprinkler heads institutional / concealed", "Ceiling"), EocItem("tiles", "Ceiling tiles secure, clipped, none missing", "Ceiling"),
            EocItem("vents", "Air grilles small-perforation, secured", "Ceiling"), EocItem("outlets", "Outlets tamper-resistant, plates secured", "Electrical"),
            EocItem("switches", "Switch plates secured, tamper screws", "Electrical"), EocItem("walls", "Walls / paint intact — no holes, exposed anchors or wires", "Surfaces"),
            EocItem("contraband", "No contraband or unapproved items", "Search"), EocItem("cords", "No cords, belts, laces, bags or tubing", "Search"),
        ),
        "Bathroom" to listOf(
            EocItem("b_shower", "Shower head ligature-resistant / flush", "Fixtures"), EocItem("b_controls", "Shower controls push-button / ligature-resistant", "Fixtures"),
            EocItem("b_faucet", "Faucet ligature-resistant", "Fixtures"), EocItem("b_toilet", "Toilet flush valve concealed / push-button", "Fixtures"),
            EocItem("b_grab", "Grab bars continuous to wall (no gap)", "Fixtures"), EocItem("b_hooks", "Robe / towel hooks collapsible or none", "Fixtures"),
            EocItem("b_curtain", "Shower curtain breakaway / none", "Fixtures"), EocItem("b_mirror", "Mirror shatter-resistant, sealed edges", "Fixtures"),
            EocItem("b_door", "Bathroom door ligature-resistant / alarmed (if required)", "Door"), EocItem("b_pipes", "No exposed pipes under sink", "Fixtures"),
            EocItem("b_dispensers", "Dispensers ligature-resistant", "Fixtures"), EocItem("b_drain", "Floor drain secure, no standing water", "Surfaces"),
        ),
        "Day room / common area" to listOf(
            EocItem("c_furn", "Furniture heavy / anchored; nothing throwable", "Furniture"), EocItem("c_tv", "TV enclosed behind polycarbonate, no exposed cords", "Electrical"),
            EocItem("c_sightline", "Sightlines clear; convex mirrors intact", "Supervision"), EocItem("c_art", "Artwork secured, no glass", "Surfaces"),
            EocItem("c_phone", "Phone cord length per policy / cordless", "Electrical"), EocItem("c_games", "Games & supplies counted, no cords or sharps", "Search"),
            EocItem("c_ceiling", "Ceiling, sprinklers and lights secure", "Ceiling"), EocItem("c_windows", "Windows intact, no cords", "Window"),
        ),
        "Group / activity room" to listOf(
            EocItem("g_supplies", "Art supplies locked and counted", "Search"), EocItem("g_furn", "Furniture appropriate / stackable chairs secured", "Furniture"),
            EocItem("g_equip", "Equipment stored, no cords accessible", "Electrical"), EocItem("g_sightline", "Door vision panel clear", "Supervision"),
        ),
        "Quiet / comfort room" to listOf(
            EocItem("q_soft", "Soft furnishings intact, no tears or loose stuffing", "Furniture"), EocItem("q_sensory", "Sensory items safe and inspected", "Search"),
            EocItem("q_light", "Lighting / dimmer controls staff-side or tamper-resistant", "Electrical"), EocItem("q_view", "Staff can observe from outside", "Supervision"),
        ),
        "Seclusion room" to listOf(
            EocItem("s_walls", "Walls & floor intact, padding (if any) undamaged", "Surfaces"), EocItem("s_view", "Viewing window / camera working, full visibility", "Supervision"),
            EocItem("s_light", "Light & sprinkler flush, tamper-resistant", "Ceiling"), EocItem("s_door", "Door opens outward, hardware intact", "Door"),
            EocItem("s_temp", "Temperature comfortable", "Environment"), EocItem("s_clean", "Clean, nothing left inside", "Search"),
        ),
        "Hallway / corridor" to listOf(
            EocItem("h_rails", "Handrails continuous / ligature-resistant", "Fixtures"), EocItem("h_mirrors", "Convex mirrors at blind corners", "Supervision"),
            EocItem("h_carts", "No unattended carts (housekeeping, med, linen)", "Search"), EocItem("h_exits", "Exits clear, signs lit, fire doors closing", "Life safety"),
            EocItem("h_extinguisher", "Fire extinguisher cabinet locked / inspected", "Life safety"), EocItem("h_doors", "Staff-only doors locked", "Security"),
        ),
        "Nurses' station / staff areas" to listOf(
            EocItem("n_sharps", "Sharps and meds locked", "Security"), EocItem("n_chem", "Chemicals / cleaning supplies locked", "Security"),
            EocItem("n_cords", "Cords and equipment out of reach", "Electrical"), EocItem("n_duress", "Duress / panic alarm tested", "Security"),
            EocItem("n_keys", "Key control log current", "Security"),
        ),
        "Courtyard / outdoor" to listOf(
            EocItem("o_fence", "Fence intact, no climb points", "Security"), EocItem("o_furn", "Furniture anchored", "Furniture"),
            EocItem("o_objects", "No loose rocks, branches, debris", "Search"), EocItem("o_shade", "Shade / heat safety", "Environment"),
            EocItem("o_gate", "Gate locked, hardware ligature-resistant", "Security"),
        ),
        "Entry / sally port" to listOf(
            EocItem("e_doors", "Doors interlock / lock correctly", "Security"), EocItem("e_lockers", "Lockers for belongings secured", "Security"),
            EocItem("e_search", "Search area supplies (wand, gloves) ready", "Search"), EocItem("e_camera", "Camera working", "Supervision"),
        ),
        "Laundry / utility" to listOf(
            EocItem("l_lock", "Door locked when unattended", "Security"), EocItem("l_chem", "Chemicals locked", "Security"),
            EocItem("l_cords", "Dryer vent / cords not accessible", "Electrical"),
        ),
    )

    val EOC_AREAS = EOC_ITEMS.keys.toList()
    val RISK_LEVELS = listOf("Low", "Medium", "High", "Immediate — area closed")
    val MITIGATIONS = listOf("Fixed on the spot", "Work order entered", "Area closed until repaired", "Increased observation in area", "Item removed", "Reported to charge RN",
        "Reported to director / safety officer", "Added to environmental risk assessment", "Vendor called")

    /** "Text HOME to 741741" → (741741, HOME); "838255" → (838255, ""). Null when there's no number to text. */
    fun smsTarget(text: String): Pair<String, String>? {
        val t = text.trim().ifBlank { return null }
        Regex("(?i)text\\s+[“\"]?([^”\"]+?)[”\"]?\\s+to\\s+([\\d-]+)").find(t)?.let { m -> return m.groupValues[2].filter { it.isDigit() } to m.groupValues[1].trim() }
        val digits = t.filter { it.isDigit() }
        return if (digits.length >= 3) digits to "" else null
    }
}
