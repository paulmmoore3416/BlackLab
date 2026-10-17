package com.blacklab.app.ui.documents

import com.blacklab.app.core.Settings
import com.blacklab.app.core.Time
import com.blacklab.app.data.ContentStore

data class DocTemplate(val id: String, val name: String, val description: String, val emoji: String, val professional: Boolean, val build: (Ctx) -> String)

data class Ctx(val name: String, val title: String, val org: String, val date: String) {
    companion object {
        fun from(s: Settings) = Ctx(s.userName.ifBlank { "Your name" }, s.jobTitle, s.organization, Time.longDate(Time.today()) + ", " + Time.today().year)
    }
}

private fun e(s: String) = ContentStore.escape(s)
private fun table(header: List<String>, rows: Int, prefill: List<List<String>> = emptyList()): String {
    val sb = StringBuilder("<table><tbody><tr>")
    header.forEach { sb.append("<th>").append(e(it)).append("</th>") }
    sb.append("</tr>")
    repeat(rows) { r ->
        sb.append("<tr>")
        header.indices.forEach { c -> sb.append("<td>").append(prefill.getOrNull(r)?.getOrNull(c)?.let(::e) ?: "<br>").append("</td>") }
        sb.append("</tr>")
    }
    return sb.append("</tbody></table>").toString()
}
/** Two-column key/value table; arguments alternate key, value, key, value… */
private fun kv(vararg kvs: String): String =
    "<table><tbody>" + kvs.toList().chunked(2).joinToString("") { (k, v) -> "<tr><th style=\"width:32%\">${e(k)}</th><td>${if (v.isBlank()) "<br>" else e(v)}</td></tr>" } + "</tbody></table>"
private fun checklist(vararg items: String) = "<ul class=\"checklist\">" + items.joinToString("") { "<li>${e(it)}</li>" } + "</ul>"
private fun signer(c: Ctx) = listOf(c.name, c.title, c.org).filter { it.isNotBlank() }.joinToString("<br>") { e(it) }

