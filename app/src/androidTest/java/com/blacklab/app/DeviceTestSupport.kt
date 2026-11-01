package com.blacklab.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider

/** Shared helpers for the on-device tests. They run inside the debug build against its own data. */
object DeviceTest {
    val context: Context get() = ApplicationProvider.getApplicationContext()
    val app: AppContainer get() = context.app
    fun tag() = "DeviceTest-" + System.nanoTime()
}
