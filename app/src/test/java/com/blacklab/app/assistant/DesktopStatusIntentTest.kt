package com.blacklab.app.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopStatusIntentTest {

    @Test fun statusQuestionsAreRecognized() {
        listOf(
            "what's the status of the desktop ai", "what is the status of my desktop ai?", "status of the desktop ai",
            "desktop ai status", "desktop ai", "desktop status", "is the desktop ai online", "is my desktop ai working",
            "how is the desktop ai doing", "check status of desktop ai", "is the desktop connected", "is my computer online",
            "is the desktop up", "is the desktop ai offline", "what model is the desktop ai using", "is the computer's brain connected",
            "is my phone linked to the computer", "desktop link status",
        ).forEach { assertTrue(it, DesktopAi.isStatusQuestion(it)) }
    }

    @Test fun handOffsAndOtherTopicsAreNot() {
        listOf(
            "ask my desktop how much disk space is left", "on my computer open my downloads", "desktop: system status",
            "tell my computer to check if the backup is running", "is my computer's disk space ok", "back up my computer",
            "what's the status of my oil change", "add task check the desktop", "open the desktop folder",
        ).forEach { assertFalse(it, DesktopAi.isStatusQuestion(it)) }
    }
}
