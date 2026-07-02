package com.qrz.voicetriggerrecorder.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class WavFileWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun closeAndCommitFinalizesHeaderAndMovesPartFile() {
        val wavFile = temporaryFolder.newFolder("recordings").resolve("clip.wav")
        val writer = WavFileWriter(wavFile, sampleRate = 16_000)
        writer.writeSamples(ShortArray(16_000) { 1_000 }, 16_000)

        assertTrue(writer.activeFile.exists())
        assertTrue(writer.closeAndCommit())

        assertTrue(wavFile.exists())
        assertFalse(writer.activeFile.exists())

        RandomAccessFile(wavFile, "r").use { raf ->
            assertEquals("RIFF", raf.readAscii(4))
            assertEquals(32_036, raf.readIntLe())
            assertEquals("WAVE", raf.readAscii(4))
            assertEquals("fmt ", raf.readAscii(4))
            assertEquals(16, raf.readIntLe())
            assertEquals(1, raf.readShortLe())
            assertEquals(1, raf.readShortLe())
            assertEquals(16_000, raf.readIntLe())
            assertEquals(32_000, raf.readIntLe())
            assertEquals(2, raf.readShortLe())
            assertEquals(16, raf.readShortLe())
            assertEquals("data", raf.readAscii(4))
            assertEquals(32_000, raf.readIntLe())
            raf.seek(8)
            assertEquals("WAVE", raf.readAscii(4))
            raf.seek(24)
            assertEquals(16_000, raf.readIntLe())
            raf.seek(40)
            assertEquals(32_000, raf.readIntLe())
        }
    }

    @Test
    fun abortDeletesPartFileWithoutCreatingWav() {
        val wavFile = temporaryFolder.newFolder("recordings").resolve("clip.wav")
        val writer = WavFileWriter(wavFile, sampleRate = 16_000)
        writer.writeSamples(shortArrayOf(1, 2, 3), 3)

        assertTrue(writer.activeFile.exists())
        assertTrue(writer.abort())

        assertFalse(writer.activeFile.exists())
        assertFalse(wavFile.exists())
    }

    @Test
    fun closeAndCommitAfterWriteFailureDeletesPartWithoutCreatingWav() {
        val wavFile = temporaryFolder.newFolder("recordings").resolve("clip.wav")
        val writer = WavFileWriter(wavFile, sampleRate = 16_000)
        writer.writeSamples(shortArrayOf(1, 2, 3), 3)
        writer.javaClass.getDeclaredField("writeFailed").apply {
            isAccessible = true
            setBoolean(writer, true)
        }

        assertFalse(writer.closeAndCommit())

        assertFalse(writer.activeFile.exists())
        assertFalse(wavFile.exists())
    }

    private fun RandomAccessFile.readAscii(length: Int): String {
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readIntLe(): Int {
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        return (b0 and 0xff) or
            ((b1 and 0xff) shl 8) or
            ((b2 and 0xff) shl 16) or
            ((b3 and 0xff) shl 24)
    }

    private fun RandomAccessFile.readShortLe(): Int {
        val b0 = read()
        val b1 = read()
        return (b0 and 0xff) or ((b1 and 0xff) shl 8)
    }
}
