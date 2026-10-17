package com.blacklab.app

import android.app.Application
import android.content.Context
import com.blacklab.app.core.BlackLabStorage
import com.blacklab.app.core.SettingsRepository
import com.blacklab.app.data.BlackLabDatabase
import com.blacklab.app.data.ContentStore
import com.blacklab.app.reminders.Notifications
import com.blacklab.app.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BlackLabApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.storage.ensureStructure()
        container.requestReminderSync()
        container.appScope.launch { container.backups.autoBackupIfDue() }
    }
}

class AppContainer(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = SettingsRepository(context)
    val storage = BlackLabStorage(context)
    val db: BlackLabDatabase = BlackLabDatabase.build(context)
    val content = ContentStore(storage)
    val backups = com.blacklab.app.core.BackupManager(context, this)

    private var syncJob: Job? = null

    /** Debounced: recompute the next reminder alarm after any data change. */
    fun requestReminderSync() {
        syncJob?.cancel()
        syncJob = appScope.launch {
            delay(400)
            ReminderScheduler.reschedule(context)
        }
    }

    /** Run a write off the UI thread in the app-wide scope so it survives navigation. */
    fun io(block: suspend () -> Unit) {
        appScope.launch { block() }
    }
}

val Context.app: AppContainer get() = (applicationContext as BlackLabApp).container
