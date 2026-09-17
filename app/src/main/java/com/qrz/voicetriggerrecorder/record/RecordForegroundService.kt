package com.qrz.voicetriggerrecorder.record

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.qrz.voicetriggerrecorder.MainActivity
import com.qrz.voicetriggerrecorder.R
import com.qrz.voicetriggerrecorder.ui.RecorderPhase
import com.qrz.voicetriggerrecorder.ui.RecorderUiState
import com.qrz.voicetriggerrecorder.ui.RecorderUiStateMutation
import com.qrz.voicetriggerrecorder.ui.PlaybackInterlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RecordForegroundService : Service() {
    companion object {
        private const val TAG = "VoiceTriggerRecorder"

        const val ACTION_START = "com.qrz.voicetriggerrecorder.action.START"
        const val ACTION_STOP = "com.qrz.voicetriggerrecorder.action.STOP"
        const val ACTION_REFRESH_SETTINGS = "com.qrz.voicetriggerrecorder.action.REFRESH_SETTINGS"
        const val NOTIFICATION_CHANNEL_ID = "voice_recording"
        const val NOTIFICATION_ID = 1001

        // Process-local UI bridge only. Real session state lives on the service instance.
        private val _uiState = MutableStateFlow(RecorderUiState())
        val uiState: StateFlow<RecorderUiState> = _uiState.asStateFlow()
        // A replacement Service must await the destroyed instance's resource/file cleanup.
        private val retiringJobs = mutableSetOf<Job>()

        internal fun applyUiMutation(mutation: RecorderUiStateMutation) {
            _uiState.update(mutation)
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
    private var sessionTiming: SessionTiming? = null
    private var stopping = false
    private var restartRequested = false
    internal var elapsedClock: () -> Long = { SystemClock.elapsedRealtime() }
    internal var wallClock: () -> Long = { System.currentTimeMillis() }
    internal var engineFactory: (RecorderUiStateMutationSink) -> AudioCaptureEngine = { sink ->
        AudioCaptureEngine(applicationContext,
            RecorderPreferences(applicationContext).loadSensitivityPreset(), sink)
    }
    private var foregroundShown = false
    private var preserveUiStateOnDestroy = false
    private var destroyInProgress = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
        PlaybackInterlock.shared.block()
        if (stopping) {
            restartRequested = true
            return
        }
        if (engineJob?.isActive == true || engine != null) {
            Log.i(TAG, "Engine already running, ignoring start")
            return
        }

        lateinit var captureEngine: AudioCaptureEngine
        captureEngine = engineFactory { mutation ->
            applySessionUiMutation(captureEngine, mutation)
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

        sessionTiming = SessionTiming(elapsedClock())
        engine = captureEngine
        preserveUiStateOnDestroy = false
        destroyInProgress = false

        applyUiMutationAndRefreshNotification {
            RecorderUiState(
                serviceRunning = true,
                recorderPhase = RecorderPhase.LISTENING
            )
        }
        refreshSessionSettingsIfRunning()

        engineJob = scope.launch {
            var closeReason = RecordingCloseReason.ServiceStop
            try {
                for (retiring in retiringJobs.toList()) {
                    retiring.join()
                    retiringJobs.remove(retiring)
                }
                if (engine !== captureEngine || stopping || destroyInProgress) return@launch
                RecordingStateMachine.cleanupStalePartialFiles(applicationContext)
                closeReason = captureEngine.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (engine === captureEngine && !destroyInProgress && !stopping) {
                    Log.e(TAG, "Audio capture engine failed", e)
                    handleStartupFailure(e, RecorderPhase.MICROPHONE_SETUP_FAILED)
                }
                return@launch
            } finally {
                Log.i(TAG, "Engine loop exited reason=$closeReason")
            }

            if (!stopping && !destroyInProgress && engine === captureEngine) {
                engine = null
                engineJob = null
                finishStoppedService(preserveFailureState = uiState.value.errorMessage != null)
            }
        }
    }

    internal fun applySessionUiMutation(source: AudioCaptureEngine, mutation: RecorderUiStateMutation) {
        scope.launch {
            // Identity is checked at execution time, not when the worker queues the callback.
            if (destroyInProgress || engine !== source) return@launch
            applyUiMutationAndRefreshNotification(mutation)
        }
    }

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
                // A queued refresh must not restart foreground capture after a failure/stop.
                if (foregroundShown && !destroyInProgress && uiState.value.serviceRunning) {
                    createNotificationChannel()
                    startAsForeground()
                }
            }
        }
    }

    private fun closeAndStop(reason: RecordingCloseReason) {
        // All commands and service state run on Main. Mark STOPPING before yielding.
        restartRequested = false
        if (stopping) return
        stopping = true
        autoStopJob?.cancel()
        autoStopJob = null
        val activeEngine = engine
        val activeJob = engineJob
        activeEngine?.close(reason)
        activeJob?.cancel()
        scope.launch {
            activeJob?.join()
            if (destroyInProgress || engine !== activeEngine) return@launch
            engine = null
            engineJob = null
            sessionTiming = null
            val restart = restartRequested
            restartRequested = false
            stopping = false
            finishStoppedService(
                preserveFailureState = uiState.value.errorMessage != null,
                requestStopSelf = !restart
            )
            if (restart) startServiceSafely()
        }
    }

    private fun finishStoppedService(
        preserveFailureState: Boolean,
        requestStopSelf: Boolean = true
    ) {
        preserveUiStateOnDestroy = preserveFailureState
        autoStopJob?.cancel()
        autoStopJob = null
        sessionTiming = null

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
        PlaybackInterlock.shared.unblock()
        if (requestStopSelf) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        destroyInProgress = true
        engine?.close(RecordingCloseReason.Destroy)
        engineJob?.let { retiringJobs += it }
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

        if (stopping) return
        val source = engine ?: return
        val timing = sessionTiming ?: return
        val autoStopHours = RecorderPreferences(applicationContext).loadAutoStopHours()
        autoStopJob?.cancel()
        autoStopJob = null
        val remainingMs = timing.remainingMs(autoStopHours, elapsedClock())
        val displayDeadline = remainingMs?.let { wallClock() + it.coerceAtLeast(0L) }
        applyUiMutationAndRefreshNotification { current ->
            current.copy(autoStopAtMs = displayDeadline)
        }
        if (remainingMs == null) return
        if (remainingMs <= 0L) {
            closeAndStop(RecordingCloseReason.ServiceStop)
            return
        }
        autoStopJob = scope.launch {
            // delay uses uptime on Android; recheck elapsed time after sleep/resume.
            // Short checks bound the awake-time delay following device sleep.
            while (engine === source && !stopping) {
                val remaining = timing.remainingMs(autoStopHours, elapsedClock()) ?: return@launch
                if (remaining <= 0L) {
                    closeAndStop(RecordingCloseReason.ServiceStop)
                    return@launch
                }
                delay(minOf(remaining, 1_000L))
            }
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
        engine?.close(RecordingCloseReason.Destroy)
        engine = null
        engineJob = null
        sessionTiming = null
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
        PlaybackInterlock.shared.unblock()
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
        foregroundShown = true
        Log.i(TAG, "Foreground notification shown")
    }
}

// Duration is anchored once per session. Wall time is only used for display.
internal class SessionTiming(private val startedElapsedMs: Long) {
    fun remainingMs(hours: Int, elapsedNowMs: Long): Long? =
        if (hours > 0) hours * 3_600_000L - (elapsedNowMs - startedElapsedMs) else null
}

internal typealias RecorderUiStateMutationSink = (RecorderUiStateMutation) -> Unit
