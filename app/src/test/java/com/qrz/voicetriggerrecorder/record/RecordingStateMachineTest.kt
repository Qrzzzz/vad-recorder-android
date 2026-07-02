package com.qrz.voicetriggerrecorder.record

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.qrz.voicetriggerrecorder.ui.RecorderUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RecordingStateMachineTest {
    private lateinit var context: Context
    private lateinit var dir: File
    private var uiState = RecorderUiState()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            "voice-recordings"
        )
        dir.deleteRecursively()
        dir.mkdirs()
        uiState = RecorderUiState()
    }

    @Test
    fun shortNoiseAfterTriggerDoesNotSaveWav() {
        val machine = stateMachine(endSilenceMs = 60, minSpeechMs = 100)

        repeat(2) { machine.onFrame(frame(), speech = true) }
        repeat(3) { machine.onFrame(frame(0), speech = false) }

        assertEquals(emptyList<File>(), wavFiles())
        assertEquals(emptyList<File>(), partFiles())
    }

    @Test
    fun normalSpeechSavesAfterEndSilence() {
        val machine = stateMachine(endSilenceMs = 60, minSpeechMs = 100)

        repeat(6) { machine.onFrame(frame(), speech = true) }
        repeat(3) { machine.onFrame(frame(0), speech = false) }

        assertEquals(1, wavFiles().size)
        assertEquals(emptyList<File>(), partFiles())
        assertEquals(1, uiState.savedCount)
        assertEquals("EndSilence", RecordingMetadataStore.loadOrCreate(wavFiles().single()).closeReason)
    }

    @Test
    fun speechAfterThirtySecondsOfSilenceSaves() {
        val machine = stateMachine(endSilenceMs = 30_000, minSpeechMs = 100)

        repeat(6) { machine.onFrame(frame(), speech = true) }
        repeat(1_500) { machine.onFrame(frame(0), speech = false) }

        assertEquals(1, wavFiles().size)
        assertEquals("EndSilence", RecordingMetadataStore.loadOrCreate(wavFiles().single()).closeReason)
    }

    @Test
    fun manualStopSavesValidActiveAudio() {
        val machine = stateMachine(endSilenceMs = 60, minSpeechMs = 800)

        repeat(2) { machine.onFrame(frame(), speech = true) }
        machine.closeCurrentFileIfNeeded(RecordingCloseReason.ManualStop)

        assertEquals(1, wavFiles().size)
        assertEquals("ManualStop", RecordingMetadataStore.loadOrCreate(wavFiles().single()).closeReason)
    }

    @Test
    fun serviceStopDoesNotSaveShortNoise() {
        val machine = stateMachine(endSilenceMs = 60, minSpeechMs = 800)

        repeat(2) { machine.onFrame(frame(), speech = true) }
        machine.closeCurrentFileIfNeeded(RecordingCloseReason.ServiceStop)

        assertEquals(emptyList<File>(), wavFiles())
        assertEquals(emptyList<File>(), partFiles())
    }

    @Test
    fun readErrorFinalizesValidActiveAudio() {
        val machine = stateMachine(endSilenceMs = 60, minSpeechMs = 100)

        repeat(6) { machine.onFrame(frame(), speech = true) }
        machine.closeCurrentFileIfNeeded(RecordingCloseReason.ReadError)

        assertEquals(1, wavFiles().size)
        assertEquals("ReadError", RecordingMetadataStore.loadOrCreate(wavFiles().single()).closeReason)
    }

    @Test
    fun readErrorDoesNotSaveShortNoise() {
        val machine = stateMachine(endSilenceMs = 60, minSpeechMs = 800)

        repeat(2) { machine.onFrame(frame(), speech = true) }
        machine.closeCurrentFileIfNeeded(RecordingCloseReason.ReadError)

        assertEquals(emptyList<File>(), wavFiles())
        assertEquals(emptyList<File>(), partFiles())
    }

    @Test
    fun destroyRemovesActivePartialFile() {
        val machine = stateMachine(endSilenceMs = 60, minSpeechMs = 100)

        repeat(2) { machine.onFrame(frame(), speech = true) }
        assertTrue(partFiles().isNotEmpty())

        machine.closeCurrentFileIfNeeded(RecordingCloseReason.Destroy)

        assertEquals(emptyList<File>(), wavFiles())
        assertEquals(emptyList<File>(), partFiles())
    }

    @Test
    fun cleanupStalePartFilesDeletesOldPartialsOnly() {
        val oldPart = File(dir, "old.wav.part").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(1_000L)
        }
        val freshPart = File(dir, "fresh.wav.part").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(10_000L)
        }

        val deleted = WavFileWriter.cleanupStalePartFiles(
            directory = dir,
            olderThanMs = 5_000L,
            nowMs = 10_000L
        )

        assertEquals(1, deleted)
        assertFalse(oldPart.exists())
        assertTrue(freshPart.exists())
    }

    private fun stateMachine(
        endSilenceMs: Int,
        minSpeechMs: Int
    ): RecordingStateMachine {
        val config = RecorderConfig(
            preferredSampleRate = 16_000,
            frameMs = 20,
            preRollMs = 40,
            endSilenceMs = endSilenceMs,
            minSpeechMs = minSpeechMs,
            tailKeepMs = 40,
            startConfirmMs = 40
        )
        return RecordingStateMachine(
            context = context,
            config = config,
            vadEngineName = "TestVad",
            applyUiMutation = { mutation -> uiState = mutation(uiState) }
        )
    }

    private fun frame(amplitude: Short = 1_000, size: Int = 320): ShortArray {
        return ShortArray(size) { index ->
            if (index % 2 == 0) amplitude else (-amplitude).toShort()
        }
    }

    private fun wavFiles(): List<File> {
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".wav") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun partFiles(): List<File> {
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".wav.part") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }
}
