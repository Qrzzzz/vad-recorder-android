package com.qrz.voicetriggerrecorder.record

data class VadResult(
    val isSpeech: Boolean,
    val isCalibrating: Boolean,
    val confidence: Float,
    val rmsDb: Double,
    val noiseFloorDb: Double,
    val zeroCrossingRate: Double
)
