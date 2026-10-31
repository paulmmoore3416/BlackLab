package com.blacklab.app.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val onboarded: Boolean = false,
    val activeProfile: Profile = Profile.PERSONAL,
    val defaultProfile: Profile = Profile.PERSONAL,
    val userName: String = "",
    val jobTitle: String = "",
    val personalName: String = "Personal",
    val professionalName: String = "Professional",
    val organization: String = "",
    // appearance
    val themeMode: String = "DARK", // DARK, LIGHT, SYSTEM
    val dynamicColor: Boolean = false,
    // security
    val appLock: Boolean = false,
    val lockProfessional: Boolean = false,
    val lockTimeoutSec: Int = 60,
    // calendar
    val weekStart: Int = 7, // java.time.DayOfWeek value; 7 = Sunday
    val workStartMinute: Int = 7 * 60,
    val workEndMinute: Int = 17 * 60,
    val defaultEventMinutes: Int = 60,
    val defaultEventReminder: Int = 15,
    val showDeviceCalendars: Boolean = false,
    val hiddenDeviceCalendars: Set<String> = emptySet(),
    val showTasksInCalendar: Boolean = true,
    val showClassesInCalendar: Boolean = true,
    val showOtherProfileInCalendar: Boolean = false,
    val calendarView: String = "MONTH",
    // notifications
    val agendaNotify: Boolean = true,
    val agendaMinute: Int = 7 * 60,
    val journalReminder: Boolean = false,
    val journalMinute: Int = 21 * 60,
    val classReminderMinutes: Int = 15,
    val defaultTaskReminderMinute: Int = 9 * 60,
    // recorder
    val recFormat: String = "M4A", // M4A, WAV
    val recSampleRate: Int = 48000,
    val recChannels: Int = 1,
    val recBitrate: Int = 192_000,
    val recSource: String = "MIC",
    val recNoiseSuppress: Boolean = false,
    val recAgc: Boolean = false,
    val recEchoCancel: Boolean = false,
    val recGainDb: Int = 0,
    val recLiveTranscript: Boolean = false,
    // camera
    val camGrid: Boolean = true,
    val camShutterSound: Boolean = true,
    val camUltraHdr: Boolean = true,
    val camMaxResolution: Boolean = false,
    val camVideoQuality: String = "UHD",
    val camStabilization: Boolean = true,
    val camHdrVideo: Boolean = false,
    val camVideoFps: Int = 30,
    val camAspect: String = "4:3",
    val camGeotag: Boolean = true,
    val camInfoTags: Boolean = true, // address, compass heading, altitude, author & copyright in EXIF
    val camInfoStamp: Boolean = false, // burn date / place / GPS into the picture
    val camFilter: String = "NONE",
    val camVintageDate: Boolean = true,
    val camPanoQuality: String = "HIGH", // HIGH, ULTRA
    // dictation
    val dictVoiceCommands: Boolean = true,
    val dictHandsFree: Boolean = false,
    val dictGeotag: Boolean = true,
    val dictVocabulary: String = "",
    val dictSource: String = "MIC",
    val dictNoiseSuppress: Boolean = true,
    val dictAgc: Boolean = true,
    val dictTimestamps: Boolean = true,
    val dictOnDevice: Boolean = true,
    // education
    val targetCredits: Double = 120.0,
    val pomodoroWork: Int = 25,
    val pomodoroBreak: Int = 5,
    val pomodoroLongBreak: Int = 15,
    val weeklyStudyGoalMin: Int = 300,
    // tasks
    val taskSort: String = "DUE",
    val showCompletedTasks: Boolean = true,
    // documents
    val docSort: String = "UPDATED",
    val defaultPageSize: String = "LETTER",
    // storage
    val autoBackup: Boolean = true,
    val lastBackupDay: Long = 0,
    // assistant
    val asstName: String = "BlackLab",
    val asstPersona: String = "LAIDBACK", // LAIDBACK, PROFESSIONAL, CONCISE
    val asstSpeak: Boolean = true,
    val asstVoiceEngine: String = "AUTO", // AUTO, ELEVENLABS, DEVICE
    val asstElevenVoice: String = "CwhRBWXzGAHq8TQ4Fs17",
    val asstElevenModel: String = "eleven_flash_v2_5",
    val asstElevenStability: Int = 40,
    val asstElevenSimilarity: Int = 75,
    val asstElevenStyle: Int = 35,
    val asstElevenBudget: Int = 9000,
    val asstSpeechRate: Int = 100,
    val asstPitch: Int = 88,
    val asstAutoListen: Boolean = false,
    val asstConfirm: Boolean = true,
    val asstUseNano: Boolean = true,
    val asstNanoTemp: Int = 30,
    val asstNanoTokens: Int = 320,
    val asstOfflineOnly: Boolean = false,
    val asstIndexDocs: Boolean = true,
    val asstIndexJournal: Boolean = true,
    val asstIndexTranscripts: Boolean = true,
    val asstIndexTasks: Boolean = true,
    val asstHistory: Int = 200,
    val asstGmailMax: Int = 8,
    val asstDriveFolder: String = "BlackLab",
    val asstWorkOrderList: String = "Work Orders",
    val asstBriefOnOpen: Boolean = false,
    // desktop link & cloud sync
    val linkEnabled: Boolean = false,
    val linkLan: Boolean = false,
    val linkPort: Int = 8765,
    val cloudSync: Boolean = false,
    val cloudSyncMinutes: Int = 15,
    val googleAccount: String = "",
)

