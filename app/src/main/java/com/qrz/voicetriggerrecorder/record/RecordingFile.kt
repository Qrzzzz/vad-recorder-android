package com.qrz.voicetriggerrecorder.record

data class RecordingFile(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val durationMs: Long?,
    val id: String = name.substringBeforeLast('.'),
    val sessionId: String = id,
    val fileName: String = name,
    val createdAt: Long = lastModified,
    val endedAt: Long? = lastModified,
    val sampleRate: Int? = null,
    val speechDurationMs: Long? = null,
    val closeReason: String? = null,
    val vadEngineName: String? = null,
    val vadConfidence: Float? = null,
    val isCorrupted: Boolean = false,
    val isFinalized: Boolean = false,
    val isExported: Boolean = false
)
