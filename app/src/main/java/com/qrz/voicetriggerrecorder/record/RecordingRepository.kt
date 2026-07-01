package com.qrz.voicetriggerrecorder.record

import android.content.Context
import android.os.Environment
import java.io.File

class RecordingRepository(private val context: Context) {

    private val recordingsDir: File
        get() = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            "voice-recordings"
        )

    fun listRecordings(): List<RecordingFile> {
        val dir = recordingsDir
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".wav", ignoreCase = true) }
            ?.map { file ->
                val metadata = RecordingMetadataStore.loadOrCreate(file)
                RecordingFile(
                    name = file.name,
                    path = file.absolutePath,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    durationMs = metadata.durationMs,
                    id = metadata.id,
                    fileName = metadata.fileName,
                    createdAt = metadata.createdAt,
                    endedAt = metadata.endedAt,
                    sampleRate = metadata.sampleRate,
                    speechDurationMs = metadata.speechDurationMs,
                    closeReason = metadata.closeReason,
                    vadEngineName = metadata.vadEngineName,
                    isCorrupted = metadata.isCorrupted,
                    isFinalized = metadata.isFinalized
                )
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()
    }

    fun deleteRecording(fileName: String): Boolean {
        val file = File(recordingsDir, fileName)
        val deletedRecording = if (file.exists()) file.delete() else false
        val deletedMetadata = RecordingMetadataStore.deleteFor(file)
        return deletedRecording && deletedMetadata
    }
}
