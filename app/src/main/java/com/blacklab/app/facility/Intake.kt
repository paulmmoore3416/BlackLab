package com.blacklab.app.facility

/**
 * Work-order intake from plain words — typed, dictated or said to the assistant on a walk-through:
 * “Leak under the sink in room 214, B-wing” → a titled work order with trade, priority (and why), location,
 * the matching asset when one is registered, and a short safety-first checklist for that trade.
 * Pure and offline (unit tested in IntakeTest); FacilityReminders.addIntake files it.
 */
object Intake {
    enum class Priority(val label: String, val taskPriority: Int, val hours: Int?) {
        P1("Emergency · respond within 1 hour", 4, 1),
        P2("Urgent · within 24 hours", 3, 24),
        P3("Routine · within 7 days", 2, 168),
        P4("Planned · schedule it", 1, null),
    }

    data class Draft(
        val title: String,
        val what: String,
        val trade: String,
        val priority: Priority,
        val reasons: List<String>,
        val location: String,
        val asset: Facility.Asset?,
        val checklist: List<String>,
    ) {
        fun notes(): String = buildString {
            appendLine(what)
            appendLine()
            appendLine("Priority: ${priority.name} — ${priority.label}" + if (reasons.isNotEmpty()) " (${reasons.joinToString(", ")})" else "")
            appendLine("Trade: $trade")
            if (location.isNotBlank()) appendLine("Location: $location")
            asset?.let { a -> appendLine("Asset: ${a.name}" + (a.tag.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") + (a.location.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")) }
            if (checklist.isNotEmpty()) { appendLine(); checklist.forEach { appendLine("☐ $it") } }
        }.trim()
    }

    private val TRADES = listOf(
        "Life safety" to Regex("\\b(fire alarm|smoke (detector|alarm)|pull station|exit sign|emergency light|extinguisher|sprinkler|fire door|door (won't|wont|doesn't|does not) (close|latch)|egress|blocked exit|ligature|eyewash)\\b"),
        "Elevator" to Regex("\\b(elevator|lift)\\b"),
        "Electrical" to Regex("\\b(outlet|receptacle|breaker|power|lights? (out|flicker\\w*)|flicker\\w*|ballast|spark\\w*|burning smell|gfci|panel|wiring|switch)\\b"),
        "Plumbing" to Regex("\\b(leak\\w*|drip\\w*|toilet|urinal|sink|faucet|clog\\w*|drain|flood\\w*|sewage|water heater|no hot water|backed up|overflow\\w*)\\b"),
        "HVAC" to Regex("\\b(hvac|no heat|no ac|no a/c|air conditioning|too (hot|cold)|thermostat|ahu|rtu|vav|chiller|boiler|vent|airflow|humidity|freezing)\\b"),
        "Roof & envelope" to Regex("\\b(roof|ceiling (tile|stain|leak)|window|skylight|gutter|water stain)\\b"),
        "Doors & hardware" to Regex("\\b(door|lock|latch|closer|hinge|key|badge reader|card reader)\\b"),
        "Grounds" to Regex("\\b(parking|sidewalk|pothole|snow|ice|landscap\\w*|irrigation|fence|gate)\\b"),
    )

    private val P1 = listOf(
        "flooding" to Regex("\\b(flood\\w*|burst|water (pouring|everywhere))\\b"),
        "fire or smoke" to Regex("\\b(fire|smoke|burning smell)\\b(?! (alarm|door|extinguisher|sprinkler|drill))"),
        "gas smell" to Regex("\\b(gas (smell|leak)|smell(s|ing)? (of |like )?gas)\\b"),
        "electrical hazard" to Regex("\\b(spark\\w*|exposed wir\\w*|shock\\w*)\\b"),
        "power outage" to Regex("\\b(no power|power (is )?out|outage)\\b"),
        "someone trapped" to Regex("\\b(stuck|trapped)\\b.*\\belevator|\\belevator\\b.*\\b(stuck|trapped)\\b"),
        "sewage" to Regex("\\bsewage\\b"),
        "ligature risk" to Regex("\\bligature\\b"),
        "security" to Regex("\\b(won't lock|wont lock|doesn't lock|does not lock|broken lock|security breach|propped open)\\b"),
        "life safety impaired" to Regex("\\b(fire alarm (down|trouble|not working)|sprinkler (leak|damage|impair\\w*)|blocked exit)\\b"),
    )
    private val P2 = listOf(
        "leak" to Regex("\\bleak\\w*\\b"),
        "no heat or cooling" to Regex("\\b(no heat|no ac|no a/c|too (hot|cold)|freezing)\\b"),
        "no hot water" to Regex("\\bno hot water\\b"),
        "out of service" to Regex("\\b(not working|out of (service|order)|broken|won't (start|turn on)|dead)\\b"),
        "restroom down" to Regex("\\b(toilet|urinal)\\b.*\\b(clog\\w*|overflow\\w*|running|broken)\\b|\\bclog\\w*\\b"),
    )
    private val P4 = Regex("\\b(when you (can|get a chance)|low priority|no rush|eventually|someday|planned|next (month|quarter)|cosmetic|paint|touch[- ]?up)\\b")

