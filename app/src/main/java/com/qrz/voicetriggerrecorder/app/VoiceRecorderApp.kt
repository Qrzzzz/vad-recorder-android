package com.qrz.voicetriggerrecorder.app

import android.app.Application
import com.qrz.voicetriggerrecorder.record.RecorderPreferences

class VoiceRecorderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val preferences = RecorderPreferences(this)
        preferences.loadAppNightMode().apply()
        preferences.loadAppLanguage().apply()
    }
}
