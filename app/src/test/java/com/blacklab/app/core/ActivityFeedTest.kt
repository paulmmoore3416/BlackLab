package com.blacklab.app.core

import com.blacklab.app.core.ActivityFeed.Kind
import com.blacklab.app.data.EventEntity
import com.blacklab.app.data.RecordingEntity
import com.blacklab.app.data.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityFeedTest {
    private val now = 10_000_000L
    private val since = 5_000_000L

    @Test fun collectsWhatHappenedInTheWindowNewestFirst() {
        val tasks = listOf(
            TaskEntity(id = 1, profile = "P", title = "Done in window", completed = true, completedAt = 9_000_000, createdAt = 1),
            TaskEntity(id = 2, profile = "P", title = "Added in window", createdAt = 6_000_000),
            TaskEntity(id = 3, profile = "P", title = "Old", createdAt = 1),
            TaskEntity(id = 4, profile = "P", title = "Reminder fired", createdAt = 1, reminderAt = 7_000_000),
        )
        val events = listOf(EventEntity(id = 5, profile = "P", title = "Meeting", start = 20_000_000, end = 21_000_000, createdAt = 8_000_000))
        val recs = listOf(RecordingEntity(id = 6, profile = "P", filePath = "x", title = "Standup", transcript = "hello", createdAt = 9_500_000))
        val feed = ActivityFeed.build(since, now, tasks, events, recordings = recs)
        assertEquals(listOf(Kind.RECORDING, Kind.TASK_DONE, Kind.EVENT_NEW, Kind.REMINDER, Kind.TASK_NEW), feed.map { it.kind })
        assertEquals("Recording saved · transcript ready", feed[0].detail)
        assertEquals("task/1", feed[1].route)
    }

    @Test fun windowStartIsClamped() {
        val t = 1_793_000_000_000L // November 2026
        assertEquals(t - 12 * 3_600_000L, ActivityFeed.since(0, t))       // first run: last 12 hours
        assertEquals(t - 3 * 86_400_000L, ActivityFeed.since(1, t))       // long away: at most 3 days
        assertEquals(t - 1000, ActivityFeed.since(t - 1000, t))
    }
}
