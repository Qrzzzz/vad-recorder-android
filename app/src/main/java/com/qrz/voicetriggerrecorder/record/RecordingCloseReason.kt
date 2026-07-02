package com.qrz.voicetriggerrecorder.record

enum class RecordingCloseReason {
    EndSilence,
    ManualStop,
    ServiceStop,
    ReadError,
    Destroy
}
