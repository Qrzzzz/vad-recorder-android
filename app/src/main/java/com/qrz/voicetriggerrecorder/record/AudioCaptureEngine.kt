package com.qrz.voicetriggerrecorder.record

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.qrz.voicetriggerrecorder.R
import com.qrz.voicetriggerrecorder.ui.RecorderPhase
import com.qrz.voicetriggerrecorder.ui.RecorderUiStateMutation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

internal class AudioCaptureEngine(
    private val context: Context,
    private val sensitivityPreset: SensitivityPreset,
    private val applyUiMutation: (RecorderUiStateMutation) -> Unit,
    private val beforeStart: suspend () -> Unit = {},
    private val inputFactory: (() -> CaptureInput)? = null
) {
    companion object {
        private const val TAG = "VoiceTriggerRecorder"
    }

    data class AudioConfig(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val samplesPerFrame: Int
    )

    internal interface CaptureInput {
        val config: AudioConfig
        fun observeSilence(listener: (Boolean?) -> Unit) { listener(null) }
        fun start()
        fun read(buffer: ShortArray): Int
        fun stop()
        fun release()
    }

    private val lifecycleLock = Any()
    private var started = false
    @Volatile private var closed = false
    @Volatile private var requestedCloseReason = RecordingCloseReason.ServiceStop
    private var input: CaptureInput? = null
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    suspend fun start(): RecordingCloseReason = withContext(Dispatchers.Default) {
        var stateMachine: RecordingStateMachine? = null
        try {
            // Test seam before acquisition: a prior close is terminal, even if start runs later.
            beforeStart()
            currentCoroutineContext().ensureActive()
            val capture = synchronized(lifecycleLock) {
                if (closed) return@withContext requestedCloseReason
                check(!started) { "Capture engines are single-use" }
                started = true
                (inputFactory?.invoke() ?: createCaptureInput()).also {
                    input = it
                    it.observeSilence(::onInputSilenceChanged)
                    it.start()
                }
            }
            val config = capture.config
            val vad = VadEngineFactory.create(config.sampleRate, sensitivityPreset)
            stateMachine = RecordingStateMachine(
                context = context,
                config = RecorderConfig(preferredSampleRate = config.sampleRate,
                    channels = config.channels, bitsPerSample = config.bitsPerSample),
                vadEngineName = vad.javaClass.simpleName,
                applyUiMutation = applyUiMutation
            )

            val buffer = ShortArray(config.samplesPerFrame)
            var readCount = 0
            var consecutiveErrors = 0
            val logInterval = 50 // log summary every 50 frames (1 second)
            var lastSpeechDetected = false
            var lastCountdownSeconds: Long? = null

            while (!closed && currentCoroutineContext().isActive) {
                val read = try {
                    capture.read(buffer)
                } catch (e: Exception) {
                    Log.e(TAG, "AudioRecord read exception", e)
                    -1
                }

                if (closed || !currentCoroutineContext().isActive) break
                if (read > 0) {
                    consecutiveErrors = 0
                    val frame = buffer.copyOf(read)
                    val vadResult = vad.isSpeech(frame, config.sampleRate)
                    val speech = vadResult.isSpeech
                    stateMachine.onFrame(frame, speech)
                    if (stateMachine.hasStorageFailure) {
                        close(RecordingCloseReason.StorageError)
                        break
                    }
                    val countdownMs = stateMachine.countdownRemainingMs
                    val countdownSeconds = countdownMs?.let {
                        ((it + 999L) / 1000L).coerceAtLeast(0L)
                    }

                    if (speech != lastSpeechDetected || countdownSeconds != lastCountdownSeconds) {
                        lastSpeechDetected = speech
                        lastCountdownSeconds = countdownSeconds
                        applyUiMutation { current ->
                            current.copy(
                                speechDetected = speech,
                                countdownRemainingMs = countdownMs
                            )
                        }
                    }

                    readCount++
                    if (readCount % logInterval == 0) {
                        val rms = computeRms(frame, read)
                        val db = 20.0 * kotlin.math.ln(rms / 32768.0 + 1e-9) / kotlin.math.ln(10.0)
                        val zcr = computeZcr(frame, read)
                        Log.v(TAG, "rate=${config.sampleRate} rms=${"%.1f".format(rms)} db=${"%.1f".format(db)} zcr=${"%.2f".format(zcr)} speech=$speech")
                    }
                } else if (read < 0) {
                    consecutiveErrors++
                    Log.e(TAG, "AudioRecord read error: $read (consecutive: $consecutiveErrors)")
                    if (consecutiveErrors >= 5) {
                        close(RecordingCloseReason.ReadError)
                        applyUiMutation { current ->
                            current.copy(
                                serviceRunning = false,
                                recorderPhase = RecorderPhase.RECORDER_FAILED,
                                errorMessage = context.getString(R.string.error_audio_reads_failed),
                                speechDetected = false,
                                countdownRemainingMs = null,
                                currentFileName = null
                            )
                        }
                        break
                    }
                }
            }

        } catch (error: Exception) {
            synchronized(lifecycleLock) {
                if (!closed) requestedCloseReason = RecordingCloseReason.Destroy
            }
            throw error
        } finally {
            try {
                stateMachine?.closeCurrentFileIfNeeded(requestedCloseReason)
                if (stateMachine?.hasStorageFailure == true) {
                    requestedCloseReason = RecordingCloseReason.StorageError
                }
            } finally {
                synchronized(lifecycleLock) {
                    closed = true
                    runCatching { input?.stop() }
                    runCatching { input?.release() }
                    input = null
                    releaseAudioResources()
                }
            }
            applyUiMutation { current ->
                current.copy(
                    speechDetected = false,
                    countdownRemainingMs = null,
                    inputSilenced = null
                )
            }
        }
        requestedCloseReason
    }

    fun close(reason: RecordingCloseReason) {
        synchronized(lifecycleLock) {
            if (closed) return
            requestedCloseReason = reason
            closed = true
            // Unblock a read, but leave release to the worker after that read has returned.
            runCatching { input?.stop() }
        }
    }

    fun stop() {
        close(RecordingCloseReason.ServiceStop)
    }

    internal fun onInputSilenceChanged(silenced: Boolean?) {
        synchronized(lifecycleLock) {
            if (!closed) applyUiMutation { it.copy(inputSilenced = silenced) }
        }
    }

    private fun createCaptureInput(): CaptureInput {
        val config = createAudioRecordOrThrow()
        val record = checkNotNull(audioRecord)
        return object : CaptureInput {
            private var detach: (() -> Unit)? = null
            override val config = config
            override fun observeSilence(listener: (Boolean?) -> Unit) {
                detach = InputSilenceMonitor.attach(record, listener)
            }
            override fun start() {
                record.startRecording()
                InputSilenceMonitor.refresh(record, ::onInputSilenceChanged)
            }
            override fun read(buffer: ShortArray) = record.read(buffer, 0, buffer.size)
            override fun stop() = record.stop()
            // Native resources are released together with the noise suppressor in finally.
            override fun release() { detach?.invoke(); detach = null }
        }
    }

    private fun releaseAudioResources() {
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }
        try {
            audioRecord?.release()
        } catch (_: Throwable) {
        }
        audioRecord = null
        try {
            noiseSuppressor?.release()
        } catch (_: Throwable) {
        }
        noiseSuppressor = null
    }

    private fun createAudioRecordOrThrow(): AudioConfig {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException(context.getString(R.string.error_microphone_init_failed))
        }
        val candidates = listOf(
            Triple(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO),
            Triple(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO),
            Triple(MediaRecorder.AudioSource.VOICE_RECOGNITION, 44100, AudioFormat.CHANNEL_IN_MONO),
            Triple(MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO)
        )

        val format = AudioFormat.ENCODING_PCM_16BIT

        for ((source, rate, channel) in candidates) {
            val minBuf = AudioRecord.getMinBufferSize(rate, channel, format)
            if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) continue

            val frameSize = rate * 20 / 1000 // 20ms frame
            val bufferSize = maxOf(minBuf, frameSize * 4)

            val record = try {
                AudioRecord(source, rate, channel, format, bufferSize)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create AudioRecord for rate=$rate", e)
                null
            }

            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord = record
                initNoiseSuppressor(record)
                val samplesPerFrame = rate * 20 / 1000
                Log.i(TAG, "AudioRecord initialized: rate=$rate source=$source frame=$samplesPerFrame")
                return AudioConfig(record.sampleRate, record.channelCount, 16, record.sampleRate * 20 / 1000)
            } else {
                try {
                    record?.release()
                } catch (_: Throwable) {
                }
            }
        }

        throw IllegalStateException(
            context.getString(R.string.error_microphone_init_failed)
        )
    }

    private fun initNoiseSuppressor(record: AudioRecord) {
        if (!NoiseSuppressor.isAvailable()) return
        try {
            noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)
            noiseSuppressor?.enabled = true
            Log.i(TAG, "NoiseSuppressor enabled")
        } catch (e: Throwable) {
            Log.w(TAG, "NoiseSuppressor creation failed", e)
            noiseSuppressor = null
        }
    }

    private fun computeRms(samples: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return kotlin.math.sqrt(sum / length)
    }

    private fun computeZcr(samples: ShortArray, length: Int): Double {
        if (length < 2) return 0.0
        var crossings = 0
        for (i in 1 until length) {
            if ((samples[i] >= 0) != (samples[i - 1] >= 0)) {
                crossings++
            }
        }
        return crossings.toDouble() / (length - 1)
    }
}
