package com.qrz.voicetriggerrecorder.record

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StorageReliabilityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun wav(dir: File, name: String = "same.wav"): File {
        val file = File(dir, name)
        WavFileWriter(file, 16000).apply {
            writeSamples(ShortArray(16000) { 1000 }, 16000)
            assertTrue(closeAndCommit())
        }
        return file
    }
    private fun save(file: File, time: Long = 100, hook: (File) -> Unit = {}): Boolean =
        RecordingMetadataStore.writeFinalized(file, time, time + 1000, 16000, 740,
            RecordingCloseReason.ManualStop, "TestVad", hook)
    private fun sidecar(file: File) = File(file.parentFile, "${file.name}.json")

    @Test fun listAndTransferAgreeWithoutRewritingLegacyFiles() {
        val dir = temporary.newFolder()
        val repository = RecordingRepository(context, RecordingStorage(dir) { emptyList() })
        val complete = wav(dir, "complete.wav")
        for ((name, length) in listOf("truncated.wav" to 1044L, "missing.wav" to 44L)) {
            complete.copyTo(File(dir, name))
            RandomAccessFile(File(dir, name), "rw").use { it.setLength(length) }
        }
        val empty = File(dir, "empty.wav")
        WavFileWriter(empty, 16000).apply { closeAndCommit() }
        // Even a self-consistent header with zero samples is not transferable.
        complete.copyTo(empty, overwrite = true)
        RandomAccessFile(empty, "rw").use {
            it.setLength(44); it.seek(4); it.write(byteArrayOf(36, 0, 0, 0))
            it.seek(40); it.write(byteArrayOf(0, 0, 0, 0))
        }
        val rows = repository.listRecordings()
        assertEquals(4, rows.size)
        rows.forEach {
            val expected = it.name == "complete.wav"
            assertEquals(expected, it.isFinalized)
            assertEquals(!expected, it.isCorrupted)
            assertEquals(expected, RecordingMetadataStore.isReadyForTransfer(File(it.path)))
        }
        assertEquals(0, dir.listFiles()!!.count { it.name.endsWith(".json") })
        assertFalse(RecordingMetadataStore.isReadyForTransfer(File(dir, "absent.wav")))
    }

    @Test fun sourceSurvivesDirectorySwitchAndNamesNeverAlias() {
        val internal = temporary.newFolder("internal")
        val external = temporary.newFolder("external")
        var externalAvailable = false
        val storage = RecordingStorage(internal) { if (externalAvailable) listOf(external) else emptyList() }
        val repository = RecordingRepository(context, storage)
        val old = wav(internal)
        val identity = repository.listRecordings().single().path
        externalAvailable = true
        val other = wav(external)
        assertEquals(2, repository.listRecordings().size)
        assertEquals(old.canonicalFile, repository.fileForTransfer(identity))
        assertTrue(runCatching { repository.fileForTransfer("same.wav") }.isFailure)
        assertEquals(DeleteOutcome.DELETED, repository.deleteRecording(identity))
        assertTrue(other.exists())
        assertTrue(runCatching { repository.fileForTransfer(identity) }.isFailure)
        externalAvailable = false
        assertTrue(runCatching { repository.fileForTransfer(other.absolutePath) }.isFailure)
        assertTrue(other.exists())
        val outside = wav(temporary.newFolder("outside"))
        assertTrue(runCatching { repository.fileForTransfer(outside.absolutePath) }.isFailure)
        assertTrue(runCatching { repository.fileForTransfer("../outside/same.wav") }.isFailure)
    }

    @Test fun deletionPreservesMetadataAndPartialCleanupCanRetry() {
        val dir = temporary.newFolder()
        val storage = RecordingStorage(dir) { emptyList() }
        val file = wav(dir)
        assertTrue(save(file))
        val old = sidecar(file).readText()
        val audioFailure = RecordingRepository(context, storage) { false }
        assertEquals(DeleteOutcome.AUDIO_FAILED, audioFailure.deleteRecording(file.absolutePath))
        assertEquals(old, sidecar(file).readText())
        assertTrue(file.exists())
        val metadataFailure = RecordingRepository(context, storage) { if (it == file) it.delete() else false }
        assertEquals(DeleteOutcome.METADATA_REMAINS, metadataFailure.deleteRecording(file.absolutePath))
        assertFalse(file.exists())
        assertEquals(old, sidecar(file).readText())
        val repository = RecordingRepository(context, storage)
        assertEquals(DeleteOutcome.ALREADY_ABSENT, repository.deleteRecording(file.absolutePath))
        assertFalse(sidecar(file).exists())
    }

    @Test fun failedTemporaryWriteAndCommitKeepPreviousJsonAndAudio() {
        val dir = temporary.newFolder()
        val file = wav(dir)
        assertTrue(save(file))
        val old = sidecar(file).readText()
        assertFalse(save(file, 200) { it.writeText("partial"); throw IOException("write failure") })
        assertEquals(old, sidecar(file).readText())
        assertFalse(save(file, 300) { it.delete() }) // atomic rename fails
        assertEquals(old, sidecar(file).readText())
        assertTrue(file.exists())
        assertEquals(2, dir.listFiles()!!.size)
        assertTrue(save(file, 400))
        assertEquals(400L, RecordingMetadataStore.loadOrCreate(file).createdAt)
    }

    @Test fun firstWriteFailureIsObservableAndRetryWorks() {
        val file = wav(temporary.newFolder())
        assertFalse(save(file) { throw IOException("no space") })
        assertTrue(file.exists())
        assertFalse(sidecar(file).exists())
        assertTrue(RecordingMetadataStore.loadOrCreate(file).isFinalized)
        assertTrue(save(file))
        assertEquals(740L, RecordingMetadataStore.loadOrCreate(file).speechDurationMs)
    }

    @Test fun readerWaitsForCommitAndDoesNotReplaceFinalizedFacts() {
        val file = wav(temporary.newFolder())
        assertTrue(save(file))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit<Boolean> { save(file, 500) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val readerStarted = CountDownLatch(1)
            val reader = executor.submit<RecordingMetadata> {
                readerStarted.countDown(); RecordingMetadataStore.loadOrCreate(file)
            }
            assertTrue(readerStarted.await(5, TimeUnit.SECONDS))
            assertFalse(reader.isDone)
            release.countDown()
            assertTrue(writer.get(5, TimeUnit.SECONDS))
            assertEquals(500L, reader.get(5, TimeUnit.SECONDS).createdAt)
            assertEquals(740L, RecordingMetadataStore.loadOrCreate(file).speechDurationMs)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