object Templates {
    val all: List<DocTemplate> = listOf(
        DocTemplate("blank", "Blank document", "Start from scratch", "📄", false) { "<p><br></p>" },
        DocTemplate("memo", "Memo", "Internal memorandum", "📨", true) { c ->
            "<h1 class=\"title\">MEMORANDUM</h1>" + kv("To", "", "From", c.name + if (c.title.isNotBlank()) ", ${c.title}" else "", "CC", "", "Date", c.date, "Subject", "") +
                "<hr><p><b>Purpose.</b> State the reason for this memo in one or two sentences.</p><p><b>Background.</b> </p><p><b>Action requested.</b> </p><p>Thank you,</p><p>${signer(c)}</p>"
        },
        DocTemplate("meeting", "Meeting minutes", "Agenda, decisions and action items", "🗓️", true) { c ->
            "<h1 class=\"title\">Meeting Minutes</h1>" + kv("Meeting", "", "Date", c.date, "Time", "", "Location / link", "", "Facilitator", c.name, "Note taker", "") +
                "<h2>Attendees</h2><ul><li><br></li></ul><h2>Agenda</h2><ol><li>Review of previous action items</li><li><br></li><li>Open discussion</li></ol>" +
                "<h2>Discussion</h2><p><br></p><h2>Decisions</h2><ul><li><br></li></ul><h2>Action items</h2>" + table(listOf("Action", "Owner", "Due", "Status"), 4) +
                "<h2>Next meeting</h2><p><br></p>"
        },
        DocTemplate("workorder", "Work order", "Request, assignment and completion sign-off", "🛠️", true) { c ->
            "<h1 class=\"title\">Work Order</h1>" + kv("WO #", "", "Date requested", c.date, "Requested by", "", "Building / area / room", "", "Priority", "☐ Emergency  ☐ Urgent  ☐ Routine  ☐ Scheduled",
                "Category", "☐ HVAC  ☐ Electrical  ☐ Plumbing  ☐ Carpentry  ☐ Grounds  ☐ Custodial  ☐ Other", "Asset tag", "") +
                "<h2>Description of work</h2><p><br></p><h2>Assignment</h2>" + kv("Assigned to", "", "Target completion", "", "Estimated hours", "") +
                "<h2>Materials &amp; parts</h2>" + table(listOf("Item", "Qty", "Unit cost", "Total"), 4) +
                "<h2>Labor</h2>" + table(listOf("Technician", "Date", "Hours", "Notes"), 3) +
                "<h2>Completion</h2>" + checklist("Work completed and area cleaned", "Requester notified", "Asset history updated") + kv("Completed by", "", "Date", "", "Supervisor sign-off", "")
        },
        DocTemplate("incident", "Incident report", "Safety / property incident with corrective actions", "⚠️", true) { c ->
            "<h1 class=\"title\">Incident Report</h1>" + kv("Report #", "", "Date & time of incident", "", "Location", "", "Reported by", c.name, "Date reported", c.date,
                "Type", "☐ Injury  ☐ Near miss  ☐ Property damage  ☐ Environmental  ☐ Security  ☐ Other") +
                "<h2>People involved</h2>" + table(listOf("Name", "Role", "Contact", "Injury?"), 3) +
                "<h2>Description of incident</h2><p><br></p><h2>Immediate actions taken</h2><p><br></p><h2>Root cause analysis</h2><p><b>Why 1:</b> </p><p><b>Why 2:</b> </p><p><b>Why 3:</b> </p>" +
                "<h2>Corrective &amp; preventive actions</h2>" + table(listOf("Action", "Owner", "Due date", "Status"), 3) +
                "<h2>Photos &amp; attachments</h2><p><br></p><h2>Sign-off</h2>" + kv("Prepared by", c.name, "Reviewed by", "", "Date", "")
        },
        DocTemplate("sop", "Standard operating procedure", "Step-by-step SOP with revision history", "📘", true) { c ->
            "<h1 class=\"title\">Standard Operating Procedure</h1>" + kv("SOP title", "", "SOP #", "", "Revision", "1.0", "Effective date", c.date, "Owner", c.name + if (c.title.isNotBlank()) ", ${c.title}" else "", "Approved by", "") +
                "<h2>1. Purpose</h2><p><br></p><h2>2. Scope</h2><p><br></p><h2>3. Responsibilities</h2><ul><li><br></li></ul>" +
                "<h2>4. Safety &amp; PPE</h2>" + checklist("Lockout/tagout applied where required", "Required PPE worn", "Area secured / signage posted") +
                "<h2>5. Procedure</h2><ol><li><br></li><li><br></li><li><br></li></ol><h2>6. References</h2><ul><li><br></li></ul>" +
                "<h2>7. Revision history</h2>" + table(listOf("Rev", "Date", "Description", "Author"), 2, listOf(listOf("1.0", c.date, "Initial release", c.name)))
        },
        DocTemplate("inspection", "Facility inspection", "Building walkthrough checklist with deficiencies", "🔍", true) { c ->
            "<h1 class=\"title\">Facility Inspection</h1>" + kv("Building / site", "", "Date", c.date, "Inspector", c.name, "Weather", "") +
                "<h2>Life safety</h2>" + checklist("Exits & egress paths clear", "Exit signs & emergency lighting functional", "Fire extinguishers tagged & accessible", "Fire alarm panel normal", "Sprinkler heads unobstructed (18 in clearance)") +
                "<h2>HVAC</h2>" + checklist("Filters within service date", "No unusual noise / vibration", "Space temperatures within setpoint", "Mechanical rooms clean & secured") +
                "<h2>Electrical</h2>" + checklist("Panels labeled & 36 in clearance", "No exposed wiring", "Lighting functional") +
                "<h2>Plumbing</h2>" + checklist("No leaks or water damage", "Restrooms operational", "Backflow devices tested") +
                "<h2>Building envelope &amp; roof</h2>" + checklist("Roof drains clear", "Doors & hardware operate", "No visible cracks / water intrusion") +
                "<h2>Grounds &amp; parking</h2>" + checklist("Walkways free of trip hazards", "Parking lot lighting", "Signage & ADA access") +
                "<h2>Deficiencies</h2>" + table(listOf("Location", "Deficiency", "Priority", "WO #"), 4) + "<h2>Notes</h2><p><br></p>"
        },
        DocTemplate("pmlog", "Preventive maintenance log", "Equipment PM record", "⚙️", true) { _ ->
            "<h1 class=\"title\">Preventive Maintenance Log</h1>" + kv("Equipment", "", "Asset tag", "", "Location", "", "Manufacturer / model", "", "Serial #", "", "PM frequency", "☐ Weekly  ☐ Monthly  ☐ Quarterly  ☐ Semi-annual  ☐ Annual") +
                "<h2>Tasks</h2>" + checklist("Inspect belts & bearings", "Lubricate per manufacturer", "Check / replace filters", "Verify controls & safeties", "Record readings") +
                "<h2>Service history</h2>" + table(listOf("Date", "Work performed", "Technician", "Readings / notes"), 6)
        },
        DocTemplate("brief", "Project brief", "Scope, budget, timeline and risks", "📐", true) { c ->
            "<h1 class=\"title\">Project Brief</h1>" + kv("Project", "", "Sponsor", "", "Project manager", c.name, "Date", c.date) +
                "<h2>Overview</h2><p><br></p><h2>Objectives</h2><ol><li><br></li></ol><h2>Scope</h2><p><b>In scope:</b> </p><p><b>Out of scope:</b> </p>" +
                "<h2>Budget</h2>" + table(listOf("Line item", "Estimate", "Notes"), 4) +
                "<h2>Timeline &amp; milestones</h2>" + table(listOf("Milestone", "Target date", "Owner"), 4) +
                "<h2>Stakeholders</h2><ul><li><br></li></ul><h2>Risks</h2>" + table(listOf("Risk", "Likelihood", "Impact", "Mitigation"), 3) + "<h2>Success criteria</h2><ul><li><br></li></ul>"
        },
        DocTemplate("vendor", "Vendor evaluation", "Weighted scoring for bids & contractors", "🤝", true) { c ->
            "<h1 class=\"title\">Vendor Evaluation</h1>" + kv("Vendor", "", "Service / bid", "", "Contact", "", "Evaluated by", c.name, "Date", c.date) +
                "<h2>Scoring</h2>" + table(listOf("Criteria", "Weight %", "Score (1–5)", "Weighted"), 6, listOf(
                    listOf("Price / value", "30"), listOf("Experience & references", "20"), listOf("Safety record (EMR)", "15"),
                    listOf("Schedule / responsiveness", "15"), listOf("Insurance & licensing", "10"), listOf("Warranty / support", "10"))) +
                "<h2>Strengths</h2><ul><li><br></li></ul><h2>Concerns</h2><ul><li><br></li></ul><h2>Recommendation</h2><p><br></p>"
        },
        DocTemplate("budget", "Budget request", "Capital / operating request with justification", "💰", true) { c ->
            "<h1 class=\"title\">Budget Request</h1>" + kv("Request title", "", "Department", c.org, "Requested by", c.name, "Fiscal year", "${Time.today().year + 1}", "Type", "☐ Capital  ☐ Operating  ☐ Emergency") +
                "<h2>Summary</h2><p><br></p><h2>Justification</h2><p><br></p><h2>Cost breakdown</h2>" + table(listOf("Item", "Qty", "Unit cost", "Total"), 5) +
                "<h2>Return on investment / risk of not funding</h2><p><br></p><h2>Approvals</h2>" + table(listOf("Name", "Title", "Decision", "Date"), 3)
        },
        DocTemplate("weekly", "Weekly status report", "Highlights, KPIs and blockers", "📊", true) { c ->
            "<h1 class=\"title\">Weekly Status Report</h1><p>${e(c.name)} · Week of ${e(c.date)}</p><h2>Highlights</h2><ul><li><br></li></ul>" +
                "<h2>Completed</h2><ul><li><br></li></ul><h2>In progress</h2><ul><li><br></li></ul><h2>Blockers / risks</h2><ul><li><br></li></ul>" +
                "<h2>Key metrics</h2>" + table(listOf("Metric", "Target", "Actual", "Trend"), 4, listOf(listOf("Open work orders"), listOf("PM completion %"), listOf("Avg. response time"), listOf("Budget variance"))) +
                "<h2>Next week</h2><ul><li><br></li></ul>"
        },
        DocTemplate("shift", "Shift handover", "Pass-down log between shifts", "🔄", true) { c ->
            "<h1 class=\"title\">Shift Handover</h1>" + kv("Date", c.date, "Outgoing", c.name, "Incoming", "", "Shift", "☐ Day  ☐ Swing  ☐ Night") +
                "<h2>Open issues</h2>" + table(listOf("Issue", "Location", "Status", "Follow-up"), 4) + "<h2>Equipment out of service</h2><ul><li><br></li></ul><h2>Notes</h2><p><br></p>"
        },
        DocTemplate("letter", "Letter", "Formal business or personal letter", "✉️", false) { c ->
            "<p>${signer(c)}</p><p>${e(c.date)}</p><p>Recipient name<br>Address</p><p>Dear ,</p><p><br></p><p>Sincerely,</p><p><br></p><p>${e(c.name)}</p>"
        },
        DocTemplate("notes", "Notes", "Quick structured notes", "🗒️", false) { c ->
            "<h1>Notes</h1><p><i>${e(c.date)}</i></p><ul><li><br></li></ul>"
        },
        DocTemplate("cornell", "Cornell notes", "Class notes: cues, notes, summary", "🎓", false) { c ->
            "<h1 class=\"title\">Lecture Notes</h1>" + kv("Course", "", "Date", c.date, "Topic", "") +
                "<table><tbody><tr><th style=\"width:30%\">Cues / questions</th><th>Notes</th></tr><tr><td><br><br><br><br></td><td><br></td></tr></tbody></table>" +
                "<h2>Summary</h2><p><br></p><h2>Review questions</h2><ol><li><br></li></ol>"
        },
        DocTemplate("checklist", "Checklist", "Tap boxes to check off", "✅", false) { _ ->
            "<h1>Checklist</h1><ul class=\"checklist\"><li><br></li></ul>"
        },
        DocTemplate("travel", "Travel plan", "Itinerary, bookings and packing", "✈️", false) { _ ->
            "<h1 class=\"title\">Travel Plan</h1>" + kv("Destination", "", "Dates", "", "Purpose", "") + "<h2>Itinerary</h2>" + table(listOf("Date", "Time", "Activity", "Confirmation"), 5) +
                "<h2>Bookings</h2><ul><li>Flight: </li><li>Hotel: </li><li>Car: </li></ul><h2>Packing</h2>" + checklist("ID / passport", "Chargers", "Medications", "Laptop & badge")
        },
    )

    fun byId(id: String?): DocTemplate = all.firstOrNull { it.id == id } ?: all.first()
}
