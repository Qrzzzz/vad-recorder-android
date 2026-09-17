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

    @Test fun batchRetainsFailuresAndFavoritesAndRejectsConcurrentMutations() = runTest(dispatcher) {
        val favorite = clip.copy(path = "/favorite.wav", isFavorite = true)
        val failed = clip.copy(path = "/failed.wav")
        var files = listOf(clip, favorite, failed)
        val barrier = CompletableDeferred<Unit>()
        val vm = model({ files }, { path ->
            barrier.await()
            when (path) {
                clip.path -> { files = files.filterNot { it.path == path }; DeleteOutcome.DELETED }
                favorite.path -> DeleteOutcome.PROTECTED
                else -> DeleteOutcome.AUDIO_FAILED
            }
        })
        vm.refresh(); advanceUntilIdle()
        vm.toggleSelectionMode(); vm.toggleSelection(files.map { it.path })
        vm.deleteSelected(vm.state.value.selected); runCurrent()
        vm.toggleSelectionMode(); vm.toggleSelection(listOf(clip.path)); vm.refresh()
        assertTrue(vm.state.value.deleting)
        assertEquals(3, vm.state.value.selected.size)
        barrier.complete(Unit); advanceUntilIdle()
        assertEquals(BatchDeleteResult(1, 1, 1), vm.state.value.batchResult)
        assertEquals(setOf(favorite.path, failed.path), vm.state.value.selected)
        assertEquals(2, vm.state.value.files.size)
    }

    @Test fun refreshPrunesMissingSelectionAndNewClipsAreNotAutomaticallySelected() = runTest(dispatcher) {
        var files = listOf(clip)
        val vm = model({ files })
        vm.refresh(); advanceUntilIdle()
        vm.toggleSelectionMode(); vm.toggleSelection(listOf(clip.path))
        files = listOf(clip.copy(path = "/new.wav"))
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.state.value.selected.isEmpty())
        assertTrue(vm.state.value.selecting)
    }

    @Test fun batchMetadataCleanupCanBeRetriedAfterAudioDisappearsFromList() = runTest(dispatcher) {
        var files = listOf(clip)
        var calls = 0
        val vm = model({ files }, {
            files = emptyList()
            if (calls++ == 0) DeleteOutcome.METADATA_REMAINS else DeleteOutcome.ALREADY_ABSENT
        })
        vm.refresh(); advanceUntilIdle()
        vm.deleteSelected(setOf(clip.path)); advanceUntilIdle()
        assertTrue(vm.state.value.files.isEmpty())
        assertEquals(setOf(clip.path), vm.state.value.batchRetry)
        assertEquals(BatchDeleteResult(0, 0, 1), vm.state.value.batchResult)
        vm.deleteSelected(vm.state.value.batchRetry); advanceUntilIdle()
        assertTrue(vm.state.value.batchRetry.isEmpty())
        assertEquals(BatchDeleteResult(1, 0, 0), vm.state.value.batchResult)
    }

    @Test fun favoriteFailureKeepsOriginalStateAndAllowsRetry() = runTest(dispatcher) {
        var success = false
        var file = clip
        val vm = RecordingHistoryViewModel({ listOf(file) }, { DeleteOutcome.DELETED },
            saveFavorite = { _, value -> if (success) file = file.copy(isFavorite = value); success })
        store.put("history", vm)
        vm.refresh(); advanceUntilIdle()
        vm.favorite(clip.path, true); advanceUntilIdle()
        assertTrue(vm.state.value.favoriteFailed)
        assertFalse(vm.state.value.files.single().isFavorite)
        success = true
        vm.favorite(clip.path, true); advanceUntilIdle()
        assertFalse(vm.state.value.favoriteFailed)
        assertTrue(vm.state.value.files.single().isFavorite)
    }

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
