package com.qrz.voicetriggerrecorder.app

import android.app.Application
import com.qrz.voicetriggerrecorder.record.RecorderPreferences

class VoiceRecorderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RecorderPreferences(this).loadAppLanguage().apply()
    }
}
