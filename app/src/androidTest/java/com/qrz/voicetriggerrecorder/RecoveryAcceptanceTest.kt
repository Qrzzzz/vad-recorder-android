package com.qrz.voicetriggerrecorder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qrz.voicetriggerrecorder.record.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run the two methods in separate instrumentation invocations, with force-stop between them. */
@RunWith(AndroidJUnit4::class)
class RecoveryAcceptanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory get() = RecordingStorage(context).writeDirectory()
    private val target get() = File(directory, "acceptance-interrupted-v25.wav")

    @Test fun seedInterruptedFragment() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("recoveryStage") == "seed")
        check(context.packageName.endsWith(".acceptance"))
        directory.mkdirs()
        check(!target.exists() && !File(directory, "${target.name}.part").exists())
        val writer = WavFileWriter(target, 44100)
        assertTrue(writer.writeSamples(ShortArray(4410) { 1234 }, 4410))
        assertFalse(target.exists())
        // Intentionally leave the writer open. The host ends this process before recovery.
        File(directory, "acceptance-unknown-v25.wav.part").writeBytes(ByteArray(128))
    }

    @Test fun recoverAfterProcessRestart() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("recoveryStage") == "recover")
        check(context.packageName.endsWith(".acceptance"))
        val repository = RecordingRepository(context)
        try {
            val recovered = repository.scan().single { it.name == target.name }
            assertEquals(44100, recovered.sampleRate)
            assertEquals("Recovered", recovered.closeReason)
            assertTrue(recovered.isFinalized)
            assertEquals(8864L, recovered.sizeBytes)
            assertTrue(repository.recoveryResults.any { it.outcome == RecoveryOutcome.UNKNOWN })
            repeat(3) { assertEquals(1, repository.scan().count { it.name == target.name }) }
            assertEquals(DeleteOutcome.DELETED, repository.delete(target.absolutePath))
            assertFalse(repository.scan().any { it.name == target.name })
        } finally {
            listOf(target.name, "acceptance-unknown-v25.wav").forEach { name ->
                listOf("", ".part", ".recovery", ".json").forEach { suffix -> File(directory, name + suffix).delete() }
            }
        }
    }
}
