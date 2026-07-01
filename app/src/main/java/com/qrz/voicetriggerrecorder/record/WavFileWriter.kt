package com.qrz.voicetriggerrecorder.record

import java.io.File
import java.io.RandomAccessFile

class WavFileWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16
) {
    private var raf: RandomAccessFile? = null
    private var dataBytes: Long = 0
    private var closed = false

    init {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        raf = RandomAccessFile(file, "rw")
        raf!!.setLength(0)
        for (i in 0 until 44) {
            raf!!.write(0)
        }
    }

    fun writeSamples(samples: ShortArray, length: Int) {
        if (closed || length <= 0) return
        val raf = this.raf ?: return
        for (i in 0 until length) {
            val v = samples[i].toInt()
            raf.write(v and 0xff)
            raf.write((v shr 8) and 0xff)
        }
        dataBytes += (length * 2).toLong()
    }

    fun close(): Boolean {
        if (closed) return true
        closed = true
        val raf = this.raf ?: return false
        try {
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
        } finally {
            try {
                raf.close()
            } catch (_: Exception) {
            }
            this.raf = null
        }
        return true
    }

    val totalBytes: Long get() = dataBytes

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
}
