package com.blacklab.app.car

import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.EnergyProfile
import androidx.car.app.hardware.info.Mileage
import androidx.car.app.hardware.info.Model
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import com.blacklab.app.vehicle.Telemetry
import com.blacklab.app.vehicle.VehicleLink

/**
 * What the car reports through Android Auto for as long as it's connected: make/model/year, fuel
 * types, fuel level, range and the low-fuel warning, speed and the odometer. Which values a head unit
 * shares is up to the manufacturer; anything missing is simply not reported. Everything goes into the
 * vehicle snapshot (odometer readings also update the Garage).
 */
class CarData(private val carContext: CarContext) {
    private val executor = ContextCompat.getMainExecutor(carContext)
    private val info get() = runCatching { carContext.getCarService(CarHardwareManager::class.java).carInfo }.getOrNull()
    private val src = Telemetry.Source.ANDROID_AUTO
    private fun <T> CarValue<T>.ok(): T? = takeIf { it.status == CarValue.STATUS_SUCCESS }?.value
    private fun put(vararg m: Telemetry.Metric?) = VehicleLink.put(carContext, m.filterNotNull())

    private val energy = OnCarDataAvailableListener<EnergyLevel> { e ->
        put(Telemetry.num("fuel_pct", e.fuelPercent.ok()?.toDouble(), src),
            Telemetry.num("range", e.rangeRemainingMeters.ok()?.let { Telemetry.metersToMi(it.toDouble()) }, src),
            Telemetry.bool("low_fuel", e.energyIsLow.ok(), src),
            Telemetry.num("hev_soc", e.batteryPercent.ok()?.toDouble(), src))
    }

    private val mileage = OnCarDataAvailableListener<Mileage> { m ->
        put(Telemetry.num("odometer", m.odometerMeters.ok()?.let { Telemetry.metersToMi(it.toDouble()) }, src))
    }

    private var lastSpeedAt = 0L
    private val speed = OnCarDataAvailableListener<Speed> { s ->
        val now = System.currentTimeMillis()
        if (now - lastSpeedAt < 900) return@OnCarDataAvailableListener
        lastSpeedAt = now
        put(Telemetry.num("speed", (s.rawSpeedMetersPerSecond.ok() ?: s.displaySpeedMetersPerSecond.ok())?.let { Telemetry.mpsToMph(it.toDouble()) }, src))
    }

    fun start() {
        val i = info ?: return
        runCatching {
            i.fetchModel(executor) { model: Model ->
                val label = listOfNotNull(model.year.ok()?.toString(), model.manufacturer.ok(), model.name.ok()).joinToString(" ")
                put(Telemetry.text("model", label, src))
            }
        }
        runCatching {
            i.fetchEnergyProfile(executor) { p: EnergyProfile ->
                val names = p.fuelTypes.ok().orEmpty().mapNotNull { t ->
                    when (t) {
                        EnergyProfile.FUEL_TYPE_UNLEADED -> "Gasoline"; EnergyProfile.FUEL_TYPE_ELECTRIC -> "Electric"; EnergyProfile.FUEL_TYPE_DIESEL_1, EnergyProfile.FUEL_TYPE_DIESEL_2 -> "Diesel"
                        EnergyProfile.FUEL_TYPE_E85 -> "E85"; EnergyProfile.FUEL_TYPE_BIODIESEL -> "Biodiesel"; else -> null
                    }
                }.distinct()
                put(Telemetry.text("fuel_type", names.joinToString(" + "), src))
            }
        }
        runCatching { i.addEnergyLevelListener(executor, energy) }
        runCatching { i.addMileageListener(executor, mileage) }
        runCatching { i.addSpeedListener(executor, speed) }
    }

    fun stop() {
        val i = info ?: return
        runCatching { i.removeEnergyLevelListener(energy) }
        runCatching { i.removeMileageListener(mileage) }
        runCatching { i.removeSpeedListener(speed) }
    }
}
