package com.qrz.voicetriggerrecorder.app

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import com.qrz.voicetriggerrecorder.R

enum class AppNightMode(
    val storageValue: String,
    private val delegateMode: Int,
    @StringRes val labelRes: Int
) {
    SYSTEM(
        storageValue = "system",
        delegateMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        labelRes = R.string.theme_system
    ),
    LIGHT(
        storageValue = "light",
        delegateMode = AppCompatDelegate.MODE_NIGHT_NO,
        labelRes = R.string.theme_light
    ),
    DARK(
        storageValue = "dark",
        delegateMode = AppCompatDelegate.MODE_NIGHT_YES,
        labelRes = R.string.theme_dark
    );

    fun apply() {
        AppCompatDelegate.setDefaultNightMode(delegateMode)
    }

    companion object {
        fun fromStorageValue(value: String?): AppNightMode {
            return entries.firstOrNull { it.storageValue == value } ?: SYSTEM
        }
    }
}
