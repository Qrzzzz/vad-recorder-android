package com.qrz.voicetriggerrecorder.app

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.qrz.voicetriggerrecorder.R

enum class AppLanguage(
    val storageValue: String,
    private val languageTag: String?,
    @StringRes val labelRes: Int
) {
    SYSTEM(
        storageValue = "system",
        languageTag = null,
        labelRes = R.string.language_system
    ),
    ENGLISH(
        storageValue = "en",
        languageTag = "en",
        labelRes = R.string.language_english
    ),
    CHINESE_SIMPLIFIED(
        storageValue = "zh",
        languageTag = "zh",
        labelRes = R.string.language_chinese_simplified
    );

    fun apply() {
        val locales = languageTag?.let(LocaleListCompat::forLanguageTags)
            ?: LocaleListCompat.getEmptyLocaleList()
        AppCompatDelegate.setApplicationLocales(locales)
    }

    companion object {
        fun fromStorageValue(value: String?): AppLanguage {
            return entries.firstOrNull { it.storageValue == value } ?: SYSTEM
        }
    }
}