    private val LOCATION = Regex("(?i)\\b(?:(?:room|rm\\.?|suite|office|unit)\\s*[A-Z]?\\d+[A-Z]?|[A-Z](?:-| )wing|[a-z]+ wing|(?:floor|level)\\s*\\d+|\\d+(?:st|nd|rd|th)\\s+floor|(?:bldg\\.?|building)\\s*[A-Z0-9]+|(?:north|south|east|west) (?:hall|wing|stair\\w*|entrance|lot)|(?:main|front|back|rear) (?:lobby|entrance|hall)|(?:kitchen|cafeteria|laundry|mechanical room|boiler room|nurses? station|day ?room|courtyard|parking lot|basement|roof))\\b")

    private val CHECKLIST = mapOf(
        "Life safety" to listOf("Assess the impairment now — is an ILSM or fire watch needed?", "Notify the charge person and, if the system is impaired, the alarm/sprinkler vendor and AHJ per policy",
            "Document the impairment start/end time", "Photo before and after"),
        "Elevator" to listOf("If anyone is trapped: call the elevator vendor's emergency line, stay in voice contact, do not attempt a rescue", "Take the car out of service and post a sign", "Log the vendor ticket number"),
        "Electrical" to listOf("Keep people clear; de-energize and lockout/tagout before any work (NFPA 70E)", "Check for heat or a burning smell at the panel", "Photo before and after"),
        "Plumbing" to listOf("Stop the water: isolate the fixture or the zone valve", "Wet-floor signs; protect the ceiling and equipment below", "Dry within 24–48 h to prevent mold; note the affected area", "Photo before and after"),
        "HVAC" to listOf("Check the thermostat setpoint, schedule and BAS alarms", "Check filters, belts and the unit's breaker", "Note space temperature and outdoor conditions"),
        "Roof & envelope" to listOf("Protect what's below (bucket, plastic) and mark the spot", "Check the roof drain/gutter above", "Photo the stain/opening; note weather"),
        "Doors & hardware" to listOf("If it's a fire or secured door: post staff or fix before end of shift", "Check closer, latch and strike alignment", "Test after repair"),
        "Grounds" to listOf("Cone or barricade the hazard", "Salt/sand for ice", "Photo before and after"),
        "General" to listOf("Confirm the location and who reported it", "Photo before and after"),
    )

    fun parse(text: String, state: Facility.State? = null): Draft {
        val what = text.trim().replace(Regex("\\s+"), " ")
        val l = what.lowercase()
        val trade = TRADES.firstOrNull { it.second.containsMatchIn(l) }?.first ?: "General"
        val p1 = P1.filter { it.second.containsMatchIn(l) }.map { it.first }
        val p2 = P2.filter { it.second.containsMatchIn(l) }.map { it.first }
        val (priority, reasons) = when {
            p1.isNotEmpty() -> Priority.P1 to p1
            p2.isNotEmpty() -> Priority.P2 to p2
            P4.containsMatchIn(l) -> Priority.P4 to listOf("marked low priority")
            else -> Priority.P3 to emptyList()
        }
        val location = LOCATION.findAll(what).map { it.value.trim() }.distinctBy { it.lowercase() }.joinToString(", ")
        val asset = state?.let { matchAsset(what, it, trade) }
        val title = what.split(Regex("(?<=[.!?])\\s|;")).first().trimEnd('.', ',').let { t ->
            if (t.length <= 70) t else t.take(67).substringBeforeLast(' ') + "…"
        }.replaceFirstChar { it.uppercase() }
        return Draft(title, what, trade, priority, reasons, location, asset, CHECKLIST[trade] ?: CHECKLIST.getValue("General"))
    }

    /** By tag (FAC-0042), then by name (AHU-2), then by location + matching kind. */
    fun matchAsset(text: String, s: Facility.State, trade: String): Facility.Asset? {
        val l = text.lowercase()
        s.assets.firstOrNull { it.tag.isNotBlank() && Regex("\\b${Regex.escape(it.tag.lowercase())}\\b").containsMatchIn(l) }?.let { return it }
        s.assets.filter { it.name.length >= 3 && Regex("\\b${Regex.escape(it.name.lowercase())}\\b").containsMatchIn(l) }.maxByOrNull { it.name.length }?.let { return it }
        val kindFor = mapOf("HVAC" to "HVAC", "Plumbing" to "Plumbing", "Electrical" to "Electrical", "Elevator" to "Elevator", "Life safety" to "Life safety", "Roof & envelope" to "Roof & envelope")
        val kind = kindFor[trade] ?: return null
        return s.assets.filter { it.kind == kind && it.location.isNotBlank() && l.contains(it.location.lowercase()) }.singleOrNull()
    }
}
