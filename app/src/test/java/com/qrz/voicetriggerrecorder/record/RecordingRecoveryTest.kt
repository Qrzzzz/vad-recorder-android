package com.qrz.voicetriggerrecorder.record

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordingRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun seed(name: String = "clip.wav", rate: Int = 44100, channels: Int = 1): File {
        val file = File(temporary.root, name)
        WavFileWriter(file, rate, channels).apply {
            assertTrue(writeSamples(shortArrayOf(1, 2, 3, 4), 4))
            preserveForRecovery()
        }
        return file
    }

    @Test fun knownFormatRestoresExactPcmOnceAndMarksInterruption() {
        val target = seed()
        val expected = RecordingRecovery.partial(target).readBytes().drop(44).toByteArray()
        assertEquals(RecoveryOutcome.RECOVERED, RecordingRecovery.recover(temporary.root).single().outcome)
        assertEquals(44100, RecordingMetadataStore.loadOrCreate(target).sampleRate)
        assertArrayEquals(expected, target.readBytes().drop(44).toByteArray())
        assertEquals("Recovered", RecordingMetadataStore.loadOrCreate(target).closeReason)
        repeat(3) { assertTrue(RecordingRecovery.recover(temporary.root).isEmpty()) }
        assertEquals(1, temporary.root.listFiles()!!.count { it.extension == "wav" })
    }

    @Test fun truncatedStereoSampleIsTrimmedToWholeFrame() {
        val target = seed(channels = 2)
        RandomAccessFile(RecordingRecovery.partial(target), "rw").use { it.setLength(51) }
        assertEquals(RecoveryOutcome.RECOVERED, RecordingRecovery.recover(temporary.root).single().outcome)
        assertEquals(48L, target.length())
        assertTrue(RecordingMetadataStore.isReadyForTransfer(target))
        RandomAccessFile(target, "r").use { it.seek(22); assertEquals(2, it.read()) }
    }

    @Test fun unknownAndEmptyStaySeparateWithoutGuessedRate() {
        val unknown = File(temporary.root, "legacy.wav.part").apply { writeBytes(ByteArray(300)) }
        val empty = File(temporary.root, "empty.wav")
        WavFileWriter(empty, 48000).preserveForRecovery()
        val outcomes = RecordingRecovery.recover(temporary.root).map { it.outcome }.toSet()
        assertEquals(setOf(RecoveryOutcome.UNKNOWN, RecoveryOutcome.EMPTY), outcomes)
        assertEquals(300L, unknown.length())
        assertFalse(empty.exists())
        assertFalse(File(temporary.root, "legacy.wav").exists())
    }

    @Test fun scanSkipsLiveWriterAndDoesNotOverwriteFormalFile() {
        val target = File(temporary.root, "active.wav")
        val writer = WavFileWriter(target, 16000)
        writer.writeSamples(shortArrayOf(1, 2), 2)
        assertTrue(RecordingRecovery.recover(temporary.root).isEmpty())
        target.writeText("existing")
        assertFalse(writer.closeAndCommit())
        repeat(2) { assertEquals(RecoveryOutcome.CONFLICT, RecordingRecovery.recover(temporary.root).single().outcome) }
        assertEquals("existing", target.readText())
    }

    @Test fun deletionRemovesRecoverySourceBeforeAudioAndCannotResurrect() = runBlocking {
        val target = seed()
        target.writeText("formal") // Simulate a destination collision.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = RecordingRepository(context, RecordingStorage(temporary.root) { emptyList() })
        assertEquals(DeleteOutcome.DELETED, repository.delete(target.absolutePath))
        repeat(2) { assertTrue(repository.scan().isEmpty()) }
        assertFalse(target.exists())
        assertFalse(RecordingRecovery.partial(target).exists())
    }

    @Test fun failedRemnantDeletionKeepsFormalAudio() = runBlocking {
        val target = seed()
        target.writeText("formal")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = RecordingRepository(context, RecordingStorage(temporary.root) { emptyList() },
            deleteFile = { if (it.name.endsWith(".part")) false else it.delete() })
        assertEquals(DeleteOutcome.AUDIO_FAILED, repository.delete(target.absolutePath))
        assertEquals("formal", target.readText())
    }

    @Test fun interruptedMetadataCommitRetriesWithoutDuplicatingAudio() {
        val target = seed()
        val json = File(temporary.root, "${target.name}.json").apply { mkdir() }
        assertEquals(RecoveryOutcome.PENDING, RecordingRecovery.recover(temporary.root).single().outcome)
        assertTrue(target.isFile)
        assertTrue(json.delete())
        assertEquals(RecoveryOutcome.RECOVERED, RecordingRecovery.recover(temporary.root).single().outcome)
        assertEquals("Recovered", RecordingMetadataStore.loadOrCreate(target).closeReason)
        assertTrue(RecordingRecovery.recover(temporary.root).isEmpty())
    }

    @Test fun clearingUnknownRemnantNeverRemovesFormalFile() = runBlocking {
        val target = File(temporary.root, "unknown.wav").apply { writeText("keep") }
        val part = RecordingRecovery.partial(target).apply { writeText("unknown") }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = RecordingRepository(context, RecordingStorage(temporary.root) { emptyList() })
        repository.clearRemnants(listOf(part.absolutePath))
        assertEquals("keep", target.readText())
        assertTrue(RecordingRecovery.recover(temporary.root).isEmpty())
    }
}
