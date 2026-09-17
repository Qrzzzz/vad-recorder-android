package com.qrz.voicetriggerrecorder.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrz.voicetriggerrecorder.record.DeleteOutcome
import com.qrz.voicetriggerrecorder.record.RecordingFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryDeleteError(val path: String, val outcome: DeleteOutcome)

data class RecordingHistoryState(
    val files: List<RecordingFile> = emptyList(),
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val deleting: Boolean = false,
    val deleteError: HistoryDeleteError? = null
)

/** All requests enter on Main; repository operations are main-safe. */
class RecordingHistoryViewModel(
    private val scan: suspend () -> List<RecordingFile>,
    private val remove: suspend (String) -> DeleteOutcome
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
                    mutableState.value = state.value.copy(files = files, loading = false, loadFailed = false)
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

    override fun onCleared() {
        invalidateScan()
    }
}
