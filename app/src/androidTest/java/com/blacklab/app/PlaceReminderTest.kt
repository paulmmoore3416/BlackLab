package com.blacklab.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.blacklab.app.brainstorm.NoteOps
import com.blacklab.app.brainstorm.PlaceReceiver
import com.blacklab.app.brainstorm.PlaceReminders
import com.blacklab.app.data.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A location reminder fires a notification on arrival and stays quiet for the wrong direction. */
@RunWith(AndroidJUnit4::class)
class PlaceReminderTest {
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION)

    private fun fire(noteId: Long, entering: Boolean) {
        val i = Intent(DeviceTest.context, PlaceReceiver::class.java).setAction(PlaceReminders.ACTION)
            .putExtra(PlaceReminders.EXTRA_NOTE, noteId).putExtra(LocationManager.KEY_PROXIMITY_ENTERING, entering)
        DeviceTest.context.sendBroadcast(i) // delivered like the system's proximity alert, so goAsync() works
    }

    private fun posted(text: String): Boolean {
        val nm = DeviceTest.context.getSystemService(NotificationManager::class.java)
        return nm.activeNotifications.any { it.notification.extras.getCharSequence("android.title")?.contains(text) == true }
    }

    private fun waitFor(timeoutMs: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (check()) return true; Thread.sleep(100) }
        return check()
    }

    @Test fun arrivingAtThePlaceNotifies() = runBlocking {
        val app = DeviceTest.app
        val title = DeviceTest.tag()
        val id = NoteOps.save(app, NoteEntity(profile = "PERSONAL", title = title, body = "Pick up filters",
            placeName = "Plant 2", placeLat = 41.88, placeLon = -87.63, placeRadius = 150, placeOnLeave = false))
        try {
            fire(id, entering = false) // leaving: not what this reminder asked for
            Thread.sleep(800)
            assertFalse(posted(title))
            fire(id, entering = true)
            assertTrue("arrival notification", waitFor(5_000) { posted(title) })
        } finally {
            DeviceTest.context.getSystemService(NotificationManager::class.java).cancelAll()
            app.db.notes().get(id)?.let { NoteOps.deleteForever(app, it) }
        }
    }
}
