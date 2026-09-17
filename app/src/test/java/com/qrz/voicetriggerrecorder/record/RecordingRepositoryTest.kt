package com.qrz.voicetriggerrecorder.record

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RecordingRepositoryTest {
    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var repository: RecordingRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            "voice-recordings"
        )
        dir.deleteRecursively()
        dir.mkdirs()
        repository = RecordingRepository(context)
    }

    @Test
    fun legacyWavWithoutMetadataIsListedFromHeader() {
        val wav = finalizedWav("legacy.wav", sampleRate = 44_100, sampleCount = 44_100)

        val recordings = repository.listRecordings()

        assertEquals(1, recordings.size)
        val recording = recordings.single()
        assertEquals(wav.name, recording.name)
        assertEquals(44_100, recording.sampleRate)
        assertEquals(1_000L, recording.durationMs)
        assertNull(recording.closeReason)
        assertNull(recording.vadEngineName)
        assertNull(recording.speechDurationMs)
        assertTrue(recording.isFinalized)
        assertFalse(recording.isCorrupted)
        assertFalse(File(dir, "legacy.wav.json").exists())
    }

    @Test
    fun metadataValuesArePreservedWhenPresent() {
        val wav = finalizedWav("metadata.wav", sampleRate = 16_000, sampleCount = 16_000)
        RecordingMetadataStore.writeFinalized(
            wavFile = wav,
            createdAt = 100L,
            endedAt = 1_100L,
            sampleRate = 16_000,
            speechDurationMs = 740L,
            closeReason = RecordingCloseReason.ManualStop,
            vadEngineName = "TestVad"
        )

        val sidecar = File(dir, "${wav.name}.json")
        val before = sidecar.readText()
        assertTrue(wav.setLastModified(9_000_000L))
        val recording = repository.listRecordings().single()
        assertEquals(before, sidecar.readText())
        repository.listRecordings()
        assertEquals(before, sidecar.readText())

        assertEquals(100L, recording.createdAt)
        assertEquals(1_100L, recording.endedAt)
        assertEquals(740L, recording.speechDurationMs)
        assertEquals("ManualStop", recording.closeReason)
        assertEquals("TestVad", recording.vadEngineName)
        assertNotNull(recording.durationMs)
    }

    @Test
    fun deleteRecordingRemovesWavAndMetadata() {
        val wav = finalizedWav("delete-me.wav", sampleRate = 16_000, sampleCount = 16_000)
        RecordingMetadataStore.writeFinalized(wav, 100, 1100, 16000, 740, RecordingCloseReason.ManualStop, "TestVad")
        val metadata = File(dir, "delete-me.wav.json")
        assertTrue(metadata.exists())

        assertEquals(DeleteOutcome.DELETED, repository.deleteRecording(wav.absolutePath))

        assertFalse(wav.exists())
        assertFalse(metadata.exists())
    }

    private fun finalizedWav(
        name: String,
        sampleRate: Int,
        sampleCount: Int
    ): File {
        val wav = File(dir, name)
        val writer = WavFileWriter(wav, sampleRate = sampleRate)
        writer.writeSamples(ShortArray(sampleCount) { 1_000 }, sampleCount)
        assertTrue(writer.closeAndCommit())
        return wav
    }

    @Test
    fun missingAndInvalidEndTimesFallBackToWavTime() {
        val wav = finalizedWav("fallback.wav", 16_000, 16_000)
        RecordingMetadataStore.writeFinalized(wav, 100, 1100, 16000, 740, RecordingCloseReason.ManualStop, "TestVad")
        val sidecar = File(dir, "${wav.name}.json")
        listOf<Long?>(null, -1L, 0L).forEach { endedAt ->
            val json = org.json.JSONObject(sidecar.readText())
            json.put("createdAt", 100L)
            if (endedAt == null) json.remove("endedAt") else json.put("endedAt", endedAt)
            sidecar.writeText(json.toString())
            assertEquals(wav.lastModified(), repository.listRecordings().single().endedAt)
        }
    }

    @Test
    fun positiveEndTimeSurvivesWallClockMovingBackwards() {
        val wav = finalizedWav("clock-change.wav", 16_000, 16_000)
        RecordingMetadataStore.writeFinalized(
            wav, 2_000L, 1_000L, 16_000, 1_000L,
            RecordingCloseReason.ManualStop, "TestVad"
        )
        assertEquals(1_000L, repository.listRecordings().single().endedAt)
    }
}
