package com.qrz.voicetriggerrecorder.record

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import com.qrz.voicetriggerrecorder.ui.RecorderUiState
import com.qrz.voicetriggerrecorder.ui.RecorderPhase
import com.qrz.voicetriggerrecorder.ui.formatRecorderState
import com.qrz.voicetriggerrecorder.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30])
class InputSilenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun zeroFramesDoNotInferRestrictionAndPositiveReadsDoNotClearPlatformState() = runBlocking {
        var state = RecorderUiState(serviceRunning = true, recorderPhase = RecorderPhase.LISTENING)
        lateinit var listener: (Boolean?) -> Unit
        lateinit var engine: AudioCaptureEngine
        var reads = 0
        var released = false
        val input = object : AudioCaptureEngine.CaptureInput {
            override val config = AudioCaptureEngine.AudioConfig(16000, 1, 16, 320)
            override fun observeSilence(callback: (Boolean?) -> Unit) { listener = callback }
            override fun start() = Unit
            override fun read(buffer: ShortArray): Int {
                when (reads++) {
                    0 -> assertNull(state.inputSilenced)
                    1 -> { assertNull(state.inputSilenced); listener(true) }
                    2 -> {
                        assertEquals(true, state.inputSilenced)
                        assertEquals(context.getString(R.string.input_silenced), formatRecorderState(context, state))
                        listener(false)
                    }
                    3 -> {
                        assertEquals(false, state.inputSilenced)
                        assertEquals(context.getString(R.string.recorder_state_listening), formatRecorderState(context, state))
                        engine.stop()
                    }
                }
                return buffer.size // All-zero audio, successful reads.
            }
            override fun stop() = Unit
            override fun release() { released = true }
        }
        engine = AudioCaptureEngine(context, SensitivityPreset.NORMAL_ROOM, { state = it(state) }, inputFactory = { input })
        engine.start()
        assertTrue(released)
        assertNull(state.errorMessage)
        assertNull(state.inputSilenced)
        listener(true) // A callback already queued by the old session must be ignored.
        assertNull(state.inputSilenced)
    }

    @Test @Config(sdk = [26, 28]) fun oldApiNeverTouchesNewPlatformMethods() {
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 4096)
        var state: Boolean? = true
        val detach = InputSilenceMonitor.attach(record) { state = it }
        InputSilenceMonitor.refresh(record) { state = it }
        assertNull(state)
        detach()
        record.release()
    }
}
