package com.qrz.voicetriggerrecorder.record

data class RecordingFile(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val durationMs: Long?
)
