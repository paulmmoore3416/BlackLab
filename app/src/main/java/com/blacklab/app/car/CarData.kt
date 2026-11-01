package com.blacklab.app.car

import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.Mileage
import androidx.car.app.hardware.info.Model
import androidx.core.content.ContextCompat
import com.blacklab.app.vehicle.Garage
import kotlin.math.roundToInt

/**
 * What the car itself reports through Android Auto: make/model/year, fuel level and range, and the
 * odometer. Which values a head unit shares is up to the manufacturer — anything missing is simply
 * not shown, and the Garage falls back to what you've logged. Odometer readings update the Garage.
 */
class CarData(private val carContext: CarContext, private val onChange: () -> Unit) {
    var detected: String? = null; private set
    var fuelLine: String? = null; private set

    private val executor = ContextCompat.getMainExecutor(carContext)
    private val info get() = runCatching { carContext.getCarService(CarHardwareManager::class.java).carInfo }.getOrNull()

    private val energy = OnCarDataAvailableListener<EnergyLevel> { e ->
        val pct = e.fuelPercent.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value
        val range = e.rangeRemainingMeters.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value
        val line = listOfNotNull(pct?.let { "Fuel ${it.roundToInt()}%" }, range?.let { "${(it / 1609.344f).roundToInt()} mi range" }).joinToString(" · ").ifBlank { null }
        if (line != fuelLine) { fuelLine = line; onChange() }
    }

    private val mileage = OnCarDataAvailableListener<Mileage> { m ->
        val meters = m.odometerMeters.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value ?: return@OnCarDataAvailableListener
        val miles = (meters / 1609.344f).roundToInt()
        if (miles > Garage.state.value.vehicle.odometer) { Garage.setOdometer(carContext, miles); onChange() }
    }

    fun start() {
        val i = info ?: return
        runCatching {
            i.fetchModel(executor) { model: Model ->
                val make = model.manufacturer.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value
                val name = model.name.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value
                val year = model.year.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value
                val label = listOfNotNull(year?.toString(), make, name).joinToString(" ").ifBlank { null }
                if (label != detected) { detected = label; onChange() }
            }
        }
        runCatching { i.addEnergyLevelListener(executor, energy) }
        runCatching { i.addMileageListener(executor, mileage) }
    }

    fun stop() {
        val i = info ?: return
        runCatching { i.removeEnergyLevelListener(energy) }
        runCatching { i.removeMileageListener(mileage) }
    }
}
