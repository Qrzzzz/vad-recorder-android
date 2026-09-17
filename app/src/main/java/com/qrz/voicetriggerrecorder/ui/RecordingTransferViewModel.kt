package com.qrz.voicetriggerrecorder.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.qrz.voicetriggerrecorder.R
import com.qrz.voicetriggerrecorder.record.ExportOutcome
import com.qrz.voicetriggerrecorder.record.RecordingTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecordingTransferState(
    val busy: Boolean = false,
    val awaitingDestination: Boolean = false,
    val shareIntent: Intent? = null,
    val messageRes: Int? = null
) {
    val actionsEnabled get() = !busy && !awaitingDestination && shareIntent == null
}

class RecordingTransferViewModel(application: Application, private val saved: SavedStateHandle) :
    AndroidViewModel(application) {
    private val transfer = RecordingTransfer(application)
    private val mutableState = MutableStateFlow(
        RecordingTransferState(awaitingDestination = saved.get<String>(PENDING_FILE) != null ||
            saved.get<ArrayList<String>>(PENDING_ARCHIVE) != null)
    )
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { runCatching { transfer.pruneExpiredShares() } }
    }

    fun beginExport(fileName: String): Boolean {
        if (!state.value.actionsEnabled) return false
        saved[PENDING_FILE] = fileName
        mutableState.value = RecordingTransferState(awaitingDestination = true)
        return true
    }

    fun destinationSelected(uri: Uri?) {
        val fileName = saved.get<String>(PENDING_FILE)
        val archive = saved.get<ArrayList<String>>(PENDING_ARCHIVE)
        if (fileName == null && archive == null) return
        saved[PENDING_FILE] = null
        saved[PENDING_ARCHIVE] = null
        if (uri == null) {
            mutableState.value = RecordingTransferState()
            return
        }
        mutableState.value = RecordingTransferState(busy = true)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                if (archive != null) transfer.exportArchive(archive, uri) { ensureActive() }
                else transfer.export(requireNotNull(fileName), uri) { ensureActive() }
            }
            mutableState.value = RecordingTransferState(messageRes = when (outcome) {
                ExportOutcome.SAVED -> R.string.export_saved
                ExportOutcome.SOURCE_UNAVAILABLE -> R.string.transfer_source_unavailable
                ExportOutcome.FAILED -> R.string.export_failed
                ExportOutcome.INCOMPLETE_DOCUMENT -> R.string.export_incomplete_document
            })
        }
    }

    fun share(fileName: String) {
        if (!state.value.actionsEnabled) return
        mutableState.value = RecordingTransferState(busy = true)
        viewModelScope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { transfer.prepareShare(fileName) { ensureActive() } }
                mutableState.value = RecordingTransferState(shareIntent = intent)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = RecordingTransferState(messageRes = R.string.share_failed)
            }
        }
    }

    fun beginArchive(paths: List<String>): Boolean {
        if (!state.value.actionsEnabled || paths.isEmpty()) return false
        saved[PENDING_ARCHIVE] = ArrayList(paths.distinct())
        mutableState.value = RecordingTransferState(awaitingDestination = true)
        return true
    }

    fun shareLaunched() { mutableState.value = RecordingTransferState() }

    fun launchFailed() {
        saved[PENDING_FILE] = null
        saved[PENDING_ARCHIVE] = null
        mutableState.value = RecordingTransferState(messageRes = R.string.transfer_no_app)
    }

    fun dismissMessage() { mutableState.value = state.value.copy(messageRes = null) }

    private companion object {
        const val PENDING_FILE = "exportFileName"
        const val PENDING_ARCHIVE = "exportArchivePaths"
    }
}
