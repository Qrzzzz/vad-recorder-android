package com.qrz.voicetriggerrecorder.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrz.voicetriggerrecorder.record.DeleteOutcome
import com.qrz.voicetriggerrecorder.record.RecordingFile
import com.qrz.voicetriggerrecorder.record.RecoveryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryDeleteError(val path: String, val outcome: DeleteOutcome)
data class BatchDeleteResult(val deleted: Int, val protected: Int, val failed: Int)

data class RecordingHistoryState(
    val files: List<RecordingFile> = emptyList(),
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val deleting: Boolean = false,
    val deleteError: HistoryDeleteError? = null,
    val recoveryResults: List<RecoveryResult> = emptyList(),
    val recoveryCleanupFailed: Boolean = false,
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet(),
    val batchResult: BatchDeleteResult? = null,
    val batchRetry: Set<String> = emptySet(),
    val favoriteFailed: Boolean = false
)

/** All requests enter on Main; repository operations are main-safe. */
class RecordingHistoryViewModel(
    private val scan: suspend () -> List<RecordingFile>,
    private val remove: suspend (String) -> DeleteOutcome,
    private val recoveryResults: () -> List<RecoveryResult> = { emptyList() },
    private val clearRemnants: suspend (List<String>) -> Unit = {},
    private val saveFavorite: suspend (String, Boolean) -> Boolean = { _, _ -> false }
) : ViewModel() {
    private val mutableState = MutableStateFlow(RecordingHistoryState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var scanJob: Job? = null

    private fun invalidateScan() {
        generation++
        scanJob?.cancel()
    }

    fun refresh() {
        invalidateScan()
        // Deletion always schedules a fresh scan when it finishes.
        if (state.value.deleting) return
        val request = generation
        mutableState.value = state.value.copy(loading = true, loadFailed = false)
        scanJob = viewModelScope.launch {
            try {
                val files = scan()
                if (request == generation) {
                    mutableState.value = state.value.copy(files = files, loading = false, loadFailed = false,
                        selected = state.value.selected.intersect(files.map { it.path }.toSet()),
                        recoveryResults = recoveryResults())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) {
                    mutableState.value = state.value.copy(loading = false, loadFailed = true)
                }
            }
        }
    }

    fun delete(path: String) {
        if (state.value.deleting) return
        invalidateScan()
        mutableState.value = state.value.copy(deleting = true, loading = false, deleteError = null)
        viewModelScope.launch {
            val outcome = try {
                remove(path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DeleteOutcome.AUDIO_FAILED
            }
            val audioRemoved = outcome == DeleteOutcome.DELETED ||
                outcome == DeleteOutcome.ALREADY_ABSENT || outcome == DeleteOutcome.METADATA_REMAINS
            mutableState.value = state.value.copy(
                files = if (audioRemoved) state.value.files.filterNot { it.path == path } else state.value.files,
                deleting = false,
                deleteError = if (outcome == DeleteOutcome.DELETED || outcome == DeleteOutcome.ALREADY_ABSENT) null
                    else HistoryDeleteError(path, outcome)
            )
            refresh()
        }
    }

    fun dismissDeleteError() {
        mutableState.value = state.value.copy(deleteError = null)
    }

    fun toggleSelectionMode() {
        if (state.value.deleting) return
        mutableState.value = state.value.copy(selecting = !state.value.selecting, selected = emptySet())
    }

    fun toggleSelection(paths: List<String>) {
        if (state.value.deleting) return
        val valid = paths.toSet().intersect(state.value.files.map { it.path }.toSet())
        val selected = state.value.selected
        mutableState.value = state.value.copy(selected =
            if (selected.containsAll(valid)) selected - valid else selected + valid)
    }

    fun favorite(path: String, value: Boolean) {
        if (state.value.deleting) return
        invalidateScan()
        mutableState.value = state.value.copy(deleting = true, loading = false, favoriteFailed = false)
        viewModelScope.launch {
            val success = try { saveFavorite(path, value) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            mutableState.value = state.value.copy(deleting = false, favoriteFailed = !success,
                files = if (success) state.value.files.map { if (it.path == path) it.copy(isFavorite = value) else it }
                    else state.value.files)
            refresh()
        }
    }

    fun deleteSelected(paths: Set<String>) {
        if (state.value.deleting || paths.isEmpty()) return
        invalidateScan()
        mutableState.value = state.value.copy(deleting = true, loading = false, batchResult = null)
        viewModelScope.launch {
            var deleted = 0
            var protected = 0
            var failed = 0
            val removed = mutableSetOf<String>()
            val retry = mutableSetOf<String>()
            for (path in paths) {
                val outcome = try { remove(path) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { DeleteOutcome.AUDIO_FAILED }
                when (outcome) {
                    DeleteOutcome.DELETED, DeleteOutcome.ALREADY_ABSENT -> { deleted++; removed += path }
                    DeleteOutcome.PROTECTED -> protected++
                    DeleteOutcome.METADATA_REMAINS -> { failed++; removed += path; retry += path }
                    else -> { failed++; retry += path }
                }
            }
            mutableState.value = state.value.copy(deleting = false,
                files = state.value.files.filterNot { it.path in removed },
                selected = state.value.selected - removed,
                batchRetry = retry,
                batchResult = BatchDeleteResult(deleted, protected, failed))
            refresh()
        }
    }

    fun clearRecoveryRemnants() {
        if (state.value.deleting) return
        val paths = state.value.recoveryResults.filter {
            it.outcome != com.qrz.voicetriggerrecorder.record.RecoveryOutcome.RECOVERED
        }.map { it.path }
        invalidateScan()
        mutableState.value = state.value.copy(deleting = true, loading = false, recoveryCleanupFailed = false)
        viewModelScope.launch {
            try {
                clearRemnants(paths)
                mutableState.value = state.value.copy(deleting = false)
                refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.value = state.value.copy(deleting = false, recoveryCleanupFailed = true)
            }
        }
    }

    override fun onCleared() {
        invalidateScan()
    }
}
