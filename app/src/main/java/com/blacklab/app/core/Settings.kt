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
        )
    }
}
