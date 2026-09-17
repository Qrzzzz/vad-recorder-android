package com.qrz.voicetriggerrecorder.ui

import androidx.lifecycle.ViewModelStore
import com.qrz.voicetriggerrecorder.record.DeleteOutcome
import com.qrz.voicetriggerrecorder.record.RecordingFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val clip = RecordingFile("a.wav", "/a.wav", 48, 1, 1)
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }
    private fun model(
        scan: suspend () -> List<RecordingFile>,
        remove: suspend (String) -> DeleteOutcome = { DeleteOutcome.DELETED }
    ) = RecordingHistoryViewModel(scan, remove).also { store.put("history", it) }

    @Test fun failedRemnantCleanupKeepsResultsAndAllowsRetry() = runTest(dispatcher) {
        val remnant = com.qrz.voicetriggerrecorder.record.RecoveryResult("/a.wav.part",
            com.qrz.voicetriggerrecorder.record.RecoveryOutcome.UNKNOWN)
        var remaining = listOf(remnant)
        var attempts = 0
        val vm = RecordingHistoryViewModel({ listOf(clip) }, { DeleteOutcome.DELETED },
            { remaining }, { paths ->
                assertEquals(listOf(remnant.path), paths)
                if (attempts++ == 0) throw java.io.IOException("unavailable")
                remaining = emptyList()
            }).also { store.put("history", it) }
        vm.refresh(); advanceUntilIdle()
        vm.clearRecoveryRemnants(); advanceUntilIdle()
        assertTrue(vm.state.value.recoveryCleanupFailed)
        assertFalse(vm.state.value.deleting)
        assertFalse(vm.state.value.loading)
        assertEquals(listOf(clip), vm.state.value.files)
        assertEquals(listOf(remnant), vm.state.value.recoveryResults)
        vm.clearRecoveryRemnants(); advanceUntilIdle()
        assertFalse(vm.state.value.recoveryCleanupFailed)
        assertTrue(vm.state.value.recoveryResults.isEmpty())
        assertEquals(listOf(clip), vm.state.value.files)
    }

    @Test fun latestRefreshWinsEvenWhenOldScanIgnoresCancellation() = runTest(dispatcher) {
        val old = CompletableDeferred<List<RecordingFile>>()
        var calls = 0
        val vm = model({ if (calls++ == 0) withContext(NonCancellable) { old.await() } else emptyList() })
        vm.refresh(); runCurrent()
        vm.refresh(); runCurrent()
        old.complete(listOf(clip)); advanceUntilIdle()
        assertTrue(vm.state.value.files.isEmpty())
        assertFalse(vm.state.value.loading)
    }

    @Test fun deleteAndRefreshCannotResurrectAnOldScan() = runTest(dispatcher) {
        val old = CompletableDeferred<List<RecordingFile>>()
        val deletion = CompletableDeferred<DeleteOutcome>()
        var calls = 0
        val vm = model({
            when (calls++) {
                0 -> listOf(clip)
                1 -> withContext(NonCancellable) { old.await() }
                else -> emptyList()
            }
        }, { deletion.await() })
        vm.refresh(); runCurrent()
        vm.refresh(); runCurrent()
        vm.delete(clip.path); runCurrent()
        vm.refresh(); vm.refresh(); runCurrent()
        assertEquals(2, calls)
        assertTrue(vm.state.value.deleting)
        deletion.complete(DeleteOutcome.DELETED); runCurrent()
        old.complete(listOf(clip)); advanceUntilIdle()
        assertEquals(3, calls)
        assertTrue(vm.state.value.files.isEmpty())
        assertFalse(vm.state.value.deleting)
    }

    @Test fun failedRefreshRetainsUsableFilesAndCanRetryToEmpty() = runTest(dispatcher) {
        var calls = 0
        val vm = model({ when (calls++) { 0 -> listOf(clip); 1 -> error("storage"); else -> emptyList() } })
        vm.refresh(); runCurrent()
        vm.refresh(); runCurrent()
        assertEquals(listOf(clip), vm.state.value.files)
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.loading)
        vm.refresh(); runCurrent()
        assertTrue(vm.state.value.files.isEmpty())
        assertFalse(vm.state.value.loadFailed)
    }

    @Test fun partialDeleteRemovesAudioEvenIfRescanFailsAndAllowsRetry() = runTest(dispatcher) {
        var failScan = false
        var outcome = DeleteOutcome.METADATA_REMAINS
        val vm = model({ if (failScan) error("scan failed") else listOf(clip) }, { outcome })
        vm.refresh(); runCurrent(); failScan = true
        vm.delete(clip.path); runCurrent()
        assertTrue(vm.state.value.files.isEmpty())
        assertEquals(HistoryDeleteError(clip.path, outcome), vm.state.value.deleteError)
        outcome = DeleteOutcome.ALREADY_ABSENT
        vm.delete(clip.path); runCurrent()
        assertNull(vm.state.value.deleteError)
    }

    @Test fun failedDeleteRetainsAudioAndReportsError() = runTest(dispatcher) {
        val vm = model({ listOf(clip) }, { error("disk error") })
        vm.refresh(); runCurrent(); vm.delete(clip.path); runCurrent()
        assertEquals(listOf(clip), vm.state.value.files)
        assertEquals(DeleteOutcome.AUDIO_FAILED, vm.state.value.deleteError?.outcome)
        assertFalse(vm.state.value.deleting)
    }

    @Test fun clearedViewModelRejectsLateResultsAndNewInstanceCanLoad() = runTest(dispatcher) {
        val old = CompletableDeferred<List<RecordingFile>>()
        val vm = model({ withContext(NonCancellable) { old.await() } })
        vm.refresh(); runCurrent(); store.clear()
        old.complete(listOf(clip)); advanceUntilIdle()
        assertTrue(vm.state.value.files.isEmpty())
        val replacement = model({ listOf(clip) })
        replacement.refresh(); runCurrent()
        assertEquals(listOf(clip), replacement.state.value.files)
    }
}
