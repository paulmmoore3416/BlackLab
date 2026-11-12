package com.blacklab.app.link

import com.blacklab.app.core.JsonStore
import com.blacklab.app.facility.Facility
import com.blacklab.app.vehicle.Garage
import com.blacklab.app.vehicle.Nhtsa
import com.blacklab.app.vehicle.VehicleLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * What the desktop link watches besides Room. File-backed [JsonStore]s don't go through Room's
 * invalidation tracker, so without this the desktop's Facility and Garage views stayed stale until
 * a manual refresh. LinkWatchTest fails when a new JsonStore is added but not listed here.
 */
object LinkWatch {
    fun stores(): List<JsonStore<*>> = listOf(Facility.store, Garage.store, VehicleLink.store, Nhtsa.store)

    /**
     * Calls [onChange] when any of [sources] changes; each source's current value is skipped. Bursts (live car readings
     * arrive every second) are throttled to one call per [minGapMs], and the last change of a burst always gets a call.
     */
    fun launch(scope: CoroutineScope, sources: List<Flow<*>>, minGapMs: Long = 5_000, onStart: () -> Unit = {}, onChange: () -> Unit): Job =
        scope.launch {
            sources.map { it.drop(1) }.merge().onStart { onStart() }.conflate().collect { onChange(); delay(minGapMs) }
        }
}
