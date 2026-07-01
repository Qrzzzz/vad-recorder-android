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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun applyUiMutationAndRefreshNotification(mutation: RecorderUiStateMutation) {
        val before = uiState.value
        applyUiMutation(mutation)
        val after = uiState.value
        if (
            before.recorderPhase != after.recorderPhase ||
            before.currentFileName != after.currentFileName ||
            before.errorMessage != after.errorMessage
        ) {
            scope.launch(Dispatchers.Main.immediate) {
                createNotificationChannel()
                startAsForeground()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> stopServiceSafely()
            ACTION_REFRESH_SETTINGS -> refreshSessionSettingsIfRunning()
            else -> startServiceSafely()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServiceSafely() {
        if (engineJob?.isActive == true || engine != null) {
            Log.i(TAG, "Engine already running, ignoring start")
            return
        }

        val sensitivityPreset = RecorderPreferences(applicationContext).loadSensitivityPreset()
        listeningStartedAtMs = System.currentTimeMillis()
        createNotificationChannel()

        applyUiMutationAndRefreshNotification {
            RecorderUiState(
                serviceRunning = true,
                recorderPhase = RecorderPhase.LISTENING
            )
        }

        engine = AudioCaptureEngine(applicationContext, sensitivityPreset) { mutation ->
            applyUiMutationAndRefreshNotification(mutation)
        }
        refreshSessionSettingsIfRunning()

        engineJob = scope.launch {
            try {
                engine?.start()
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture engine failed", e)
                applyUiMutationAndRefreshNotification { current ->
                    current.copy(
                        serviceRunning = true,
                        recorderPhase = RecorderPhase.MICROPHONE_SETUP_FAILED,
                        errorMessage = e.message,
                        speechDetected = false,
                        countdownRemainingMs = null,
                        autoStopAtMs = _uiState.value.autoStopAtMs
                    )
                }
            } finally {
                engine = null
                engineJob = null
                Log.i(TAG, "Engine loop exited")
            }
        }
    }

    private fun stopServiceSafely() {
        Log.i(TAG, "Stopping service")
        scope.launch {
            try {
                autoStopJob?.cancel()
                autoStopJob = null
                engine?.stop()
                engineJob?.join()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop engine", e)
            } finally {
                engine = null
                engineJob = null
                listeningStartedAtMs = null
            }

            applyUiMutation { RecorderUiState() }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        try {
            engine?.stop()
        } catch (_: Throwable) {
        }
        autoStopJob?.cancel()
        autoStopJob = null
        engine = null
        engineJob = null
        listeningStartedAtMs = null
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshSessionSettingsIfRunning() {
        if (engineJob?.isActive != true && engine == null) {
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
            stopServiceSafely()
            return
        }

        autoStopJob = scope.launch {
            delay(remainingMs)
            Log.i(TAG, "Auto-stop deadline reached")
            stopServiceSafely()
        }
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

        val currentState = uiState.value
        val notificationTitle = when (currentState.recorderPhase) {
            RecorderPhase.RECORDING -> getString(R.string.notification_title_recording)
            RecorderPhase.WAITING_TO_FINISH -> getString(R.string.notification_title_finishing)
            RecorderPhase.MICROPHONE_SETUP_FAILED,
            RecorderPhase.RECORDER_FAILED -> getString(R.string.notification_title_error)
            else -> getString(R.string.notification_title_listening)
        }
        val notificationText = when (currentState.recorderPhase) {
            RecorderPhase.RECORDING -> getString(R.string.notification_text_recording)
            RecorderPhase.WAITING_TO_FINISH -> getString(R.string.notification_text_finishing)
            RecorderPhase.MICROPHONE_SETUP_FAILED,
            RecorderPhase.RECORDER_FAILED -> currentState.errorMessage
                ?: getString(R.string.notification_text_error)
            else -> getString(R.string.notification_text_listening)
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
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
        Log.i(TAG, "Foreground notification shown")
    }
}
