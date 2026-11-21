package com.blacklab.app.office

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import com.blacklab.app.core.Files
import com.blacklab.app.core.Time
import java.io.File
import java.time.LocalDateTime
import java.util.Locale

/**
 * Hand-offs to Microsoft's own apps (PowerPoint, Excel, Word, Teams, Outlook, OneDrive) and Teams meetings found in
 * the phone's calendars. A work Microsoft 365 account managed by IT stays inside Microsoft's apps: BlackLab never
 * signs in to it, it only opens the file or the meeting link in the app that is already signed in.
 */
object MicrosoftApps {
    enum class App(val label: String, val pkg: String, val exts: Set<String>) {
        POWERPOINT("PowerPoint", "com.microsoft.office.powerpoint", setOf("pptx", "ppt", "ppsx", "pps", "potx", "odp")),
        EXCEL("Excel", "com.microsoft.office.excel", setOf("xlsx", "xls", "xlsm", "xltx", "csv", "ods")),
        WORD("Word", "com.microsoft.office.word", setOf("docx", "doc", "dotx", "rtf", "odt")),
        TEAMS("Teams", "com.microsoft.teams", emptySet()),
        OUTLOOK("Outlook", "com.microsoft.office.outlook", emptySet()),
        ONEDRIVE("OneDrive", "com.microsoft.skydrive", emptySet()),
        M365("Microsoft 365", "com.microsoft.office.officehubrow", setOf("pptx", "xlsx", "docx", "pdf")),
    }

    val OFFICE_EXTS = App.POWERPOINT.exts + App.EXCEL.exts + App.WORD.exts

    fun appFor(f: File): App? = f.extension.lowercase(Locale.US).let { e -> listOf(App.POWERPOINT, App.EXCEL, App.WORD).firstOrNull { e in it.exts } }

    fun installed(ctx: Context, app: App): Boolean = runCatching { ctx.packageManager.getPackageInfo(app.pkg, 0); true }.getOrDefault(false)

    fun mime(f: File): String = when (f.extension.lowercase(Locale.US)) {
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "ppsx" -> "application/vnd.openxmlformats-officedocument.presentationml.slideshow"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "xlsm" -> "application/vnd.ms-excel.sheet.macroEnabled.12"
        "ppt" -> "application/vnd.ms-powerpoint"
        "xls" -> "application/vnd.ms-excel"
        "doc" -> "application/msword"
        else -> Files.mime(f)
    }

    /**
     * Opens [f] in the Microsoft app for its type, with write access so Save in PowerPoint/Excel/Word writes back to
     * the same file. Falls back to Microsoft 365, then to Android's chooser. Returns what happened, for a toast.
     */
    fun openInMicrosoft(ctx: Context, f: File): String {
        val uri = Files.uri(ctx, f)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        val targets = listOfNotNull(appFor(f), App.M365).filter { installed(ctx, it) }
        for (t in targets) {
            for (action in listOf(Intent.ACTION_EDIT, Intent.ACTION_VIEW)) {
                val i = Intent(action).setDataAndType(uri, mime(f)).setPackage(t.pkg).addFlags(flags)
                if (runCatching { ctx.startActivity(i) }.isSuccess) return "Opened in ${t.label}"
            }
        }
        val any = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(f)).addFlags(flags)
        return if (runCatching { ctx.startActivity(Intent.createChooser(any, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess)
            "Pick an app — install ${appFor(f)?.label ?: "Microsoft 365"} for full Office editing"
        else "No app on this phone opens .${f.extension}"
    }

    /** Opens a Microsoft app, or its Play Store page when it isn't installed. */
    fun launch(ctx: Context, app: App) {
        val i = ctx.packageManager.getLaunchIntentForPackage(app.pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i != null && runCatching { ctx.startActivity(i) }.isSuccess) return
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${app.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(store) }.isFailure)
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${app.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ------------------------------------------------------------------ Teams meetings

    private val TEAMS_LINK = Regex("""https://teams\.(?:microsoft|live)\.com/(?:l/meetup-join|meet)/[^\s"'<>)\]]+""", RegexOption.IGNORE_CASE)

    /** The Teams join link in a calendar event's location or description, if it has one. Pure, so it is unit tested. */
    fun teamsLink(vararg texts: String?): String? = texts.firstNotNullOfOrNull { t -> t?.let { TEAMS_LINK.find(it)?.value?.trimEnd('.', ',', ';', '>') } }

    data class Meeting(val eventId: Long, val title: String, val start: LocalDateTime, val end: LocalDateTime, val calendar: String, val link: String)

    /** Teams meetings in the phone's calendars (Outlook, Google…) from [from] for [days] days. Needs READ_CALENDAR. */
    fun meetings(ctx: Context, from: LocalDateTime = LocalDateTime.now().minusMinutes(30), days: Long = 7): List<Meeting> {
        if (!com.blacklab.app.ui.schedule.DeviceCalendar.hasPermission(ctx)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, Time.millis(from)); ContentUris.appendId(it, Time.millis(from.plusDays(days)))
        }.build()
        val out = ArrayList<Meeting>()
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(
                CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            ), null, null, CalendarContract.Instances.BEGIN)?.use { c ->
                while (c.moveToNext()) {
                    val link = teamsLink(c.getString(4), c.getString(5)) ?: continue
                    out += Meeting(c.getLong(0), c.getString(1) ?: "Teams meeting", Time.toLdt(c.getLong(2)), Time.toLdt(c.getLong(3)), c.getString(6) ?: "", link)
                }
            }
        }
        return out.distinctBy { it.eventId to it.start }
    }

    /** Joins a Teams meeting: the Teams app takes the https link when installed, otherwise the browser does. */
    fun join(ctx: Context, link: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (installed(ctx, App.TEAMS)) i.setPackage(App.TEAMS.pkg)
        if (runCatching { ctx.startActivity(i) }.isFailure) ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Starts a Teams chat with [email] (work colleagues are found by their work address). */
    fun chat(ctx: Context, email: String, message: String = "") {
        val link = "https://teams.microsoft.com/l/chat/0/0?users=${Uri.encode(email)}" + if (message.isNotBlank()) "&message=${Uri.encode(message)}" else ""
        join(ctx, link)
    }
}
