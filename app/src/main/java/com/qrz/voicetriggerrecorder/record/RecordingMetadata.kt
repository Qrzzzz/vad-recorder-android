package com.qrz.voicetriggerrecorder.record

data class RecordingMetadata(
    val id: String,
    val fileName: String,
    val path: String,
    val createdAt: Long,
    val endedAt: Long?,
    val durationMs: Long?,
    val sizeBytes: Long,
    val sampleRate: Int?,
    val speechDurationMs: Long?,
    val closeReason: String?,
    val vadEngineName: String?,
    val isCorrupted: Boolean,
    val isFinalized: Boolean
)
