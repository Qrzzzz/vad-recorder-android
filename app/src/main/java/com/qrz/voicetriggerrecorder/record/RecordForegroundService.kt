package com.qrz.voicetriggerrecorder.record

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.qrz.voicetriggerrecorder.R
import com.qrz.voicetriggerrecorder.MainActivity
import com.qrz.voicetriggerrecorder.ui.RecorderPhase
import com.qrz.voicetriggerrecorder.ui.RecorderUiState
import com.qrz.voicetriggerrecorder.ui.RecorderUiStateMutation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class RecordForegroundService : Service() {
    companion object {
        private const val TAG = "VoiceTriggerRecorder"

        const val ACTION_START = "com.qrz.voicetriggerrecorder.action.START"
        const val ACTION_STOP = "com.qrz.voicetriggerrecorder.action.STOP"
        const val ACTION_REFRESH_SETTINGS = "com.qrz.voicetriggerrecorder.action.REFRESH_SETTINGS"
        const val NOTIFICATION_CHANNEL_ID = "voice_recording"
        const val NOTIFICATION_ID = 1001

        private val _uiState = MutableStateFlow(RecorderUiState())
        val uiState: StateFlow<RecorderUiState> = _uiState.asStateFlow()

        private fun applyUiMutation(mutation: RecorderUiStateMutation) {
            _uiState.value = mutation(_uiState.value)
        }

        fun requestSettingsRefresh(context: android.content.Context) {
            val intent = Intent(context, RecordForegroundService::class.java).apply {
                action = ACTION_REFRESH_SETTINGS
            }
            context.startService(intent)
        }
    }

    private var engine: AudioCaptureEngine? = null
    private var engineJob: Job? = null
    private var autoStopJob: Job? = null
    private var listeningStartedAtMs: Long? = null
    private var foregroundShown = false
    private var preserveUiStateOnDestroy = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand action=$action")
        when (action) {
            ACTION_START -> startServiceSafely()
            ACTION_STOP -> closeAndStop(RecordingCloseReason.ManualStop)
            ACTION_REFRESH_SETTINGS -> refreshSessionSettingsIfRunning()
            null -> handleUnexpectedStart("null action", startId)
            else -> handleUnexpectedStart("unknown action=$action", startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServiceSafely() {
        if (engineJob?.isActive == true || engine != null) {
            Log.i(TAG, "Engine already running, ignoring start")
            return
        }

        val preferences = RecorderPreferences(applicationContext)
        val sensitivityPreset = preferences.loadSensitivityPreset()
        RecordingStateMachine.cleanupStalePartialFiles(applicationContext)
        val captureEngine = AudioCaptureEngine(applicationContext, sensitivityPreset) { mutation ->
            applyUiMutation(mutation)
        }

        try {
            createNotificationChannel()
            startAsForeground()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start foreground service", e)
            captureEngine.close(RecordingCloseReason.ServiceStop)
            handleStartupFailure(e, RecorderPhase.RECORDER_FAILED)
            return
        }

        listeningStartedAtMs = System.currentTimeMillis()
        engine = captureEngine

        applyUiMutation {
            RecorderUiState(
                serviceRunning = true,
                recorderPhase = RecorderPhase.LISTENING
            )
        }

        engineJob = scope.launch {
            var closeReason = RecordingCloseReason.ServiceStop
            try {
                closeReason = captureEngine.start()
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture engine failed", e)
                handleStartupFailure(e, RecorderPhase.MICROPHONE_SETUP_FAILED)
                return@launch
            } finally {
                if (engine === captureEngine) {
                    engine = null
                }
                engineJob = null
                Log.i(TAG, "Engine loop exited reason=$closeReason")
            }

            if (closeReason == RecordingCloseReason.ReadError) {
                finishStoppedService(preserveFailureState = true)
            }
        }

        refreshSessionSettingsIfRunning()
    }

    private fun closeAndStop(reason: RecordingCloseReason) {
        Log.i(TAG, "Stopping service reason=$reason")
        scope.launch {
            closeEngine(reason)
            finishStoppedService(preserveFailureState = false)
        }
    }

    private suspend fun closeEngine(reason: RecordingCloseReason) {
        autoStopJob?.cancel()
        autoStopJob = null

        val activeEngine = engine
        val activeJob = engineJob

        try {
            activeEngine?.close(reason)
            activeJob?.join()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop engine", e)
        } finally {
            if (engine === activeEngine) {
                engine = null
            }
            if (engineJob === activeJob) {
                engineJob = null
            }
            listeningStartedAtMs = null
        }
    }

    private fun finishStoppedService(
        preserveFailureState: Boolean,
        requestStopSelf: Boolean = true
    ) {
        preserveUiStateOnDestroy = preserveFailureState
        autoStopJob?.cancel()
        autoStopJob = null
        listeningStartedAtMs = null

        if (preserveFailureState) {
            applyUiMutation { current ->
                current.copy(
                    serviceRunning = false,
                    currentFileName = null,
                    speechDetected = false,
                    countdownRemainingMs = null,
                    autoStopAtMs = null
                )
            }
        } else {
            applyUiMutation { RecorderUiState() }
        }

        stopForegroundIfNeeded()
        if (requestStopSelf) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        engine?.close(RecordingCloseReason.Destroy)
        finishStoppedService(
            preserveFailureState = preserveUiStateOnDestroy,
            requestStopSelf = false
        )
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed, stopping service")
        closeAndStop(RecordingCloseReason.ServiceStop)
        super.onTaskRemoved(rootIntent)
    }

    private fun refreshSessionSettingsIfRunning() {
        if (engineJob?.isActive != true && engine == null) {
            stopSelf()
            return
        }

        val startedAt = listeningStartedAtMs ?: System.currentTimeMillis().also {
            listeningStartedAtMs = it
        }
        val autoStopHours = RecorderPreferences(applicationContext).loadAutoStopHours()
        val autoStopAtMs = if (autoStopHours > 0) {
            startedAt + autoStopHours * 60L * 60L * 1000L
        } else {
            null
        }

        autoStopJob?.cancel()
        autoStopJob = null

        applyUiMutation { current ->
            current.copy(autoStopAtMs = autoStopAtMs)
        }

        createNotificationChannel()
        startAsForeground()

        if (autoStopAtMs == null) {
            return
        }

        val remainingMs = autoStopAtMs - System.currentTimeMillis()
        if (remainingMs <= 0L) {
            Log.i(TAG, "Auto-stop deadline already reached, stopping service now")
            closeAndStop(RecordingCloseReason.ServiceStop)
            return
        }

        autoStopJob = scope.launch {
            delay(remainingMs)
            Log.i(TAG, "Auto-stop deadline reached")
            closeAndStop(RecordingCloseReason.ServiceStop)
        }
    }

    private fun handleUnexpectedStart(reason: String, startId: Int) {
        Log.w(TAG, "Ignoring service start with $reason")
        if (engineJob?.isActive == true || engine != null) {
            closeAndStop(RecordingCloseReason.ServiceStop)
        } else {
            applyUiMutation { RecorderUiState() }
            stopForegroundIfNeeded()
            stopSelf(startId)
        }
    }

    private fun handleStartupFailure(error: Exception, phase: RecorderPhase) {
        autoStopJob?.cancel()
        autoStopJob = null
        engine?.close(RecordingCloseReason.ServiceStop)
        engine = null
        engineJob = null
        listeningStartedAtMs = null
        preserveUiStateOnDestroy = true

        applyUiMutation { current ->
            current.copy(
                serviceRunning = false,
                recorderPhase = phase,
                errorMessage = error.message,
                speechDetected = false,
                countdownRemainingMs = null,
                currentFileName = null,
                autoStopAtMs = null
            )
        }

        stopForegroundIfNeeded()
        stopSelf()
    }

    private fun stopForegroundIfNeeded() {
        if (!foregroundShown) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foregroundShown = false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun startAsForeground() {
        val stopIntent = Intent(this, RecordForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundShown = true
        Log.i(TAG, "Foreground notification shown")
    }
}
