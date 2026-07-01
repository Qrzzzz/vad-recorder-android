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
                RecordingFile(
                    name = file.name,
                    path = file.absolutePath,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    durationMs = estimateDuration(file.length())
                )
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()
    }

    fun deleteRecording(fileName: String): Boolean {
        val file = File(recordingsDir, fileName)
        return if (file.exists()) file.delete() else false
    }

    private fun estimateDuration(fileSize: Long): Long? {
        // WAV: PCM 16-bit mono 16kHz = 32000 bytes/sec
        val bytesPerSecond = 16000 * 1 * 16 / 8
        if (fileSize <= 44) return null
        val dataBytes = fileSize - 44
        return dataBytes * 1000 / bytesPerSecond
    }
}
