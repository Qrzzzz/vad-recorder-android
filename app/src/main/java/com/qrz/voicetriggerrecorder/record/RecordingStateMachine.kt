package com.qrz.voicetriggerrecorder.record

import android.content.Context
import android.os.Environment
import android.os.SystemClock
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
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
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
            val parent = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: File(context.filesDir, "music")
            return File(parent, "voice-recordings")
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
    private var resumeConfirmFrames = 0
    private var lastConfirmedSpeechAtMs = 0L
    private val preRoll = RingBuffer(config.preRollFrames)
    private val resumeBuffer = RingBuffer(config.resumeConfirmFrames + config.tailKeepFrames)

    val countdownRemainingMs: Long?
        get() = if (state == InternalState.HANGOVER) {
            val elapsedMs = (clockMs() - lastConfirmedSpeechAtMs).coerceAtLeast(0L)
            (config.endSilenceMs.toLong() - elapsedMs).coerceAtLeast(0L)
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
        resumeConfirmFrames = 0
        speechFrames = 0
        silenceFrames = 0
        lastConfirmedSpeechAtMs = 0L
        resumeBuffer.clear()
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
            resumeConfirmFrames = 0
            lastConfirmedSpeechAtMs = clockMs()
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
            resumeConfirmFrames = 0
            lastConfirmedSpeechAtMs = clockMs()
        } else {
            silenceFrames = 1
            resumeConfirmFrames = 0
            resumeBuffer.clear()
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
            resumeConfirmFrames++
            resumeBuffer.add(frame)

            if (resumeConfirmFrames >= config.resumeConfirmFrames) {
                for (pending in resumeBuffer.snapshot()) {
                    writer?.writeSamples(pending, pending.size)
                }
                speechFrames += resumeConfirmFrames
                silenceFrames = 0
                resumeConfirmFrames = 0
                resumeBuffer.clear()
                lastConfirmedSpeechAtMs = clockMs()
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
            }
        } else {
            resumeConfirmFrames = 0
            resumeBuffer.clear()
            silenceFrames++
            if (silenceFrames <= config.tailKeepFrames) {
                writer?.writeSamples(frame, frame.size)
            }
        }

        val waitingForResumeConfirmation = speech && resumeConfirmFrames > 0
        if (state == InternalState.HANGOVER && !waitingForResumeConfirmation && shouldEndHangover()) {
            closeWriterAndFinalize(RecordingCloseReason.EndSilence)
            state = InternalState.LISTENING
            startConfirmFrames = 0
            resumeConfirmFrames = 0
            speechFrames = 0
            silenceFrames = 0
            lastConfirmedSpeechAtMs = 0L
            preRoll.clear()
            resumeBuffer.clear()
        }
    }

    private fun shouldEndHangover(): Boolean {
        val elapsedMs = (clockMs() - lastConfirmedSpeechAtMs).coerceAtLeast(0L)
        return elapsedMs >= config.endSilenceMs
    }

    private enum class DiscardReason {
        NoWriter,
        NoAudio,
        TooShortForServiceStop,
        Destroy,
        CommitFailed
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

        if (w == null) {
            Log.i(
                TAG,
                "discarding recording reason=$reason save=false discardReason=${DiscardReason.NoWriter} " +
                    "speechDurationMs=$speechDurationMs speechFrames=$speechFrames audioBytes=$audioBytes"
            )
            return
        }

        var discardReason = discardReason(reason, speechDurationMs, speechFrames, audioBytes)
        val committed = if (f != null && discardReason == null) {
            w.closeAndCommit()
        } else {
            w.abort()
            false
        }
        if (discardReason == null && !committed) {
            discardReason = DiscardReason.CommitFailed
        }

        if (f != null && committed) {
            val endedAtMs = System.currentTimeMillis()
            RecordingMetadataStore.writeFinalized(
                wavFile = f,
                createdAt = startedAtMs.takeIf { it > 0L }
                    ?: (endedAtMs - speechDurationMs).coerceAtLeast(0L),
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
                    errorMessage = if (reason == RecordingCloseReason.ReadError) current.errorMessage else null,
                    countdownRemainingMs = null
                )
            }
        } else if (reason == RecordingCloseReason.ReadError || reason == RecordingCloseReason.Destroy) {
            Log.i(
                TAG,
                "discarding recording reason=$reason save=false discardReason=$discardReason " +
                    "speechDurationMs=$speechDurationMs speechFrames=$speechFrames audioBytes=$audioBytes"
            )
            applyUiMutation { current ->
                current.copy(
                    serviceRunning = false,
                    recorderPhase = if (reason == RecordingCloseReason.ReadError) {
                        RecorderPhase.RECORDER_FAILED
                    } else {
                        current.recorderPhase
                    },
                    currentFileName = null,
                    lastSavedFileName = null,
                    speechDetected = false,
                    countdownRemainingMs = null
                )
            }
        } else {
            Log.i(
                TAG,
                "discarding recording reason=$reason save=false discardReason=$discardReason " +
                    "speechDurationMs=$speechDurationMs speechFrames=$speechFrames audioBytes=$audioBytes"
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

    private fun discardReason(
        reason: RecordingCloseReason,
        speechDurationMs: Long,
        speechFrameCount: Int,
        audioBytes: Long
    ): DiscardReason? {
        if (reason == RecordingCloseReason.Destroy) return DiscardReason.Destroy
        if (audioBytes <= 0L || speechFrameCount <= 0) return DiscardReason.NoAudio

        return when (reason) {
            RecordingCloseReason.EndSilence -> {
                null
            }
            RecordingCloseReason.ServiceStop,
            RecordingCloseReason.ReadError -> {
                if (
                    speechDurationMs >= config.minSpeechMs &&
                    speechFrameCount >= config.minSpeechFrames
                ) {
                    null
                } else {
                    DiscardReason.TooShortForServiceStop
                }
            }
            RecordingCloseReason.ManualStop -> null
            RecordingCloseReason.Destroy -> DiscardReason.Destroy
        }
    }
}
