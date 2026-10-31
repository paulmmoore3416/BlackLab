package com.blacklab.app.core

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Describes every user-facing setting: its storage type, label, help text, allowed values and
 * default. It is the single source of truth for the settings search on the phone and for the
 * BlackLab Desktop UI, which renders its settings pages from [toJson] and writes changes back
 * through [apply].
 */
object SettingsSchema {

    enum class Store { BOOL, INT, FLOAT, STRING, SET }

    data class Def(
        val key: String,
        val store: Store,
        val label: String,
        val help: String = "",
        /** bool, int, float, text, choice, time (minute of day), weekday, set, percent */
        val ui: String = "",
        val options: List<Pair<String, String>> = emptyList(),
        val min: Double? = null,
        val max: Double? = null,
        val step: Double? = null,
        val unit: String = "",
        val default: Any?,
        val advanced: Boolean = false,
        val readOnly: Boolean = false,
    )

    data class Section(val id: String, val title: String, val icon: String, val description: String, val items: List<Def>)

    private val d = Settings()

    private fun bool(key: String, label: String, help: String, default: Boolean, advanced: Boolean = false) =
        Def(key, Store.BOOL, label, help, "bool", default = default, advanced = advanced)

    private fun int(key: String, label: String, help: String, default: Int, min: Int, max: Int, step: Int = 1, unit: String = "", advanced: Boolean = false) =
        Def(key, Store.INT, label, help, "int", min = min.toDouble(), max = max.toDouble(), step = step.toDouble(), unit = unit, default = default, advanced = advanced)

    private fun pct(key: String, label: String, help: String, default: Int, min: Int = 0, max: Int = 100, advanced: Boolean = false) =
        Def(key, Store.INT, label, help, "percent", min = min.toDouble(), max = max.toDouble(), step = 1.0, unit = "%", default = default, advanced = advanced)

    private fun text(key: String, label: String, help: String, default: String, advanced: Boolean = false) =
        Def(key, Store.STRING, label, help, "text", default = default, advanced = advanced)

    private fun choice(key: String, label: String, help: String, default: String, vararg options: Pair<String, String>, advanced: Boolean = false) =
        Def(key, Store.STRING, label, help, "choice", options.toList(), default = default, advanced = advanced)

    private fun intChoice(key: String, label: String, help: String, default: Int, vararg options: Pair<Int, String>, advanced: Boolean = false) =
        Def(key, Store.INT, label, help, "choice", options.map { it.first.toString() to it.second }, default = default, advanced = advanced)

    private fun time(key: String, label: String, help: String, default: Int) =
        Def(key, Store.INT, label, help, "time", min = 0.0, max = 1439.0, step = 5.0, default = default)