object Keys {
    const val ONBOARDED = "onboarded"
    const val ACTIVE_PROFILE = "active_profile"
    const val DEFAULT_PROFILE = "default_profile"
    const val USER_NAME = "user_name"
    const val JOB_TITLE = "job_title"
    const val PERSONAL_NAME = "personal_name"
    const val PROFESSIONAL_NAME = "professional_name"
    const val ORGANIZATION = "organization"
    const val THEME_MODE = "theme_mode"
    const val DYNAMIC_COLOR = "dynamic_color"
    const val APP_LOCK = "app_lock"
    const val LOCK_PROFESSIONAL = "lock_professional"
    const val LOCK_TIMEOUT = "lock_timeout"
    const val WEEK_START = "week_start"
    const val WORK_START = "work_start"
    const val WORK_END = "work_end"
    const val DEFAULT_EVENT_MIN = "default_event_min"
    const val DEFAULT_EVENT_REMINDER = "default_event_reminder"
    const val SHOW_DEVICE_CALS = "show_device_cals"
    const val HIDDEN_DEVICE_CALS = "hidden_device_cals"
    const val SHOW_TASKS_IN_CAL = "show_tasks_in_cal"
    const val SHOW_CLASSES_IN_CAL = "show_classes_in_cal"
    const val SHOW_OTHER_PROFILE_CAL = "show_other_profile_cal"
    const val CALENDAR_VIEW = "calendar_view"
    const val AGENDA_NOTIFY = "agenda_notify"
    const val AGENDA_MINUTE = "agenda_minute"
    const val JOURNAL_REMINDER = "journal_reminder"
    const val JOURNAL_MINUTE = "journal_minute"
    const val CLASS_REMINDER = "class_reminder"
    const val TASK_REMINDER_MINUTE = "task_reminder_minute"
    const val REC_FORMAT = "rec_format"
    const val REC_RATE = "rec_rate"
    const val REC_CHANNELS = "rec_channels"
    const val REC_BITRATE = "rec_bitrate"
    const val REC_SOURCE = "rec_source"
    const val REC_NS = "rec_ns"
    const val REC_AGC = "rec_agc"
    const val REC_AEC = "rec_aec"
    const val REC_GAIN = "rec_gain"
    const val REC_TRANSCRIPT = "rec_transcript"
    const val CAM_GRID = "cam_grid"
    const val CAM_SHUTTER = "cam_shutter"
    const val CAM_ULTRA_HDR = "cam_ultra_hdr"
    const val CAM_MAX_RES = "cam_max_res"
    const val CAM_VIDEO_QUALITY = "cam_video_quality"
    const val CAM_STABILIZATION = "cam_stabilization"
    const val CAM_HDR_VIDEO = "cam_hdr_video"
    const val CAM_FPS = "cam_fps"
    const val CAM_ASPECT = "cam_aspect"
    const val CAM_GEOTAG = "cam_geotag"
    const val CAM_INFO_TAGS = "cam_info_tags"
    const val CAM_INFO_STAMP = "cam_info_stamp"
    const val CAM_FILTER = "cam_filter"
    const val CAM_VINTAGE_DATE = "cam_vintage_date"
    const val CAM_PANO_QUALITY = "cam_pano_quality"
    const val DICT_COMMANDS = "dict_commands"
    const val DICT_HANDS_FREE = "dict_hands_free"
    const val DICT_GEOTAG = "dict_geotag"
    const val DICT_VOCAB = "dict_vocab"
    const val DICT_SOURCE = "dict_source"
    const val DICT_NS = "dict_ns"
    const val DICT_AGC = "dict_agc"
    const val DICT_TIMESTAMPS = "dict_timestamps"
    const val DICT_ON_DEVICE = "dict_on_device"
    const val TARGET_CREDITS = "target_credits"
    const val POMO_WORK = "pomo_work"
    const val POMO_BREAK = "pomo_break"
    const val POMO_LONG = "pomo_long"
    const val STUDY_GOAL = "study_goal"
    const val TASK_SORT = "task_sort"
    const val SHOW_COMPLETED = "show_completed"
    const val DOC_SORT = "doc_sort"
    const val PAGE_SIZE = "page_size"
    const val AUTO_BACKUP = "auto_backup"
    const val LAST_BACKUP_DAY = "last_backup_day"
    const val ASST_NAME = "asst_name"
    const val ASST_PERSONA = "asst_persona"
    const val ASST_SPEAK = "asst_speak"
    const val ASST_VOICE_ENGINE = "asst_voice_engine"
    const val ASST_ELEVEN_VOICE = "asst_eleven_voice"
    const val ASST_ELEVEN_MODEL = "asst_eleven_model"
    const val ASST_ELEVEN_STABILITY = "asst_eleven_stability"
    const val ASST_ELEVEN_SIMILARITY = "asst_eleven_similarity"
    const val ASST_ELEVEN_STYLE = "asst_eleven_style"
    const val ASST_ELEVEN_BUDGET = "asst_eleven_budget"
    const val ASST_RATE = "asst_rate"
    const val ASST_PITCH = "asst_pitch"
    const val ASST_AUTO_LISTEN = "asst_auto_listen"
    const val ASST_CONFIRM = "asst_confirm"
    const val ASST_USE_NANO = "asst_use_nano"
    const val ASST_NANO_TEMP = "asst_nano_temp"
    const val ASST_NANO_TOKENS = "asst_nano_tokens"
    const val ASST_OFFLINE_ONLY = "asst_offline_only"
    const val ASST_INDEX_DOCS = "asst_index_docs"
    const val ASST_INDEX_JOURNAL = "asst_index_journal"
    const val ASST_INDEX_TRANSCRIPTS = "asst_index_transcripts"
    const val ASST_INDEX_TASKS = "asst_index_tasks"
    const val ASST_HISTORY = "asst_history"
    const val ASST_GMAIL_MAX = "asst_gmail_max"
    const val ASST_DRIVE_FOLDER = "asst_drive_folder"
    const val ASST_WO_LIST = "asst_wo_list"
    const val ASST_BRIEF_ON_OPEN = "asst_brief_on_open"
    const val LINK_ENABLED = "link_enabled"
    const val LINK_LAN = "link_lan"
    const val LINK_PORT = "link_port"
    const val CLOUD_SYNC = "cloud_sync"
    const val CLOUD_SYNC_MIN = "cloud_sync_min"
    const val GOOGLE_ACCOUNT = "google_account"
}

