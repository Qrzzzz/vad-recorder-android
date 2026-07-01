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
import java.util.concurrent.TimeUnit

class RecordingStateMachine(
    private val context: Context,
    private val config: RecorderConfig,
    private val vadEngineName: String = "RuleBasedVadEngine",
    private val applyUiMutation: (RecorderUiStateMutation) -> Unit
) {
    companion object {
        private const val TAG = "VoiceTriggerRecorder"
        private val DEFAULT_STALE_PART_AGE_MS = TimeUnit.HOURS.toMillis(6)

        fun cleanupStalePartialFiles(
            context: Context,
            olderThanMs: Long = DEFAULT_STALE_PART_AGE_MS
        ): Int {
            return WavFileWriter.cleanupStalePartFiles(recordingsDir(context), olderThanMs)
        }

        private fun recordingsDir(context: Context): File {
            return File(
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                "voice-recordings"
            )
        }
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
    private var currentStartedAtMs: Long = 0L
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

    fun closeCurrentFileIfNeeded(reason: RecordingCloseReason = RecordingCloseReason.ServiceStop) {
        closeWriterAndFinalize(reason)
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
            val dir = recordingsDir(context)
            dir.mkdirs()
            val fileName = "voice_${dateStr}.wav"
            currentFile = File(dir, fileName)
            currentFileName = fileName
            currentStartedAtMs = now.time

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
                closeWriterAndFinalize(RecordingCloseReason.EndSilence)
                state = InternalState.LISTENING
                startConfirmFrames = 0
                speechFrames = 0
                silenceFrames = 0
                preRoll.clear()
            }
        }
    }

    private fun closeWriterAndFinalize(reason: RecordingCloseReason) {
        val f = currentFile
        val w = writer
        val fileName = currentFileName
        val startedAtMs = currentStartedAtMs
        val speechDurationMs = speechFrames * config.frameMs.toLong()
        val audioBytes = w?.totalBytes ?: 0L

        currentFile = null
        currentFileName = null
        currentStartedAtMs = 0L
        writer = null

        if (w != null) {
            val shouldSave = shouldSave(reason, speechDurationMs, speechFrames, audioBytes)
            val committed = if (f != null && shouldSave) {
                w.closeAndCommit()
            } else {
                w.abort()
                false
            }

            if (f != null && committed) {
                val endedAtMs = System.currentTimeMillis()
                RecordingMetadataStore.writeFinalized(
                    wavFile = f,
                    createdAt = startedAtMs.takeIf { it > 0L } ?: (endedAtMs - speechDurationMs).coerceAtLeast(0L),
                    endedAt = endedAtMs,
                    sampleRate = config.preferredSampleRate,
                    speechDurationMs = speechDurationMs,
                    closeReason = reason,
                    vadEngineName = vadEngineName
                )
                Log.i(
                    TAG,
                    "state -> LISTENING saved=true reason=$reason file=$fileName durationMs=$speechDurationMs"
                )
                applyUiMutation { current ->
                    current.copy(
                        serviceRunning = reason != RecordingCloseReason.ReadError,
                        recorderPhase = if (reason == RecordingCloseReason.ReadError) {
                            RecorderPhase.RECORDER_FAILED
                        } else {
                            RecorderPhase.SAVED
                        },
                        currentFileName = null,
                        lastSavedFileName = fileName,
                        savedCount = current.savedCount + 1,
                        errorMessage = if (reason == RecordingCloseReason.ReadError) {
                            current.errorMessage
                        } else {
                            null
                        },
                        countdownRemainingMs = null
                    )
                }
            } else if (reason == RecordingCloseReason.ReadError || reason == RecordingCloseReason.Destroy) {
                Log.i(
                    TAG,
                    "aborted active recording reason=$reason file=$fileName durationMs=$speechDurationMs"
                )
                applyUiMutation { current ->
                    current.copy(
                        currentFileName = null,
                        lastSavedFileName = null,
                        countdownRemainingMs = null
                    )
                }
            } else {
                Log.i(
                    TAG,
                    "discarding unsaved file reason=$reason file=$fileName durationMs=$speechDurationMs"
                )
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

    private fun shouldSave(
        reason: RecordingCloseReason,
        speechDurationMs: Long,
        speechFrameCount: Int,
        audioBytes: Long
    ): Boolean {
        if (audioBytes <= 0L) return false

        return when (reason) {
            RecordingCloseReason.EndSilence -> {
                speechDurationMs >= config.minSpeechMs && speechFrameCount >= config.minSpeechFrames
            }
            RecordingCloseReason.ManualStop,
            RecordingCloseReason.ServiceStop,
            RecordingCloseReason.ReadError -> true
            RecordingCloseReason.Destroy -> false
        }
    }
}