    val sections: List<Section> = listOf(
        Section("profile", "Profile & accounts", "person", "Who you are and how the Personal / Professional accounts are named.", listOf(
            text(Keys.USER_NAME, "Your name", "Used on documents, templates, photo copyright and greetings.", d.userName),
            text(Keys.JOB_TITLE, "Job title", "Shown under your name and on professional templates.", d.jobTitle),
            text(Keys.ORGANIZATION, "Organization", "Company or institution printed on professional templates.", d.organization),
            text(Keys.PERSONAL_NAME, "Personal account name", "Label for the Personal account.", d.personalName),
            text(Keys.PROFESSIONAL_NAME, "Professional account name", "Label for the Professional account.", d.professionalName),
            choice(Keys.DEFAULT_PROFILE, "Open in account", "Account BlackLab opens in after a cold start.", d.defaultProfile.name, "PERSONAL" to "Personal", "PROFESSIONAL" to "Professional"),
            choice(Keys.ACTIVE_PROFILE, "Active account", "Switches the account currently shown on the phone.", d.activeProfile.name, "PERSONAL" to "Personal", "PROFESSIONAL" to "Professional"),
        )),
        Section("appearance", "Appearance", "palette", "Theme and colors.", listOf(
            choice(Keys.THEME_MODE, "Theme", "Dark, light or follow the system.", d.themeMode, "DARK" to "Dark", "LIGHT" to "Light", "SYSTEM" to "System"),
            bool(Keys.DYNAMIC_COLOR, "Material You colors", "Use wallpaper colors instead of the account accents.", d.dynamicColor),
        )),
        Section("security", "Security & privacy", "lock", "App lock and account protection.", listOf(
            bool(Keys.APP_LOCK, "Lock BlackLab", "Require fingerprint, face or screen lock to open the app.", d.appLock),
            bool(Keys.LOCK_PROFESSIONAL, "Lock Professional account", "Require unlock when switching to the Professional account.", d.lockProfessional),
            intChoice(Keys.LOCK_TIMEOUT, "Lock after leaving", "Grace period before the app locks again.", d.lockTimeoutSec, 0 to "Immediately", 30 to "30 seconds", 60 to "1 minute", 300 to "5 minutes", 900 to "15 minutes"),
        )),
        Section("assistant", "Assistant", "assistant", "Your personal assistant: personality, brain and behavior.", listOf(
            text(Keys.ASST_NAME, "Assistant name", "What the assistant calls itself.", d.asstName),
            choice(Keys.ASST_PERSONA, "Personality", "How replies are worded.", d.asstPersona, "LAIDBACK" to "Laid-back & funny", "PROFESSIONAL" to "Professional", "CONCISE" to "Just the facts"),
            bool(Keys.ASST_USE_NANO, "On-device AI (Gemini Nano)", "Use the Pixel's built-in Gemini Nano for open questions. Runs fully offline.", d.asstUseNano),
            pct(Keys.ASST_NANO_TEMP, "Creativity", "Gemini Nano temperature. Lower is more predictable.", d.asstNanoTemp, advanced = true),
            int(Keys.ASST_NANO_TOKENS, "Max reply length", "Upper bound on Gemini Nano output tokens.", d.asstNanoTokens, 64, 1024, 32, "tokens", advanced = true),
            bool(Keys.ASST_OFFLINE_ONLY, "Offline only", "Never use the network: no ElevenLabs, Gmail, Drive or docs search.", d.asstOfflineOnly),
            bool(Keys.ASST_CONFIRM, "Confirm before acting", "Ask before sending email, deleting or changing settings.", d.asstConfirm),
            int(Keys.ASST_HISTORY, "Conversation history", "Messages kept on the phone.", d.asstHistory, 20, 2000, 20, "messages", advanced = true),
            bool(Keys.ASST_BRIEF_ON_OPEN, "Brief me when I open it", "Speak the daily briefing the first time you open the assistant each day.", d.asstBriefOnOpen),
            text(Keys.ASST_WO_LIST, "Work-order list", "To-Do list that Snap-to-Work-Order and meeting actions file into.", d.asstWorkOrderList, advanced = true),
        )),
        Section("voice", "Assistant voice", "voice", "Speech output and listening.", listOf(
            bool(Keys.ASST_SPEAK, "Speak replies", "Read replies aloud.", d.asstSpeak),
            choice(Keys.ASST_VOICE_ENGINE, "Voice engine", "Auto uses ElevenLabs when online and within budget, otherwise the on-device voice.", d.asstVoiceEngine,
                "AUTO" to "Auto", "ELEVENLABS" to "ElevenLabs only", "DEVICE" to "On-device only"),
            choice(Keys.ASST_ELEVEN_VOICE, "ElevenLabs voice", "Laid-back, gravelly male voices work best for the default personality.", d.asstElevenVoice,
                "CwhRBWXzGAHq8TQ4Fs17" to "Roger · laid-back, casual, resonant",
                "N2lVS1w4EtoT3dr4eOWO" to "Callum · husky",
                "iP95p4xoKVk53GoZ742B" to "Chris · charming, down-to-earth",
                "bIHbv24MWmeRgasZH58o" to "Will · relaxed optimist",
                "nPczCjzI2devNBz1zQrb" to "Brian · deep, comforting",
                "cjVigY5qzO86Huf0OWal" to "Eric · smooth",
                "EXAVITQu4vr4xnSDxMaL" to "Sarah · warm, confident",
                "JBFqnCBsd6RMkjVDRZzb" to "George · British storyteller"),
            choice(Keys.ASST_ELEVEN_MODEL, "ElevenLabs model", "Flash is fastest and uses half the credits.", d.asstElevenModel,
                "eleven_flash_v2_5" to "Flash v2.5 (fast, cheap)", "eleven_turbo_v2_5" to "Turbo v2.5", "eleven_multilingual_v2" to "Multilingual v2 (richest)", advanced = true),
            pct(Keys.ASST_ELEVEN_STABILITY, "Stability", "Lower is more expressive, higher is more even.", d.asstElevenStability, advanced = true),
            pct(Keys.ASST_ELEVEN_SIMILARITY, "Clarity / similarity", "How closely to match the base voice.", d.asstElevenSimilarity, advanced = true),
            pct(Keys.ASST_ELEVEN_STYLE, "Style exaggeration", "Adds delivery and attitude.", d.asstElevenStyle, advanced = true),
            int(Keys.ASST_ELEVEN_BUDGET, "Monthly ElevenLabs budget", "Characters per month before falling back to the on-device voice (free tier is 10,000).", d.asstElevenBudget, 0, 500_000, 500, "chars"),
            pct(Keys.ASST_RATE, "On-device speech rate", "Speed of the offline voice.", d.asstSpeechRate, 50, 200),
            pct(Keys.ASST_PITCH, "On-device pitch", "Lower sounds deeper.", d.asstPitch, 50, 150),
            bool(Keys.ASST_AUTO_LISTEN, "Keep listening", "Start listening again after a spoken reply, for back-and-forth conversation.", d.asstAutoListen),
        )),
        Section("knowledge", "Assistant knowledge", "search", "What “Ask my files” indexes on the phone. All indexing is local.", listOf(
            bool(Keys.ASST_INDEX_DOCS, "Documents", "Index document text.", d.asstIndexDocs),
            bool(Keys.ASST_INDEX_JOURNAL, "Journal", "Index journal entries.", d.asstIndexJournal),
            bool(Keys.ASST_INDEX_TRANSCRIPTS, "Recordings & dictation", "Index transcripts.", d.asstIndexTranscripts),
            bool(Keys.ASST_INDEX_TASKS, "Tasks, events & courses", "Index titles and notes.", d.asstIndexTasks),
        )),
        Section("google", "Google services", "cloud", "Gmail, Drive and Keep through your Google account.", listOf(
            Def(Keys.GOOGLE_ACCOUNT, Store.STRING, "Signed-in account", "Sign in on the phone under Settings › Assistant.", "text", default = "", readOnly = true),
            int(Keys.ASST_GMAIL_MAX, "Emails per summary", "How many messages the assistant reads for an inbox summary.", d.asstGmailMax, 1, 50),
            text(Keys.ASST_DRIVE_FOLDER, "Drive folder", "Folder that “upload to Drive” files go into.", d.asstDriveFolder),
        )),
        Section("sync", "Desktop link & sync", "sync", "How the phone talks to BlackLab Desktop.", listOf(
            bool(Keys.LINK_ENABLED, "Desktop link", "Run the local link server so BlackLab Desktop can sync over USB or Wi-Fi.", d.linkEnabled),
            bool(Keys.LINK_LAN, "Allow Wi-Fi connections", "Listen on the local network, not just USB. Still requires the pairing code.", d.linkLan),
            int(Keys.LINK_PORT, "Link port", "TCP port of the link server.", d.linkPort, 1024, 65535, advanced = true),
            bool(Keys.CLOUD_SYNC, "Cloud sync (Google Drive)", "Mirror settings and a data snapshot to your Drive's hidden app folder so the web UI works from anywhere.", d.cloudSync),
            intChoice(Keys.CLOUD_SYNC_MIN, "Cloud sync interval", "Background sync period.", d.cloudSyncMinutes, 15 to "15 minutes", 30 to "30 minutes", 60 to "1 hour", 180 to "3 hours"),
        )),
        Section("calendar", "Schedule", "calendar", "Calendar views, working hours and overlays.", listOf(
            Def(Keys.WEEK_START, Store.INT, "Week starts on", "First day of week in calendar grids.", "weekday",
                (1..7).map { it.toString() to java.time.DayOfWeek.of(it).name.lowercase().replaceFirstChar { c -> c.uppercase() } }, default = d.weekStart),
            choice(Keys.CALENDAR_VIEW, "Default view", "View the Schedule opens in.", d.calendarView, "MONTH" to "Month", "WEEK" to "Week", "THREE" to "3 days", "DAY" to "Day", "AGENDA" to "Schedule"),
            time(Keys.WORK_START, "Working hours start", "Used by Find a time and Plan my day.", d.workStartMinute),
            time(Keys.WORK_END, "Working hours end", "Used by Find a time and Plan my day.", d.workEndMinute),
            int(Keys.DEFAULT_EVENT_MIN, "Default event length", "Length of new events.", d.defaultEventMinutes, 5, 480, 5, "min"),
            int(Keys.DEFAULT_EVENT_REMINDER, "Default event reminder", "Minutes before new events.", d.defaultEventReminder, 0, 10080, 5, "min"),
            bool(Keys.SHOW_DEVICE_CALS, "Show phone calendars", "Overlay Google / Outlook calendars from the phone.", d.showDeviceCalendars),
            bool(Keys.SHOW_TASKS_IN_CAL, "Show dated tasks", "Show tasks with due dates in the calendar.", d.showTasksInCalendar),
            bool(Keys.SHOW_CLASSES_IN_CAL, "Show classes & deadlines", "Show class meetings, assignments and exams.", d.showClassesInCalendar),
            bool(Keys.SHOW_OTHER_PROFILE_CAL, "Overlay other account", "Show the other account's events too.", d.showOtherProfileInCalendar),
            Def(Keys.HIDDEN_DEVICE_CALS, Store.SET, "Hidden phone calendars", "Phone calendar IDs that are hidden.", "set", default = emptyList<String>(), advanced = true),
        )),
        Section("notifications", "Notifications", "bell", "Daily summaries and default reminders.", listOf(
            bool(Keys.AGENDA_NOTIFY, "Morning agenda", "Daily summary notification.", d.agendaNotify),
            time(Keys.AGENDA_MINUTE, "Agenda time", "When the morning agenda arrives.", d.agendaMinute),
            bool(Keys.JOURNAL_REMINDER, "Journal reminder", "Nudge if you haven't written today.", d.journalReminder),
            time(Keys.JOURNAL_MINUTE, "Journal reminder time", "When the journal nudge arrives.", d.journalMinute),
            int(Keys.CLASS_REMINDER, "Class reminder", "Minutes before each class meeting.", d.classReminderMinutes, 0, 240, 5, "min"),
            time(Keys.TASK_REMINDER_MINUTE, "Default task reminder time", "Time used when a task has a date but no time.", d.defaultTaskReminderMinute),
        )),
        Section("tasks", "To-Do", "tasks", "Task list behavior.", listOf(
            choice(Keys.TASK_SORT, "Sort tasks by", "Default ordering in lists.", d.taskSort, "DUE" to "Due date", "PRIORITY" to "Priority", "IMPORTANT" to "Importance", "CREATED" to "Creation date", "ALPHA" to "Alphabetically"),
            bool(Keys.SHOW_COMPLETED, "Show completed tasks", "Keep completed tasks visible at the bottom of lists.", d.showCompletedTasks),
        )),
        Section("documents", "Documents", "doc", "Document editor defaults.", listOf(
            choice(Keys.DOC_SORT, "Sort documents by", "Default ordering.", d.docSort, "UPDATED" to "Last modified", "CREATED" to "Date created", "TITLE" to "Title", "WORDS" to "Length"),
            choice(Keys.PAGE_SIZE, "Default page size", "Page setup for new documents.", d.defaultPageSize, "LETTER" to "Letter", "A4" to "A4", "LEGAL" to "Legal", "A5" to "A5"),
        )),
        Section("camera", "Camera", "camera", "Capture quality, video and photo metadata.", listOf(
            bool(Keys.CAM_GRID, "Grid lines", "Rule-of-thirds overlay.", d.camGrid),
            bool(Keys.CAM_SHUTTER, "Shutter sound", "Play the shutter sound.", d.camShutterSound),
            bool(Keys.CAM_ULTRA_HDR, "Ultra HDR photos", "Save JPEG_R with an HDR gain map.", d.camUltraHdr),
            bool(Keys.CAM_MAX_RES, "Maximum resolution", "Capture at full sensor resolution (slower, larger files).", d.camMaxResolution),
            choice(Keys.CAM_ASPECT, "Photo aspect ratio", "Frame shape.", d.camAspect, "4:3" to "4:3", "16:9" to "16:9"),
            choice(Keys.CAM_FILTER, "Default look", "Look applied to new photos.", d.camFilter,
                "NONE" to "Original", "SEVENTIES" to "1970s", "VIVID" to "Vivid", "NOIR" to "Noir", "SILVER" to "Silver", "GOLDEN" to "Golden hour",
                "ARCTIC" to "Arctic", "CINEMATIC" to "Cinematic", "MATTE" to "Matte fade", "CROSS" to "Cross-process"),
            bool(Keys.CAM_VINTAGE_DATE, "1970s date stamp", "Burn the orange date stamp into 1970s-look photos.", d.camVintageDate),
            choice(Keys.CAM_VIDEO_QUALITY, "Video resolution", "Recording resolution.", d.camVideoQuality, "UHD" to "4K", "FHD" to "1080p", "HD" to "720p"),
            intChoice(Keys.CAM_FPS, "Video frame rate", "Frames per second.", d.camVideoFps, 24 to "24 fps", 30 to "30 fps", 60 to "60 fps"),
            bool(Keys.CAM_STABILIZATION, "Stabilization", "Preview and video stabilization.", d.camStabilization),
            bool(Keys.CAM_HDR_VIDEO, "10-bit HDR video", "Record HLG HDR video when supported.", d.camHdrVideo),
            choice(Keys.CAM_PANO_QUALITY, "Panorama quality", "Ultra keeps more resolution but takes longer.", d.camPanoQuality, "HIGH" to "High", "ULTRA" to "Ultra"),
            bool(Keys.CAM_GEOTAG, "Geotag photos", "Write GPS location to EXIF.", d.camGeotag),
            bool(Keys.CAM_INFO_TAGS, "Detailed photo info", "Address, compass heading, altitude, author and copyright in EXIF.", d.camInfoTags),
            bool(Keys.CAM_INFO_STAMP, "Info stamp", "Burn date, place and GPS into the picture.", d.camInfoStamp),
        )),
        Section("recorder", "Recorder", "mic", "Audio format, source and processing.", listOf(
            choice(Keys.REC_FORMAT, "Format", "M4A (AAC) or lossless WAV.", d.recFormat, "M4A" to "M4A (AAC)", "WAV" to "WAV (lossless)"),
            intChoice(Keys.REC_RATE, "Sample rate", "Higher captures more detail.", d.recSampleRate, 16000 to "16 kHz", 22050 to "22.05 kHz", 32000 to "32 kHz", 44100 to "44.1 kHz", 48000 to "48 kHz", 96000 to "96 kHz"),
            intChoice(Keys.REC_CHANNELS, "Channels", "Mono or stereo.", d.recChannels, 1 to "Mono", 2 to "Stereo"),
            intChoice(Keys.REC_BITRATE, "AAC bitrate", "Only for M4A.", d.recBitrate, 64000 to "64 kbps", 96000 to "96 kbps", 128000 to "128 kbps", 192000 to "192 kbps", 256000 to "256 kbps", 320000 to "320 kbps"),
            choice(Keys.REC_SOURCE, "Microphone source", "Processing applied by Android.", d.recSource, *MIC_SOURCES),
            bool(Keys.REC_NS, "Noise suppression", "Reduce steady background noise.", d.recNoiseSuppress),
            bool(Keys.REC_AGC, "Automatic gain", "Level out loud and quiet speakers.", d.recAgc),
            bool(Keys.REC_AEC, "Echo cancellation", "Remove speaker echo.", d.recEchoCancel),
            int(Keys.REC_GAIN, "Digital gain", "Boost quiet recordings.", d.recGainDb, 0, 24, 1, "dB"),
            bool(Keys.REC_TRANSCRIPT, "Live transcription", "Transcribe on-device while recording (beta).", d.recLiveTranscript),
        )),
        Section("dictation", "Dictation", "dictation", "Transcription, voice commands and audio.", listOf(
            bool(Keys.DICT_COMMANDS, "Voice commands", "“pause recording”, “new paragraph”, “mark that”…", d.dictVoiceCommands),
            bool(Keys.DICT_HANDS_FREE, "Hands-free standby", "Wait for “start recording”.", d.dictHandsFree),
            bool(Keys.DICT_ON_DEVICE, "On-device recognition", "Keep speech on the phone.", d.dictOnDevice),
            bool(Keys.DICT_TIMESTAMPS, "Timestamps in transcript", "Prefix paragraphs with elapsed time.", d.dictTimestamps),
            bool(Keys.DICT_GEOTAG, "Save location", "Store GPS and address with each session.", d.dictGeotag),
            text(Keys.DICT_VOCAB, "Custom vocabulary", "Comma-separated names and jargon to bias recognition.", d.dictVocabulary),
            choice(Keys.DICT_SOURCE, "Microphone source", "Processing applied by Android.", d.dictSource, *MIC_SOURCES),
            bool(Keys.DICT_NS, "Noise suppression", "Reduce background noise.", d.dictNoiseSuppress),
            bool(Keys.DICT_AGC, "Automatic gain", "Level out speakers.", d.dictAgc),
        )),
        Section("education", "Education", "school", "Degree progress and study timer.", listOf(
            Def(Keys.TARGET_CREDITS, Store.FLOAT, "Degree credits", "Credits needed to graduate.", "float", min = 1.0, max = 400.0, step = 0.5, unit = "credits", default = d.targetCredits),
            int(Keys.POMO_WORK, "Focus length", "Pomodoro focus minutes.", d.pomodoroWork, 5, 120, 1, "min"),
            int(Keys.POMO_BREAK, "Short break", "Minutes.", d.pomodoroBreak, 1, 30, 1, "min"),
            int(Keys.POMO_LONG, "Long break", "Minutes.", d.pomodoroLongBreak, 5, 60, 1, "min"),
            int(Keys.STUDY_GOAL, "Weekly study goal", "Minutes per week.", d.weeklyStudyGoalMin, 0, 3000, 15, "min"),
        )),
        Section("storage", "Storage & backup", "storage", "Backups of the database and settings.", listOf(
            bool(Keys.AUTO_BACKUP, "Automatic daily backup", "Keeps the 14 most recent in BlackLab/Backups.", d.autoBackup),
        )),
    )

