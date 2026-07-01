package com.qrz.voicetriggerrecorder.record

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.RandomAccessFile

class RecordingRepository(private val context: Context) {

    private val recordingsDir: File
        get() {
            val parent = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: File(context.filesDir, "music")
            return File(parent, "voice-recordings")
        }

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
                    durationMs = readDurationMs(file)
                )
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()
    }

    fun deleteRecording(fileName: String): Boolean {
        val file = File(recordingsDir, fileName)
        return if (file.exists()) file.delete() else false
    }

    private fun readDurationMs(file: File): Long? {
        val parsed = runCatching { readWavDurationMs(file) }.getOrNull()
        if (parsed != null) {
            return parsed
        }
        return estimateLegacyDuration(file.length())
    }

    private fun readWavDurationMs(file: File): Long? {
        if (!file.exists() || file.length() <= 44L) {
            return null
        }

        RandomAccessFile(file, "r").use { raf ->
            if (raf.readAscii(4) != "RIFF") return null
            raf.skipBytes(4)
            if (raf.readAscii(4) != "WAVE") return null

            var sampleRate: Long? = null
            var channels: Int? = null
            var bitsPerSample: Int? = null
            var dataSize: Long? = null

            while (raf.filePointer + 8 <= raf.length()) {
                val chunkId = raf.readAscii(4)
                val chunkSize = raf.readLittleEndianInt().toLong() and 0xFFFFFFFFL
                val nextChunk = raf.filePointer + chunkSize

                when (chunkId) {
                    "fmt " -> {
                        if (chunkSize >= 16L) {
                            raf.skipBytes(2)
                            channels = raf.readLittleEndianShort()
                            sampleRate = raf.readLittleEndianInt().toLong() and 0xFFFFFFFFL
                            raf.skipBytes(6)
                            bitsPerSample = raf.readLittleEndianShort()
                        }
                    }

                    "data" -> {
                        dataSize = chunkSize
                    }
                }

                raf.seek((nextChunk + (chunkSize and 1L)).coerceAtMost(raf.length()))

                if (sampleRate != null && channels != null && bitsPerSample != null && dataSize != null) {
                    break
                }
            }

            val bytesPerSecond = sampleRate
                ?.takeIf { it > 0 }
                ?.times(channels?.takeIf { it > 0 }?.toLong() ?: return null)
                ?.times(bitsPerSample?.takeIf { it > 0 }?.toLong() ?: return null)
                ?.div(8L)
                ?.takeIf { it > 0L }
                ?: return null

            return dataSize?.times(1000L)?.div(bytesPerSecond)
        }
    }

    private fun estimateLegacyDuration(fileSize: Long): Long? {
        val bytesPerSecond = 16000 * 1 * 16 / 8
        if (fileSize <= 44L) return null
        val dataBytes = fileSize - 44L
        return dataBytes * 1000L / bytesPerSecond
    }

    private fun RandomAccessFile.readAscii(length: Int): String {
        val buffer = ByteArray(length)
        readFully(buffer)
        return String(buffer, Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readLittleEndianInt(): Int {
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
            throw IllegalStateException("Unexpected EOF while reading WAV header.")
        }
        return (b0 and 0xFF) or
            ((b1 and 0xFF) shl 8) or
            ((b2 and 0xFF) shl 16) or
            ((b3 and 0xFF) shl 24)
    }

    private fun RandomAccessFile.readLittleEndianShort(): Int {
        val b0 = read()
        val b1 = read()
        if (b0 < 0 || b1 < 0) {
            throw IllegalStateException("Unexpected EOF while reading WAV header.")
        }
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8)
    }
}
