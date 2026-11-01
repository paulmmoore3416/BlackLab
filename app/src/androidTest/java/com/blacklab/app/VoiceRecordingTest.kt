package com.blacklab.app

import android.Manifest
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.blacklab.app.recorder.AudioEngine
import com.blacklab.app.recorder.RecConfig
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Voice notes: the recorder captures from the microphone and writes a playable AAC file. */
@RunWith(AndroidJUnit4::class)
class VoiceRecordingTest {
    @get:Rule val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test fun recordsAPlayableVoiceNote() {
        val f = File(DeviceTest.context.cacheDir, "voice-test.m4a").apply { delete() }
        val e = AudioEngine(DeviceTest.context, RecConfig(format = "M4A", sampleRate = 48_000, channels = 1, bitrate = 96_000), f, onLevel = { _, _ -> }, transcriber = null)
        val err = e.start()
        assumeTrue("No microphone on this device/emulator: $err", err == null)
        Thread.sleep(2_000)
        val ms = e.elapsedMs
        e.stop()
        try {
            assertTrue("recorded ${ms}ms", ms in 1_000..4_000)
            assertTrue(f.length() > 2_000)
            val dur = MediaMetadataRetriever().use { r -> r.setDataSource(f.path); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0 }
            assertTrue("file plays for ${dur}ms", dur in 1_000..4_000)
        } finally { f.delete() }
    }
}
