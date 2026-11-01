package com.blacklab.app.car

import androidx.car.app.Screen
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blacklab.app.assistant.Briefing
import com.blacklab.app.summarize.SummarySources
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * Builds every Android Auto screen's template. The Car App Library validates templates as they're
 * built (row and action limits, text lines, required fields), so a rule violation throws here
 * instead of on the car's display.
 */
@RunWith(AndroidJUnit4::class)
class CarScreensTest {

    private fun check(make: (TestCarContext) -> Screen) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val ctx = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())
            val screen = make(ctx)
            assertNotNull(screen.onGetTemplate())
        }
    }

    @Test fun home() = check { CarHomeScreen(it) }
    @Test fun ask() = check { CarAskScreen(it) }
    @Test fun talkList() = check { CarTalkListScreen(it) }
    @Test fun talkGroups() { check { CarTalkListScreen(it, "Tech talk") }; check { CarTalkListScreen(it, "Everyday") } }
    @Test fun talk() = check { CarTalkScreen(it, "conv-ai-builder") }
    @Test fun agenda() = check { CarAgendaScreen(it) }
    @Test fun event() = check {
        val now = LocalDateTime.now()
        CarEventScreen(it, Briefing.AgendaItem(now, now.plusHours(1), "Chiller review", "Event", "Building C, Room 101", "event/0"))
    }
    @Test fun tasks() = check { CarTasksScreen(it) }
    @Test fun capture() = CarCaptureScreen.Mode.entries.forEach { m -> check { c -> CarCaptureScreen(c, m) } }
    @Test fun summaries() = check { CarSummariesScreen(it) }
    @Test fun summary() { check { CarSummaryScreen(it, null) }; check { CarSummaryScreen(it, SummarySources.Preset.WEEK) } }
    @Test fun garage() = check { CarGarageScreen(it) }
    @Test fun schedule() = check { CarScheduleScreen(it) }
    @Test fun message() = check { CarMessageScreen(it, "Title", "Message") }
}
