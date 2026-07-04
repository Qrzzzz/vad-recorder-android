package com.qrz.voicetriggerrecorder.record

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.qrz.voicetriggerrecorder.app.AppNightMode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RecorderPreferencesTest {
    private lateinit var context: Context
    private lateinit var preferences: RecorderPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("voice_recorder_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        preferences = RecorderPreferences(context)
    }

    @Test
    fun nightModeDefaultsToSystem() {
        assertEquals(AppNightMode.SYSTEM, preferences.loadAppNightMode())
    }

    @Test
    fun nightModeRoundTripsSavedValue() {
        preferences.saveAppNightMode(AppNightMode.DARK)

        assertEquals(AppNightMode.DARK, preferences.loadAppNightMode())
    }

    @Test
    fun unknownNightModeFallsBackToSystem() {
        context.getSharedPreferences("voice_recorder_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("app_night_mode", "unexpected")
            .commit()

        assertEquals(AppNightMode.SYSTEM, preferences.loadAppNightMode())
    }
}
