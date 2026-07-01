package com.qrz.voicetriggerrecorder.ui

enum class RecorderPhase {
    IDLE,
    LISTENING,
    RECORDING,
    WAITING_TO_FINISH,
    SAVED,
    MICROPHONE_SETUP_FAILED,
    RECORDER_FAILED
}
