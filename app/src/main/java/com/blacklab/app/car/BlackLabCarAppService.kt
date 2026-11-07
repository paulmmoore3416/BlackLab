package com.blacklab.app.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.blacklab.app.app
import kotlinx.coroutines.launch

/**
 * BlackLab on Android Auto, designed around the 2023 Hyundai Santa Fe head units (8-inch
 * wireless and 10.25-inch wide-screen navigation displays): short titles, big tap targets, no
 * text entry while driving, everything reachable by voice through the car's microphone.
 *
 * Release builds only accept Android Auto / Android Automotive hosts signed by Google
 * (the Car App Library's allow-list); debug builds accept any host so the Desktop Head Unit works.
 */
class BlackLabCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
    }

    override fun onCreateSession(): Session = BlackLabCarSession()
}

class BlackLabCarSession : Session() {
    private var carData: CarData? = null

    init {
        // Read the car (fuel, range, speed, odometer) for the whole Android Auto session, not only the Garage screen
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onCreate(owner: androidx.lifecycle.LifecycleOwner) {
                val c = carContext
                val missing = listOf("com.google.android.gms.permission.CAR_FUEL", "com.google.android.gms.permission.CAR_MILEAGE", "com.google.android.gms.permission.CAR_SPEED")
                    .filter { androidx.core.content.ContextCompat.checkSelfPermission(c, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                if (missing.isNotEmpty()) runCatching { c.requestPermissions(missing) { _, _ -> carData?.stop(); carData?.start() } }
                carData = CarData(c).also { it.start() }
                com.blacklab.app.vehicle.VehicleLink.onAndroidAuto(c, true)
            }
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                carData?.stop(); carData = null
                com.blacklab.app.vehicle.VehicleLink.onAndroidAuto(carContext, false)
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val app = carContext.app
        // Speak the daily briefing on connect, once a day, if asked to
        if (app.settings.current.carBriefOnStart) {
            val prefs = carContext.getSharedPreferences("blacklab_car", 0)
            val today = java.time.LocalDate.now().toEpochDay()
            if (prefs.getLong("brief_day", 0) != today) {
                prefs.edit().putLong("brief_day", today).apply()
                app.appScope.launch { app.speaker.speak(com.blacklab.app.assistant.Briefing.build(app, app.settings.current.asstPersona)) }
            }
        }
        val home = CarHomeScreen(carContext)
        routeScreen(intent)?.let { target -> carContext.getCarService(androidx.car.app.ScreenManager::class.java).push(home); return target }
        return home
    }

    /** Tapping a reminder on the car display opens the matching screen. */
    override fun onNewIntent(intent: Intent) {
        routeScreen(intent)?.let { carContext.getCarService(androidx.car.app.ScreenManager::class.java).push(it) }
    }

    private fun routeScreen(intent: Intent): Screen? = when (intent.getStringExtra("car_route")) {
        "tasks" -> CarTasksScreen(carContext)
        "agenda" -> CarAgendaScreen(carContext)
        else -> null
    }
}
