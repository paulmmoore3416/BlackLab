package com.blacklab.app.facility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntakeTest {
    private val state = Facility.State(assets = listOf(
        Facility.Asset(id = "ahu2", name = "AHU-2", kind = "HVAC", tag = "FAC-0042", location = "B-wing mechanical room"),
        Facility.Asset(id = "wh1", name = "Water heater 1", kind = "Plumbing", tag = "FAC-0007", location = "Kitchen"),
    ))

    @Test fun leakUnderSink() {
        val d = Intake.parse("Leak under the sink in room 214, B-wing", state)
        assertEquals("Plumbing", d.trade)
        assertEquals(Intake.Priority.P2, d.priority)
        assertTrue(d.location, d.location.contains("room 214") && d.location.contains("B-wing"))
        assertTrue(d.checklist.first().startsWith("Stop the water"))
        assertTrue(d.notes().contains("Priority: P2"))
    }

    @Test fun emergenciesAreP1WithReasons() {
        assertEquals(Intake.Priority.P1, Intake.parse("Flooding in the basement from a burst pipe").priority)
        assertEquals(listOf("gas smell"), Intake.parse("Smells like gas in the kitchen").reasons)
        assertEquals(Intake.Priority.P1, Intake.parse("Someone is stuck in the elevator on floor 2").priority)
        assertEquals("Elevator", Intake.parse("Someone is stuck in the elevator on floor 2").trade)
        assertEquals(Intake.Priority.P1, Intake.parse("Ligature point found on a closet hook, day room").priority)
        // "fire alarm" is the system, not a fire
        assertEquals(Intake.Priority.P3, Intake.parse("Fire alarm pull station cover missing in the north hall").priority)
        assertEquals("Life safety", Intake.parse("Fire alarm pull station cover missing in the north hall").trade)
    }

    @Test fun routineAndPlanned() {
        assertEquals(Intake.Priority.P3, Intake.parse("Ceiling tile stained in office 12").priority)
        assertEquals(Intake.Priority.P4, Intake.parse("Touch-up paint in the main lobby when you get a chance").priority)
    }

    @Test fun matchesAssetsByTagNameOrLocation() {
        assertEquals("ahu2", Intake.parse("FAC-0042 is making a loud squeal", state).asset?.id)
        assertEquals("ahu2", Intake.parse("AHU-2 belt squealing, too hot upstairs", state).asset?.id)
        assertEquals("wh1", Intake.parse("No hot water in the kitchen", state).asset?.id)
        assertNull(Intake.parse("Outlet dead in room 5", state).asset)
    }

    @Test fun longTextGetsAShortTitle() {
        val d = Intake.parse("The toilet in the second floor staff restroom keeps running and now it is overflowing onto the floor near the nurses station. Please hurry.")
        assertTrue(d.title.length <= 71)
        assertEquals(Intake.Priority.P2, d.priority)
    }
}
