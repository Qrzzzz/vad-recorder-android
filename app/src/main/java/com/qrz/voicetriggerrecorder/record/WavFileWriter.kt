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
        require(sampleRate in 1..384000 && channels in 1..2 && bitsPerSample == 16)
        synchronized(RecordingRecovery.lock) {
            val parent = finalFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            if (finalFile.exists() || RecordingRecovery.manifest(finalFile).exists() || !partFile.createNewFile())
                throw IOException("Recording source already exists")
            RecordingRecovery.active.add(finalFile.canonicalPath)
            try {
                RecordingRecovery.prepare(finalFile, sampleRate, channels, bitsPerSample)
                raf = RandomAccessFile(partFile, "rw")
                writeHeader(raf!!, sampleRate, channels, bitsPerSample, 0)
                raf!!.fd.sync()
            } catch (e: IOException) {
                abort()
                throw e
            }
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
        if (dataBytes <= 0L && !writeFailed) {
            abort()
            return false
        }

        val raf = this.raf ?: return false
        var finalized = false
        try {
            closed = true
            // A failed write may have left a partial sample; retain complete sample frames only.
            dataBytes = ((raf.length() - 44).coerceAtLeast(0) / (channels * 2)) * (channels * 2)
            if (dataBytes == 0L) throw IOException("No complete sample frame")
            raf.setLength(44 + dataBytes)
            writeHeader(raf, sampleRate, channels, bitsPerSample, dataBytes)
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
            preserveForRecovery()
            return false
        }

        synchronized(RecordingRecovery.lock) {
            committed = movePartToFinal()
            if (committed) RecordingRecovery.manifest(finalFile).delete()
            RecordingRecovery.active.remove(finalFile.canonicalPath)
        }
        return committed
    }

    fun abort(): Boolean {
        synchronized(RecordingRecovery.lock) {
            closed = true
            try {
                raf?.close()
            } catch (_: Exception) {
            }
            raf = null
            RecordingRecovery.active.remove(finalFile.canonicalPath)
            return RecordingRecovery.discard(finalFile)
        }
    }

    fun preserveForRecovery() = synchronized(RecordingRecovery.lock) {
        closed = true
        runCatching { raf?.close() }
        raf = null
        RecordingRecovery.active.remove(finalFile.canonicalPath)
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

    companion object {
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

        internal fun writeHeader(raf: RandomAccessFile, rate: Int, channels: Int, bits: Int, bytes: Long) {
            require(bytes <= 0xffffffffL - 36)
            raf.seek(0)
            raf.writeBytes("RIFF")
            writeIntLE(raf, (bytes + 36).toInt())
            raf.writeBytes("WAVEfmt ")
            writeIntLE(raf, 16)
            writeShortLE(raf, 1)
            writeShortLE(raf, channels)
            writeIntLE(raf, rate)
            writeIntLE(raf, rate * channels * bits / 8)
            writeShortLE(raf, channels * bits / 8)
            writeShortLE(raf, bits)
            raf.writeBytes("data")
            writeIntLE(raf, bytes.toInt())
        }
    }
}
