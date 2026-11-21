package com.blacklab.app.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicrosoftAppsTest {
    @Test fun findsTeamsJoinLinksInInvites() {
        val body = "Join on your computer\nhttps://teams.microsoft.com/l/meetup-join/19%3ameeting_abc%40thread.v2/0?context=%7b%22Tid%22%7d.\nMeeting ID: 123"
        assertEquals("https://teams.microsoft.com/l/meetup-join/19%3ameeting_abc%40thread.v2/0?context=%7b%22Tid%22%7d", MicrosoftApps.teamsLink(null, body))
        assertEquals("https://teams.live.com/meet/9876543210", MicrosoftApps.teamsLink("<https://teams.live.com/meet/9876543210>", null))
        assertEquals("https://teams.microsoft.com/meet/2345?p=xyz", MicrosoftApps.teamsLink("Microsoft Teams Meeting", "Join: https://teams.microsoft.com/meet/2345?p=xyz"))
    }

    @Test fun ignoresOtherLinks() {
        assertNull(MicrosoftApps.teamsLink("Room 214", "https://zoom.us/j/123 and https://example.com/teams.microsoft.com"))
        assertNull(MicrosoftApps.teamsLink(null, null))
    }
}
