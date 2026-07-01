package com.qrz.voicetriggerrecorder.ui

typealias RecorderUiStateMutation = (RecorderUiState) -> RecorderUiState

data class RecorderUiState(
    val serviceRunning: Boolean = false,
    val recorderPhase: RecorderPhase = RecorderPhase.IDLE,
    val currentFileName: String? = null,
    val lastSavedFileName: String? = null,
    val savedCount: Int = 0,
    val errorMessage: String? = null,
    val speechDetected: Boolean = false,
    val countdownRemainingMs: Long? = null,
    val autoStopAtMs: Long? = null
)
