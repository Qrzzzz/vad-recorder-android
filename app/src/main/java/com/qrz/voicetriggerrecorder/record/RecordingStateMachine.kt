package com.qrz.voicetriggerrecorder.record

import android.content.Context
import android.os.Environment
import android.util.Log
import com.qrz.voicetriggerrecorder.ui.RecorderPhase
import com.qrz.voicetriggerrecorder.ui.RecorderUiStateMutation
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingStateMachine(
    private val context: Context,
    private val config: RecorderConfig,
    private val applyUiMutation: (RecorderUiStateMutation) -> Unit
) {
    companion object {
        private const val TAG = "VoiceTriggerRecorder"
    }

    private enum class InternalState {
        LISTENING,
        RECORDING,
        HANGOVER
    }

    private var state = InternalState.LISTENING
    private var writer: WavFileWriter? = null
    private var currentFile: File? = null
    private var currentFileName: String? = null
    private var speechFrames = 0
    private var silenceFrames = 0
    private var startConfirmFrames = 0
    private val preRoll = RingBuffer(config.preRollFrames)

    val countdownRemainingMs: Long?
        get() = if (state == InternalState.HANGOVER) {
            (config.endSilenceFrames - silenceFrames).coerceAtLeast(0) * config.frameMs.toLong()
        } else {
            null
        }

    fun onFrame(frame: ShortArray, speech: Boolean) {
        when (state) {
            InternalState.LISTENING -> handleListening(frame, speech)
            InternalState.RECORDING -> handleRecording(frame, speech)
            InternalState.HANGOVER -> handleHangover(frame, speech)
        }
    }

    fun closeCurrentFileIfNeeded(forceSave: Boolean = false) {
        closeWriterAndFinalize(forceSave)
        preRoll.clear()
        state = InternalState.LISTENING
        startConfirmFrames = 0
        speechFrames = 0
        silenceFrames = 0
    }

    private fun handleListening(frame: ShortArray, speech: Boolean) {
        preRoll.add(frame)

        if (speech) {
            startConfirmFrames++
        } else {
            startConfirmFrames = 0
        }

        if (startConfirmFrames >= config.startConfirmFrames) {
            val now = Date()
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
            val parent = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: File(context.filesDir, "music")
            val dir = File(parent, "voice-recordings")
            dir.mkdirs()
            val fileName = "voice_${dateStr}.wav"
            currentFile = File(dir, fileName)
            currentFileName = fileName

            writer = WavFileWriter(currentFile!!, config.preferredSampleRate)

            for (preFrame in preRoll.snapshot()) {
                writer?.writeSamples(preFrame, preFrame.size)
            }

            speechFrames = startConfirmFrames
            silenceFrames = 0
            state = InternalState.RECORDING

            Log.i(TAG, "state LISTENING -> RECORDING file=$fileName")
            applyUiMutation { current ->
                current.copy(
                    serviceRunning = true,
                    recorderPhase = RecorderPhase.RECORDING,
                    currentFileName = currentFileName,
                    lastSavedFileName = null,
                    errorMessage = null,
                    countdownRemainingMs = null
                )
            }
        }
    }

    private fun handleRecording(frame: ShortArray, speech: Boolean) {
        if (speech) {
            writer?.writeSamples(frame, frame.size)
            speechFrames++
            silenceFrames = 0
        } else {
            silenceFrames = 1
            if (silenceFrames <= config.tailKeepFrames) {
                writer?.writeSamples(frame, frame.size)
            }
            state = InternalState.HANGOVER
            Log.i(TAG, "state RECORDING -> HANGOVER")
            applyUiMutation { current ->
                current.copy(
                    serviceRunning = true,
                    recorderPhase = RecorderPhase.WAITING_TO_FINISH,
                    currentFileName = currentFileName,
                    countdownRemainingMs = countdownRemainingMs
                )
            }
        }
    }

    private fun handleHangover(frame: ShortArray, speech: Boolean) {
        if (speech) {
            writer?.writeSamples(frame, frame.size)
            speechFrames++
            silenceFrames = 0
            state = InternalState.RECORDING
            Log.i(TAG, "state HANGOVER -> RECORDING")
            applyUiMutation { current ->
                current.copy(
                    serviceRunning = true,
                    recorderPhase = RecorderPhase.RECORDING,
                    currentFileName = currentFileName,
                    lastSavedFileName = null,
                    countdownRemainingMs = null
                )
            }
        } else {
            silenceFrames++
            if (silenceFrames <= config.tailKeepFrames) {
                writer?.writeSamples(frame, frame.size)
            }

            if (silenceFrames >= config.endSilenceFrames) {
                closeWriterAndFinalize(forceSave = true)
                state = InternalState.LISTENING
                startConfirmFrames = 0
                speechFrames = 0
                silenceFrames = 0
                preRoll.clear()
            }
        }
    }

    private fun closeWriterAndFinalize(forceSave: Boolean) {
        val f = currentFile
        val w = writer
        val fileName = currentFileName

        currentFile = null
        currentFileName = null
        writer = null

        if (w != null) {
            w.close()
            val durationMs = speechFrames * config.frameMs.toLong()
            val shouldSave = if (forceSave) {
                speechFrames > 0 && w.totalBytes > 0
            } else {
                f != null && durationMs >= config.minSpeechMs
            }

            if (f != null && shouldSave) {
                Log.i(TAG, "state -> LISTENING saved=true file=$fileName durationMs=$durationMs")
                applyUiMutation { current ->
                    current.copy(
                        serviceRunning = true,
                        recorderPhase = RecorderPhase.SAVED,
                        currentFileName = null,
                        lastSavedFileName = fileName,
                        savedCount = current.savedCount + 1,
                        errorMessage = null,
                        countdownRemainingMs = null
                    )
                }
            } else {
                Log.i(TAG, "discarding too-short file=$fileName durationMs=$durationMs")
                try {
                    f?.delete()
                } catch (_: Exception) {
                }
                applyUiMutation { current ->
                    current.copy(
                        serviceRunning = true,
                        recorderPhase = RecorderPhase.LISTENING,
                        currentFileName = null,
                        lastSavedFileName = null,
                        countdownRemainingMs = null
                    )
                }
            }
        }
    }
}
