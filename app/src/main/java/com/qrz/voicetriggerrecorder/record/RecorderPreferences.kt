package com.qrz.voicetriggerrecorder.record

import android.content.Context
import com.qrz.voicetriggerrecorder.app.AppLanguage

class RecorderPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "voice_recorder_prefs"
        private const val KEY_SENSITIVITY_PRESET = "sensitivity_preset"
        private const val KEY_AUTO_STOP_HOURS = "auto_stop_hours"
        private const val KEY_APP_LANGUAGE = "app_language"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSensitivityPreset(): SensitivityPreset {
        return SensitivityPreset.fromStorageValue(
            prefs.getString(KEY_SENSITIVITY_PRESET, SensitivityPreset.NORMAL_ROOM.storageValue)
        )
    }

    fun saveSensitivityPreset(preset: SensitivityPreset) {
        prefs.edit().putString(KEY_SENSITIVITY_PRESET, preset.storageValue).apply()
    }

    fun loadAutoStopHours(): Int {
        return prefs.getInt(KEY_AUTO_STOP_HOURS, 0).coerceIn(0, 12)
    }

    fun saveAutoStopHours(hours: Int) {
        prefs.edit().putInt(KEY_AUTO_STOP_HOURS, hours.coerceIn(0, 12)).apply()
    }

    fun loadAppLanguage(): AppLanguage {
        return AppLanguage.fromStorageValue(
            prefs.getString(KEY_APP_LANGUAGE, AppLanguage.SYSTEM.storageValue)
        )
    }

    fun saveAppLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_APP_LANGUAGE, language.storageValue).apply()
    }
}
