package com.qrz.voicetriggerrecorder.record

import androidx.annotation.StringRes
import com.qrz.voicetriggerrecorder.R

enum class SensitivityPreset(
    val storageValue: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val detectorSensitivity: Float
) {
    QUIET_BEDROOM(
        storageValue = "quiet_bedroom",
        labelRes = R.string.sensitivity_quiet_bedroom_label,
        descriptionRes = R.string.sensitivity_quiet_bedroom_description,
        detectorSensitivity = 0.72f
    ),
    NORMAL_ROOM(
        storageValue = "normal_room",
        labelRes = R.string.sensitivity_normal_room_label,
        descriptionRes = R.string.sensitivity_normal_room_description,
        detectorSensitivity = 0.55f
    ),
    NOISY_ENVIRONMENT(
        storageValue = "noisy_environment",
        labelRes = R.string.sensitivity_noisy_room_label,
        descriptionRes = R.string.sensitivity_noisy_room_description,
        detectorSensitivity = 0.38f
    );

    companion object {
        fun fromStorageValue(value: String?): SensitivityPreset {
            return values().firstOrNull { it.storageValue == value } ?: NORMAL_ROOM
        }
    }
}
