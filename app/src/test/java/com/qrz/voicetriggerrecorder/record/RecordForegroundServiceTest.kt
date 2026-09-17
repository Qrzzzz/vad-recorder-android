package com.qrz.voicetriggerrecorder.record

import android.content.Intent
import com.qrz.voicetriggerrecorder.app.AppLanguage
import com.qrz.voicetriggerrecorder.ui.PlaybackInterlock
import com.qrz.voicetriggerrecorder.ui.RecorderUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordForegroundServiceTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var service: RecordForegroundService
    private val engines = mutableListOf<AudioCaptureEngine>()
    private val barriers = mutableListOf<CompletableDeferred<Unit>>()
    private val entered = mutableListOf<CompletableDeferred<Unit>>()
    private var elapsed = 100_000L
    private var wall = 1_700_000_000_000L
    private var acquisitions = 0

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        RecordForegroundService.applyUiMutation { RecorderUiState() }
        service = Robolectric.buildService(RecordForegroundService::class.java).create().get()
        service.elapsedClock = { elapsed }
        service.wallClock = { wall }
        RecorderPreferences(service).saveAutoStopHours(0)
        service.engineFactory = { sink ->
            val barrier = CompletableDeferred<Unit>().also { barriers += it }
            val arrival = CompletableDeferred<Unit>().also { entered += it }
            AudioCaptureEngine(service, SensitivityPreset.NORMAL_ROOM, sink,
                beforeStart = {
                    // Simulate delayed initialization which cannot be cancelled until released.
                    withContext(NonCancellable) { arrival.complete(Unit); barrier.await() }
                }, inputFactory = { acquisitions++; error("Stopped session acquired input") }
            ).also { engines += it }
        }
    }

    @After fun teardown() {
        command(RecordForegroundService.ACTION_STOP)
        barriers.forEach { it.complete(Unit) }
        pumpUntil { !RecordForegroundService.uiState.value.serviceRunning }
        service.onDestroy()
        dispatcher.scheduler.runCurrent()
        PlaybackInterlock.shared.unblock()
        Dispatchers.resetMain()
    }

    @Test fun stopDuringStartupThenRestartRejectsOldCallbacks() {
        startAndAwaitBarrier()
        val old = engines.single()
        command(RecordForegroundService.ACTION_STOP)
        command(RecordForegroundService.ACTION_STOP)
        command(RecordForegroundService.ACTION_START)
        assertEquals(1, engines.size)
        barriers[0].complete(Unit)
        pumpUntil { engines.size == 2 && entered[1].isCompleted }
        service.applySessionUiMutation(old) { it.copy(serviceRunning = false, errorMessage = "old") }
        dispatcher.scheduler.runCurrent()
        assertTrue(RecordForegroundService.uiState.value.serviceRunning)
        assertNull(RecordForegroundService.uiState.value.errorMessage)
        command(RecordForegroundService.ACTION_STOP)
        barriers[1].complete(Unit)
        pumpUntil { !RecordForegroundService.uiState.value.serviceRunning }
        assertEquals(0, acquisitions)
        assertFalse(PlaybackInterlock.shared.blocked.value)
    }

    @Test fun lastStopCancelsQueuedRestart() {
        startAndAwaitBarrier()
        command(RecordForegroundService.ACTION_STOP)
        command(RecordForegroundService.ACTION_START)
        command(RecordForegroundService.ACTION_STOP)
        barriers[0].complete(Unit)
        pumpUntil { !RecordForegroundService.uiState.value.serviceRunning }
        assertEquals(1, engines.size)
        assertEquals(0, acquisitions)
    }

    @Test fun replacementServiceWaitsForDestroyedWorkerAndIgnoresItsCallbacks() {
        startAndAwaitBarrier()
        val oldService = service
        val oldEngine = engines.single()
        val factory = service.engineFactory
        service.onDestroy()
        service = Robolectric.buildService(RecordForegroundService::class.java).create().get()
        service.engineFactory = factory
        command(RecordForegroundService.ACTION_START)
        assertEquals(2, engines.size)
        assertFalse(entered[1].isCompleted)
        barriers[0].complete(Unit)
        pumpUntil { entered[1].isCompleted }
        oldService.applySessionUiMutation(oldEngine) { it.copy(errorMessage = "retired") }
        dispatcher.scheduler.runCurrent()
        assertTrue(RecordForegroundService.uiState.value.serviceRunning)
        assertNull(RecordForegroundService.uiState.value.errorMessage)
        assertEquals(0, acquisitions)
    }

    @Test fun startupFailureRemainsVisibleAfterStopAndDestroy() {
        service.engineFactory = { sink ->
            AudioCaptureEngine(service, SensitivityPreset.NORMAL_ROOM, sink,
                inputFactory = { error("microphone unavailable") })
        }
        command(RecordForegroundService.ACTION_START)
        pumpUntil { !RecordForegroundService.uiState.value.serviceRunning }
        assertEquals("microphone unavailable", RecordForegroundService.uiState.value.errorMessage)
        command(RecordForegroundService.ACTION_STOP)
        service.onDestroy()
        assertEquals("microphone unavailable", RecordForegroundService.uiState.value.errorMessage)
        assertFalse(PlaybackInterlock.shared.blocked.value)
    }

    @Test fun wallClockAndLanguageRefreshKeepDurationAndSettingsKeepSessionOrigin() {
        RecorderPreferences(service).saveAutoStopHours(4)
        startAndAwaitBarrier()
        elapsed += 3_600_000L
        for (clockJump in listOf(7_200_000L, -14_400_000L, 0L)) {
            wall += clockJump
            command(RecordForegroundService.ACTION_REFRESH_SETTINGS)
            assertEquals(wall + 3 * 3_600_000L, RecordForegroundService.uiState.value.autoStopAtMs)
            assertTrue(RecordForegroundService.uiState.value.serviceRunning)
        }
        for (language in AppLanguage.entries) {
            RecorderPreferences(service).saveAppLanguage(language)
            command(RecordForegroundService.ACTION_REFRESH_SETTINGS)
            assertEquals(wall + 3 * 3_600_000L, RecordForegroundService.uiState.value.autoStopAtMs)
        }
        RecorderPreferences(service).saveAutoStopHours(0)
        command(RecordForegroundService.ACTION_REFRESH_SETTINGS)
        assertNull(RecordForegroundService.uiState.value.autoStopAtMs)
        RecorderPreferences(service).saveAutoStopHours(6)
        command(RecordForegroundService.ACTION_REFRESH_SETTINGS)
        assertEquals(wall + 5 * 3_600_000L, RecordForegroundService.uiState.value.autoStopAtMs)
        RecorderPreferences(service).saveAutoStopHours(1)
        command(RecordForegroundService.ACTION_REFRESH_SETTINGS)
        barriers[0].complete(Unit)
        pumpUntil { !RecordForegroundService.uiState.value.serviceRunning }
    }

    @Test fun elapsedDeadlineIsRecheckedAfterDeviceSleepWithoutSettingsRefresh() {
        RecorderPreferences(service).saveAutoStopHours(1)
        startAndAwaitBarrier()
        elapsed += 3_600_001L
        dispatcher.scheduler.advanceTimeBy(1_001)
        barriers[0].complete(Unit)
        pumpUntil { !RecordForegroundService.uiState.value.serviceRunning }
        assertEquals(0, acquisitions)
    }

    private fun startAndAwaitBarrier() {
        command(RecordForegroundService.ACTION_START)
        pumpUntil { entered.lastOrNull()?.isCompleted == true }
        assertTrue(PlaybackInterlock.shared.blocked.value)
    }

    private fun command(action: String) {
        service.onStartCommand(Intent(action), 0, 1)
        dispatcher.scheduler.runCurrent()
    }

    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            dispatcher.scheduler.runCurrent()
            Thread.sleep(2)
        }
        dispatcher.scheduler.runCurrent()
        assertTrue("Service did not settle within 5 seconds", condition())
    }
}
