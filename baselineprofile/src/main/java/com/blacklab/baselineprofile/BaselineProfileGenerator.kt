package com.blacklab.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cold start to Home and a scroll of the Home cards — the paths every launch takes. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun startupAndHome() = rule.collect(packageName = "com.blacklab.app", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        val w = device.displayWidth / 2
        device.swipe(w, device.displayHeight * 3 / 4, w, device.displayHeight / 4, 20)
        device.waitForIdle()
    }
}