    private val byKey: Map<String, Def> by lazy { sections.flatMap { it.items }.associateBy { it.key } }

    fun def(key: String): Def? = byKey[key]

    fun read(prefs: SharedPreferences, def: Def): Any? = when (def.store) {
        Store.BOOL -> prefs.getBoolean(def.key, def.default as Boolean)
        Store.INT -> prefs.getInt(def.key, (def.default as Number).toInt())
        Store.FLOAT -> prefs.getFloat(def.key, (def.default as Number).toFloat()).toDouble()
        Store.STRING -> prefs.getString(def.key, def.default as String?) ?: def.default
        Store.SET -> prefs.getStringSet(def.key, emptySet())?.toList() ?: emptyList<String>()
    }

    /** Current values for every setting, keyed by preference key. */
    fun values(prefs: SharedPreferences): JSONObject = JSONObject().apply {
        sections.flatMap { it.items }.forEach { def ->
            when (val v = read(prefs, def)) {
                is List<*> -> put(def.key, JSONArray(v))
                else -> put(def.key, v)
            }
        }
    }

    /**
     * Validates and writes a batch of changes. Returns the keys that were applied; unknown,
     * read-only or out-of-range values are skipped.
     */
    fun apply(repo: SettingsRepository, changes: JSONObject): List<String> {
        val applied = ArrayList<String>()
        repo.update {
            changes.keys().forEach { key ->
                val def = byKey[key] ?: return@forEach
                if (def.readOnly) return@forEach
                val raw = changes.opt(key)
                val ok = runCatching {
                    when (def.store) {
                        Store.BOOL -> putBoolean(key, raw as? Boolean ?: raw.toString().toBooleanStrict())
                        Store.INT -> {
                            val v = (raw as? Number)?.toInt() ?: raw.toString().toDouble().toInt()
                            if (def.ui == "choice" && def.options.none { it.first == v.toString() }) error("not an option")
                            if (def.min != null && v < def.min || def.max != null && v > def.max) error("out of range")
                            putInt(key, v)
                        }
                        Store.FLOAT -> {
                            val v = (raw as? Number)?.toDouble() ?: raw.toString().toDouble()
                            if (def.min != null && v < def.min || def.max != null && v > def.max) error("out of range")
                            putFloat(key, v.toFloat())
                        }
                        Store.STRING -> {
                            val v = raw.toString()
                            if (def.ui == "choice" && def.options.none { it.first == v }) error("not an option")
                            putString(key, v.take(2000))
                        }
                        Store.SET -> {
                            val arr = raw as? JSONArray ?: JSONArray()
                            putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet())
                        }
                    }
                }.isSuccess
                if (ok) applied += key
            }
        }
        return applied
    }

    fun toJson(): JSONArray = JSONArray().apply {
        sections.forEach { s ->
            put(JSONObject().apply {
                put("id", s.id); put("title", s.title); put("icon", s.icon); put("description", s.description)
                put("items", JSONArray().apply {
                    s.items.forEach { def ->
                        put(JSONObject().apply {
                            put("key", def.key); put("store", def.store.name.lowercase()); put("label", def.label); put("help", def.help)
                            put("ui", def.ui); put("advanced", def.advanced); put("readOnly", def.readOnly); put("unit", def.unit)
                            def.min?.let { put("min", it) }; def.max?.let { put("max", it) }; def.step?.let { put("step", it) }
                            put("default", when (val v = def.default) { is List<*> -> JSONArray(v); null -> JSONObject.NULL; else -> v })
                            if (def.options.isNotEmpty()) put("options", JSONArray().apply {
                                def.options.forEach { (value, label) -> put(JSONObject().put("value", value).put("label", label)) }
                            })
                        })
                    }
                })
            })
        }
    }
}

private val MIC_SOURCES = arrayOf(
    "MIC" to "Standard microphone",
    "CAMCORDER" to "Camcorder (directional / stereo)",
    "VOICE_RECOGNITION" to "Voice (minimal processing)",
    "VOICE_COMMUNICATION" to "Meeting / call (echo & noise cancel)",
    "UNPROCESSED" to "Raw / unprocessed",
    "VOICE_PERFORMANCE" to "Music / performance",
)
