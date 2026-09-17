package com.qrz.voicetriggerrecorder.record

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.qrz.voicetriggerrecorder.ui.RecorderUiState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CaptureLifecycleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun stopBeforeStartupBarrierIsTerminalAndKeepsFirstReason() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val barrier = CompletableDeferred<Unit>()
        var acquisitions = 0
        val engine = AudioCaptureEngine(context, SensitivityPreset.NORMAL_ROOM, {},
            beforeStart = { entered.complete(Unit); barrier.await() },
            inputFactory = { acquisitions++; BlockingInput() })
        val job = async { engine.start() }
        withTimeout(5_000) { entered.await() }
        engine.close(RecordingCloseReason.ManualStop)
        engine.close(RecordingCloseReason.Destroy)
        barrier.complete(Unit)
        assertEquals(RecordingCloseReason.ManualStop, withTimeout(5_000) { job.await() })
        assertEquals(0, acquisitions)
    }

    @Test fun rapidSessionsUnblockReadsAndReleaseOnlyAfterWorkerExitsRead() = runBlocking {
        repeat(20) {
            val input = BlockingInput()
            val engine = AudioCaptureEngine(context, SensitivityPreset.NORMAL_ROOM, {},
                inputFactory = { input })
            val job = async(Dispatchers.Default) { engine.start() }
            assertTrue(input.readEntered.await(5, TimeUnit.SECONDS))
            engine.close(RecordingCloseReason.ManualStop)
            assertEquals(RecordingCloseReason.ManualStop, withTimeout(5_000) { job.await() })
            assertEquals(1, input.starts)
            assertEquals(1, input.releases)
            assertFalse(input.reading.get())
        }
    }

    @Test fun startFailureReleasesAcquiredInput() = runBlocking {
        val input = BlockingInput(failStart = true)
        val engine = AudioCaptureEngine(context, SensitivityPreset.NORMAL_ROOM, {},
            inputFactory = { input })
        val failure = runCatching { engine.start() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, input.releases)
        engine.close(RecordingCloseReason.Destroy)
        assertEquals(1, input.releases)
    }

    @Test fun concurrentFieldUpdatesBothSurviveTheControlledInterleaving() {
        RecordForegroundService.applyUiMutation { RecorderUiState() }
        val readOldState = CountDownLatch(1)
        val resumeMutation = CountDownLatch(1)
        val firstAttempt = AtomicBoolean(true)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit {
                RecordForegroundService.applyUiMutation { current ->
                    if (firstAttempt.getAndSet(false)) {
                        readOldState.countDown()
                        check(resumeMutation.await(5, TimeUnit.SECONDS))
                    }
                    current.copy(savedCount = current.savedCount + 1)
                }
            }
            assertTrue(readOldState.await(5, TimeUnit.SECONDS))
            RecordForegroundService.applyUiMutation { it.copy(autoStopAtMs = 123456789L) }
            resumeMutation.countDown()
            result.get(5, TimeUnit.SECONDS)
            assertEquals(1, RecordForegroundService.uiState.value.savedCount)
            assertEquals(123456789L, RecordForegroundService.uiState.value.autoStopAtMs)
        } finally {
            resumeMutation.countDown()
            worker.shutdownNow()
        }
    }

    private class BlockingInput(private val failStart: Boolean = false) : AudioCaptureEngine.CaptureInput {
        override val config = AudioCaptureEngine.AudioConfig(16000, 1, 16, 320)
        val readEntered = CountDownLatch(1)
        private val stopped = CountDownLatch(1)
        val reading = AtomicBoolean(false)
        var starts = 0
        var releases = 0
        override fun start() { starts++; check(!failStart) }
        override fun read(buffer: ShortArray): Int {
            reading.set(true)
            readEntered.countDown()
            try { check(stopped.await(5, TimeUnit.SECONDS)) }
            finally { reading.set(false) }
            return -1
        }
        override fun stop() { stopped.countDown() }
        override fun release() { check(!reading.get()); releases++ }
    }
}
