package com.qrz.voicetriggerrecorder.record

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    init {
        val parent = finalFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        raf = RandomAccessFile(partFile, "rw")
        raf!!.setLength(0)
        for (i in 0 until 44) {
            raf!!.write(0)
        }
    }

    fun writeSamples(samples: ShortArray, length: Int) {
        if (closed || writeFailed || length <= 0) return
        val raf = this.raf ?: return
        val safeLength = length.coerceAtMost(samples.size)
        try {
            for (i in 0 until safeLength) {
                val v = samples[i].toInt()
                raf.write(v and 0xff)
                raf.write((v shr 8) and 0xff)
            }
            dataBytes += (safeLength * 2).toLong()
        } catch (_: Exception) {
            writeFailed = true
        }
    }

    fun closeAndCommit(): Boolean {
        if (closed) return finalFile.exists()
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
            try {
                raf.fd.sync()
            } catch (_: Exception) {
            }
            finalized = true
        } catch (_: Exception) {
        } finally {
            try {
                raf.close()
            } catch (_: Exception) {
            }
            this.raf = null
        }

        if (!finalized) {
            partFile.delete()
            return false
        }

        return if (movePartToFinal()) {
            true
        } else {
            partFile.delete()
            false
        }
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
            Files.move(
                partFile.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (_: AtomicMoveNotSupportedException) {
            try {
                Files.move(
                    partFile.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                true
            } catch (_: Exception) {
                false
            }
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