class SettingsRepository(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("blacklab_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val current: Settings get() = _state.value

    fun update(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    fun setProfile(profile: Profile) = update { putString(Keys.ACTIVE_PROFILE, profile.name) }

    fun profileName(profile: Profile): String = when (profile) {
        Profile.PERSONAL -> current.personalName.ifBlank { "Personal" }
        Profile.PROFESSIONAL -> current.professionalName.ifBlank { "Professional" }
    }

    private fun read(): Settings = with(prefs) {
        val d = Settings()
        Settings(
            onboarded = getBoolean(Keys.ONBOARDED, d.onboarded),
            activeProfile = Profile.from(getString(Keys.ACTIVE_PROFILE, null)),
            defaultProfile = Profile.from(getString(Keys.DEFAULT_PROFILE, null)),
            userName = getString(Keys.USER_NAME, d.userName) ?: "",
            jobTitle = getString(Keys.JOB_TITLE, d.jobTitle) ?: "",
            personalName = getString(Keys.PERSONAL_NAME, d.personalName) ?: d.personalName,
            professionalName = getString(Keys.PROFESSIONAL_NAME, d.professionalName) ?: d.professionalName,
            organization = getString(Keys.ORGANIZATION, d.organization) ?: "",
            themeMode = getString(Keys.THEME_MODE, d.themeMode) ?: d.themeMode,
            dynamicColor = getBoolean(Keys.DYNAMIC_COLOR, d.dynamicColor),
            appLock = getBoolean(Keys.APP_LOCK, d.appLock),
            lockProfessional = getBoolean(Keys.LOCK_PROFESSIONAL, d.lockProfessional),
            lockTimeoutSec = getInt(Keys.LOCK_TIMEOUT, d.lockTimeoutSec),
            weekStart = getInt(Keys.WEEK_START, d.weekStart),
            workStartMinute = getInt(Keys.WORK_START, d.workStartMinute),
            workEndMinute = getInt(Keys.WORK_END, d.workEndMinute),
            defaultEventMinutes = getInt(Keys.DEFAULT_EVENT_MIN, d.defaultEventMinutes),
            defaultEventReminder = getInt(Keys.DEFAULT_EVENT_REMINDER, d.defaultEventReminder),
            showDeviceCalendars = getBoolean(Keys.SHOW_DEVICE_CALS, d.showDeviceCalendars),
            hiddenDeviceCalendars = getStringSet(Keys.HIDDEN_DEVICE_CALS, emptySet())?.toSet() ?: emptySet(),
            showTasksInCalendar = getBoolean(Keys.SHOW_TASKS_IN_CAL, d.showTasksInCalendar),
            showClassesInCalendar = getBoolean(Keys.SHOW_CLASSES_IN_CAL, d.showClassesInCalendar),
            showOtherProfileInCalendar = getBoolean(Keys.SHOW_OTHER_PROFILE_CAL, d.showOtherProfileInCalendar),
            calendarView = getString(Keys.CALENDAR_VIEW, d.calendarView) ?: d.calendarView,
            agendaNotify = getBoolean(Keys.AGENDA_NOTIFY, d.agendaNotify),
            agendaMinute = getInt(Keys.AGENDA_MINUTE, d.agendaMinute),
            journalReminder = getBoolean(Keys.JOURNAL_REMINDER, d.journalReminder),
            journalMinute = getInt(Keys.JOURNAL_MINUTE, d.journalMinute),
            classReminderMinutes = getInt(Keys.CLASS_REMINDER, d.classReminderMinutes),
            defaultTaskReminderMinute = getInt(Keys.TASK_REMINDER_MINUTE, d.defaultTaskReminderMinute),
            recFormat = getString(Keys.REC_FORMAT, d.recFormat) ?: d.recFormat,
            recSampleRate = getInt(Keys.REC_RATE, d.recSampleRate),
            recChannels = getInt(Keys.REC_CHANNELS, d.recChannels),
            recBitrate = getInt(Keys.REC_BITRATE, d.recBitrate),
            recSource = getString(Keys.REC_SOURCE, d.recSource) ?: d.recSource,
            recNoiseSuppress = getBoolean(Keys.REC_NS, d.recNoiseSuppress),
            recAgc = getBoolean(Keys.REC_AGC, d.recAgc),
            recEchoCancel = getBoolean(Keys.REC_AEC, d.recEchoCancel),
            recGainDb = getInt(Keys.REC_GAIN, d.recGainDb),
            recLiveTranscript = getBoolean(Keys.REC_TRANSCRIPT, d.recLiveTranscript),
            camGrid = getBoolean(Keys.CAM_GRID, d.camGrid),
            camShutterSound = getBoolean(Keys.CAM_SHUTTER, d.camShutterSound),
            camUltraHdr = getBoolean(Keys.CAM_ULTRA_HDR, d.camUltraHdr),
            camMaxResolution = getBoolean(Keys.CAM_MAX_RES, d.camMaxResolution),
            camVideoQuality = getString(Keys.CAM_VIDEO_QUALITY, d.camVideoQuality) ?: d.camVideoQuality,
            camStabilization = getBoolean(Keys.CAM_STABILIZATION, d.camStabilization),
            camHdrVideo = getBoolean(Keys.CAM_HDR_VIDEO, d.camHdrVideo),
            camVideoFps = getInt(Keys.CAM_FPS, d.camVideoFps),
            camAspect = getString(Keys.CAM_ASPECT, d.camAspect) ?: d.camAspect,
            camGeotag = getBoolean(Keys.CAM_GEOTAG, d.camGeotag),
            camInfoTags = getBoolean(Keys.CAM_INFO_TAGS, d.camInfoTags),
            camInfoStamp = getBoolean(Keys.CAM_INFO_STAMP, d.camInfoStamp),
            camFilter = getString(Keys.CAM_FILTER, d.camFilter) ?: d.camFilter,
            camVintageDate = getBoolean(Keys.CAM_VINTAGE_DATE, d.camVintageDate),
            camPanoQuality = getString(Keys.CAM_PANO_QUALITY, d.camPanoQuality) ?: d.camPanoQuality,
            dictVoiceCommands = getBoolean(Keys.DICT_COMMANDS, d.dictVoiceCommands),
            dictHandsFree = getBoolean(Keys.DICT_HANDS_FREE, d.dictHandsFree),
            dictGeotag = getBoolean(Keys.DICT_GEOTAG, d.dictGeotag),
            dictVocabulary = getString(Keys.DICT_VOCAB, d.dictVocabulary) ?: "",
            dictSource = getString(Keys.DICT_SOURCE, d.dictSource) ?: d.dictSource,
            dictNoiseSuppress = getBoolean(Keys.DICT_NS, d.dictNoiseSuppress),
            dictAgc = getBoolean(Keys.DICT_AGC, d.dictAgc),
            dictTimestamps = getBoolean(Keys.DICT_TIMESTAMPS, d.dictTimestamps),
            dictOnDevice = getBoolean(Keys.DICT_ON_DEVICE, d.dictOnDevice),
            targetCredits = getFloat(Keys.TARGET_CREDITS, d.targetCredits.toFloat()).toDouble(),
            pomodoroWork = getInt(Keys.POMO_WORK, d.pomodoroWork),
            pomodoroBreak = getInt(Keys.POMO_BREAK, d.pomodoroBreak),
            pomodoroLongBreak = getInt(Keys.POMO_LONG, d.pomodoroLongBreak),
            weeklyStudyGoalMin = getInt(Keys.STUDY_GOAL, d.weeklyStudyGoalMin),
            taskSort = getString(Keys.TASK_SORT, d.taskSort) ?: d.taskSort,
            showCompletedTasks = getBoolean(Keys.SHOW_COMPLETED, d.showCompletedTasks),
            docSort = getString(Keys.DOC_SORT, d.docSort) ?: d.docSort,
            defaultPageSize = getString(Keys.PAGE_SIZE, d.defaultPageSize) ?: d.defaultPageSize,
            autoBackup = getBoolean(Keys.AUTO_BACKUP, d.autoBackup),
            lastBackupDay = getLong(Keys.LAST_BACKUP_DAY, d.lastBackupDay),
            asstName = getString(Keys.ASST_NAME, d.asstName)?.ifBlank { null } ?: d.asstName,
            asstPersona = getString(Keys.ASST_PERSONA, d.asstPersona) ?: d.asstPersona,
            asstSpeak = getBoolean(Keys.ASST_SPEAK, d.asstSpeak),
            asstVoiceEngine = getString(Keys.ASST_VOICE_ENGINE, d.asstVoiceEngine) ?: d.asstVoiceEngine,
            asstElevenVoice = getString(Keys.ASST_ELEVEN_VOICE, d.asstElevenVoice)?.ifBlank { null } ?: d.asstElevenVoice,
            asstElevenModel = getString(Keys.ASST_ELEVEN_MODEL, d.asstElevenModel) ?: d.asstElevenModel,
            asstElevenStability = getInt(Keys.ASST_ELEVEN_STABILITY, d.asstElevenStability),
            asstElevenSimilarity = getInt(Keys.ASST_ELEVEN_SIMILARITY, d.asstElevenSimilarity),
            asstElevenStyle = getInt(Keys.ASST_ELEVEN_STYLE, d.asstElevenStyle),
            asstElevenBudget = getInt(Keys.ASST_ELEVEN_BUDGET, d.asstElevenBudget),
            asstSpeechRate = getInt(Keys.ASST_RATE, d.asstSpeechRate),
            asstPitch = getInt(Keys.ASST_PITCH, d.asstPitch),
            asstAutoListen = getBoolean(Keys.ASST_AUTO_LISTEN, d.asstAutoListen),
            asstConfirm = getBoolean(Keys.ASST_CONFIRM, d.asstConfirm),
            asstUseNano = getBoolean(Keys.ASST_USE_NANO, d.asstUseNano),
            asstNanoTemp = getInt(Keys.ASST_NANO_TEMP, d.asstNanoTemp),
            asstNanoTokens = getInt(Keys.ASST_NANO_TOKENS, d.asstNanoTokens),
            asstOfflineOnly = getBoolean(Keys.ASST_OFFLINE_ONLY, d.asstOfflineOnly),
            asstIndexDocs = getBoolean(Keys.ASST_INDEX_DOCS, d.asstIndexDocs),
            asstIndexJournal = getBoolean(Keys.ASST_INDEX_JOURNAL, d.asstIndexJournal),
            asstIndexTranscripts = getBoolean(Keys.ASST_INDEX_TRANSCRIPTS, d.asstIndexTranscripts),
            asstIndexTasks = getBoolean(Keys.ASST_INDEX_TASKS, d.asstIndexTasks),
            asstHistory = getInt(Keys.ASST_HISTORY, d.asstHistory),
            asstGmailMax = getInt(Keys.ASST_GMAIL_MAX, d.asstGmailMax),
            asstDriveFolder = getString(Keys.ASST_DRIVE_FOLDER, d.asstDriveFolder) ?: d.asstDriveFolder,
            asstWorkOrderList = getString(Keys.ASST_WO_LIST, d.asstWorkOrderList) ?: d.asstWorkOrderList,
            asstBriefOnOpen = getBoolean(Keys.ASST_BRIEF_ON_OPEN, d.asstBriefOnOpen),
            linkEnabled = getBoolean(Keys.LINK_ENABLED, d.linkEnabled),
            linkLan = getBoolean(Keys.LINK_LAN, d.linkLan),
            linkPort = getInt(Keys.LINK_PORT, d.linkPort),
            cloudSync = getBoolean(Keys.CLOUD_SYNC, d.cloudSync),
            cloudSyncMinutes = getInt(Keys.CLOUD_SYNC_MIN, d.cloudSyncMinutes),
            googleAccount = getString(Keys.GOOGLE_ACCOUNT, d.googleAccount) ?: "",
        )
    }
}
