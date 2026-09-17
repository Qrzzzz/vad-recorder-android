package com.qrz.voicetriggerrecorder.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.qrz.voicetriggerrecorder.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RecordingTransferViewModelTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test fun archivePickerSurvivesRecreationAndCancellationClearsSnapshot() {
        val saved = SavedStateHandle()
        val original = RecordingTransferViewModel(application, saved)
        assertTrue(original.beginArchive(listOf("/a.wav", "/b.wav")))
        assertFalse(original.beginExport("other.wav"))
        assertFalse(original.beginArchive(listOf("/c.wav")))
        val restored = RecordingTransferViewModel(application, SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        assertTrue(restored.state.value.awaitingDestination)
        restored.destinationSelected(null)
        assertTrue(restored.state.value.actionsEnabled)
        assertTrue(restored.beginExport("other.wav"))
    }

    @Test fun restoredPickerRequestCanBeCancelledAndRetried() {
        val saved = SavedStateHandle()
        val original = RecordingTransferViewModel(application, saved)
        assertTrue(original.beginExport("original.wav"))
        val restored = RecordingTransferViewModel(application, SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        assertTrue(restored.state.value.awaitingDestination)
        restored.destinationSelected(null)
        assertTrue(restored.state.value.actionsEnabled)
        assertNull(restored.state.value.messageRes)
        assertTrue(restored.beginExport("another.wav"))
    }

    @Test fun pendingPickerRejectsDuplicateExportAndShare() {
        val model = RecordingTransferViewModel(application, SavedStateHandle())
        assertTrue(model.beginExport("first.wav"))
        assertFalse(model.beginExport("second.wav"))
        model.share("second.wav")
        assertTrue(model.state.value.awaitingDestination)
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.shareIntent)
    }

    @Test fun missingPickerReportsFailureClearsPendingAndAllowsRetry() {
        val saved = SavedStateHandle()
        val model = RecordingTransferViewModel(application, saved)
        model.beginExport("first.wav")
        model.launchFailed()
        assertTrue(model.state.value.actionsEnabled)
        assertEquals(R.string.transfer_no_app, model.state.value.messageRes)
        val restored = RecordingTransferViewModel(application, SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        assertFalse(restored.state.value.awaitingDestination)
        model.dismissMessage()
        assertNull(model.state.value.messageRes)
    }

    @Test fun createDocumentUsesWavAndCancellationReturnsNoDestination() {
        val contract = CreateRecordingDocument()
        val intent = contract.createIntent(application, "original.wav")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("audio/wav", intent.type)
        assertEquals("original.wav", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(intent.categories.contains(Intent.CATEGORY_OPENABLE))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent()))
    }
}
