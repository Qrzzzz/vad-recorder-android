package com.qrz.voicetriggerrecorder.record

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files

class WavFileWriter(
    private val finalFile: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16
) {
    private val partFile: File = File(finalFile.parentFile, "${finalFile.name}.part")
    private var raf: RandomAccessFile? = null
    private var dataBytes: Long = 0
    private var closed = false
    private var writeFailed = false
    private var committed = false
    private var pcmBuffer = ByteArray(0)
    internal var pcmWriteCalls = 0
        private set

    init {
        val parent = finalFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        if (!partFile.createNewFile()) throw IOException("Recording partial already exists")
        try {
            raf = RandomAccessFile(partFile, "rw")
            raf!!.write(ByteArray(44))
        } catch (e: IOException) {
            abort()
            throw e
        }
    }

    fun writeSamples(samples: ShortArray, length: Int): Boolean {
        if (closed || writeFailed) return false
        if (length <= 0) return true
        val raf = this.raf ?: return false
        val safeLength = length.coerceAtMost(samples.size)
        if (safeLength == 0) return true
        try {
            val byteCount = safeLength * 2
            if (pcmBuffer.size < byteCount) pcmBuffer = ByteArray(byteCount)
            for (i in 0 until safeLength) {
                val v = samples[i].toInt()
                pcmBuffer[i * 2] = v.toByte()
                pcmBuffer[i * 2 + 1] = (v shr 8).toByte()
            }
            raf.write(pcmBuffer, 0, byteCount)
            pcmWriteCalls++
            dataBytes += byteCount.toLong()
            return true
        } catch (_: Exception) {
            writeFailed = true
            return false
        }
    }

    fun closeAndCommit(): Boolean {
        if (closed) return committed
        if (writeFailed) {
            abort()
            return false
        }
        if (dataBytes <= 0L) {
            abort()
            return false
        }

        val raf = this.raf ?: return false
        var finalized = false
        try {
            closed = true
            raf.seek(0)
            val totalDataLen = dataBytes + 36
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8

            raf.writeBytes("RIFF")
            writeIntLE(raf, totalDataLen.toInt())
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            writeIntLE(raf, 16)
            writeShortLE(raf, 1) // PCM = 1
            writeShortLE(raf, channels)
            writeIntLE(raf, sampleRate)
            writeIntLE(raf, byteRate)
            writeShortLE(raf, blockAlign)
            writeShortLE(raf, bitsPerSample)
            raf.writeBytes("data")
            writeIntLE(raf, dataBytes.toInt())
            raf.fd.sync()
            finalized = true
        } catch (_: Exception) {
        } finally {
            try {
                raf.close()
            } catch (_: Exception) {
                finalized = false
            }
            this.raf = null
        }

        if (!finalized) {
            partFile.delete()
            return false
        }

        committed = movePartToFinal()
        if (!committed) {
            partFile.delete()
        }
        return committed
    }

    fun abort(): Boolean {
        closed = true
        try {
            raf?.close()
        } catch (_: Exception) {
        }
        raf = null
        return !partFile.exists() || partFile.delete()
    }

    val totalBytes: Long get() = dataBytes

    val activeFile: File get() = partFile

    private fun movePartToFinal(): Boolean {
        return try {
            // ATOMIC_MOVE may replace an existing target even without REPLACE_EXISTING.
            // The default move contract rejects an existing destination.
            Files.move(partFile.toPath(), finalFile.toPath())
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun writeIntLE(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xff)
        raf.write((value shr 8) and 0xff)
        raf.write((value shr 16) and 0xff)
        raf.write((value shr 24) and 0xff)
    }

    private fun writeShortLE(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xff)
        raf.write((value shr 8) and 0xff)
    }

    companion object {
        fun cleanupStalePartFiles(
            directory: File,
            olderThanMs: Long,
            nowMs: Long = System.currentTimeMillis()
        ): Int {
            if (!directory.exists() || !directory.isDirectory) return 0

            var deleted = 0
            directory.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".wav.part", ignoreCase = true) }
                ?.forEach { file ->
                    val ageMs = nowMs - file.lastModified()
                    if (ageMs >= olderThanMs && file.delete()) {
                        deleted++
                    }
                }
            return deleted
        }
    }
}
