package com.qrz.voicetriggerrecorder.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.qrz.voicetriggerrecorder.R
import com.qrz.voicetriggerrecorder.app.AppLanguage
import com.qrz.voicetriggerrecorder.app.AppNightMode
import com.qrz.voicetriggerrecorder.record.RecordForegroundService
import com.qrz.voicetriggerrecorder.record.RecorderPreferences
import com.qrz.voicetriggerrecorder.record.RecordingFile
import com.qrz.voicetriggerrecorder.record.RecordingRepository
import com.qrz.voicetriggerrecorder.record.SensitivityPreset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarResult
import com.qrz.voicetriggerrecorder.record.DeleteOutcome

private enum class MainTab(
    @StringRes val titleRes: Int
) {
    HOME(R.string.tab_home),
    SETTINGS(R.string.tab_settings)
}
@Composable
fun MainScreen(transfers: RecordingTransferViewModel) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by RecordForegroundService.uiState.collectAsState()
    val repository = remember(context) { RecordingRepository(context) }
    val preferences = remember(context) { RecorderPreferences(context) }
    var selectedTab by remember { mutableStateOf(MainTab.HOME) }
    var files by remember { mutableStateOf<List<RecordingFile>>(emptyList()) }
    var permissionDeniedPermanently by rememberSaveable { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf(preferences.loadSensitivityPreset()) }
    var selectedLanguage by remember { mutableStateOf(preferences.loadAppLanguage()) }
    var selectedNightMode by remember { mutableStateOf(preferences.loadAppNightMode()) }
    var autoStopEnabled by remember { mutableStateOf(preferences.loadAutoStopHours() > 0) }
    var autoStopHours by remember {
        mutableIntStateOf(
            preferences.loadAutoStopHours().takeIf { it > 0 } ?: 4
        )
    }
    var resumeTick by remember { mutableIntStateOf(0) }
    var filePendingDelete by remember { mutableStateOf<RecordingFile?>(null) }
    var fileLoadError by remember { mutableStateOf<String?>(null) }
    val playbackController = remember { PlaybackController() }
    val playback by playbackController.state.collectAsState()
    val playbackBlocked by PlaybackInterlock.shared.blocked.collectAsState()
    val transferState by transfers.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val actionScope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        CreateRecordingDocument()
    ) { transfers.destinationSelected(it) }

    LaunchedEffect(transferState.messageRes) {
        transferState.messageRes?.let { message ->
            snackbar.showSnackbar(context.getString(message))
            transfers.dismissMessage()
        }
    }

    LaunchedEffect(transferState.shareIntent, resumeTick) {
        val intent = transferState.shareIntent
        if (intent != null && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            // Hand-off is not delivery confirmation; cancelling the chooser changes no metadata.
            try {
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
                transfers.shareLaunched()
            } catch (_: Exception) {
                transfers.launchFailed()
            }
        }
    }

    fun refreshFiles() {
        runCatching { repository.listRecordings() }
            .onSuccess { latestFiles ->
                fileLoadError = null
                files = latestFiles
                playbackController.retainFiles(latestFiles.map { it.path })
            }
            .onFailure {
                files = emptyList()
                fileLoadError = context.getString(R.string.error_recordings_load_failed)
                playbackController.clear()
            }
    }

    fun saveAutoStop(hours: Int) {
        val sanitizedHours = hours.coerceIn(0, 12)
        autoStopEnabled = sanitizedHours > 0
        if (sanitizedHours > 0) {
            autoStopHours = sanitizedHours
        }
        preferences.saveAutoStopHours(sanitizedHours)
        if (uiState.serviceRunning) {
            RecordForegroundService.requestSettingsRefresh(context)
        }
    }

    fun saveLanguage(language: AppLanguage) {
        if (selectedLanguage == language) return
        selectedLanguage = language
        preferences.saveAppLanguage(language)
        language.apply()
        if (uiState.serviceRunning) {
            RecordForegroundService.requestSettingsRefresh(context)
        }
    }

    fun saveNightMode(mode: AppNightMode) {
        if (selectedNightMode == mode) return
        selectedNightMode = mode
        preferences.saveAppNightMode(mode)
        mode.apply()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDeniedPermanently = false
            startService(context)
            requestNotificationIfNeeded(context, notificationPermissionLauncher)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permissionDeniedPermanently = activity?.shouldShowRequestPermissionRationale(
                android.Manifest.permission.RECORD_AUDIO
            ) == false
        }
    }

    DisposableEffect(playbackController) {
        onDispose { playbackController.clear() }
    }

    LaunchedEffect(playback.path, playback.isPlaying) {
        while (playbackController.state.value.isPlaying) {
            playbackController.updatePosition()
            delay(200)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick++
            } else if (event == Lifecycle.Event.ON_STOP) {
                playbackController.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.savedCount, uiState.serviceRunning, resumeTick) {
        refreshFiles()
        selectedPreset = preferences.loadSensitivityPreset()
        val loadedAutoStopHours = preferences.loadAutoStopHours()
        autoStopEnabled = loadedAutoStopHours > 0
        if (loadedAutoStopHours > 0) {
            autoStopHours = loadedAutoStopHours
        }
        selectedLanguage = preferences.loadAppLanguage()
        selectedNightMode = preferences.loadAppNightMode()

        val audioGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        // Only a completed request can establish permanent denial. On first launch,
        // rationale=false also means that permission has never been requested.
        permissionDeniedPermanently = retainPermanentDenial(
            permissionDeniedPermanently,
            audioGranted,
            activity?.shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO) == true
        )
    }

    val audioPermissionGranted = ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.RECORD_AUDIO
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    val notificationPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    val batteryOptimizationEnabled = remember(context, resumeTick) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            false
        } else {
            val powerManager = context.getSystemService(PowerManager::class.java)
            powerManager != null && !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
    }
    val readinessNeedsAttention = !audioPermissionGranted ||
        !notificationPermissionGranted ||
        batteryOptimizationEnabled

    val latestRecording = remember(files) { files.maxByOrNull { it.lastModified } }
    val nightGroups = remember(files) { buildNightGroups(files) }
    val latestNightGroup = nightGroups.firstOrNull()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = {
            Column {
                if (transferState.busy) {
                    Surface(tonalElevation = 3.dp) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(stringResource(R.string.transfer_working))
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                }
                SnackbarHost(snackbar)
            }
        },
        bottomBar = {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                MainTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = {
                            if (tab != MainTab.HOME) playbackController.pause()
                            selectedTab = tab
                        },
                        text = { Text(stringResource(tab.titleRes)) }
                    )
                }
            }
        }
    ) { padding ->
        when (selectedTab) {
            MainTab.HOME -> {
                HomeTabContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    uiState = uiState,
                    latestRecording = latestRecording,
                    autoStopEnabled = autoStopEnabled,
                    autoStopHours = autoStopHours,
                    errorMessage = uiState.errorMessage,
                    fileLoadError = fileLoadError,
                    permissionDeniedPermanently = permissionDeniedPermanently,
                    audioPermissionGranted = audioPermissionGranted,
                    notificationPermissionGranted = notificationPermissionGranted,
                    batteryOptimizationEnabled = batteryOptimizationEnabled,
                    latestNightGroup = latestNightGroup,
                    nightGroups = nightGroups,
                    playback = playback,
                    playbackEnabled = !playbackBlocked,
                    readinessNeedsAttention = readinessNeedsAttention,
                    onPrimaryAction = {
                        when {
                            permissionDeniedPermanently -> openAppSettings(context)
                            uiState.serviceRunning -> stopService(context)
                            audioPermissionGranted -> {
                                startService(context)
                                requestNotificationIfNeeded(context, notificationPermissionLauncher)
                            }
                            else -> audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onRefresh = { refreshFiles() },
                    onPlayPause = { file ->
                        runCatching { repository.fileForTransfer(file.path) }
                            .onSuccess { playbackController.toggle(it.path) }
                            .onFailure { actionScope.launch { snackbar.showSnackbar(context.getString(R.string.transfer_source_unavailable)) } }
                    },
                    onSeek = { path, position -> playbackController.seekTo(path, position) },
                    onDelete = { filePendingDelete = it },
                    transferActionsEnabled = transferState.actionsEnabled,
                    onShare = {
                        playbackController.pause()
                        transfers.share(it.path)
                    },
                    onExport = {
                        if (transfers.beginExport(it.path)) {
                            playbackController.pause()
                            try { exportLauncher.launch(it.name) }
                            catch (_: Exception) { transfers.launchFailed() }
                        }
                    },
                    onOpenSettingsTab = {
                        playbackController.pause()
                        selectedTab = MainTab.SETTINGS
                    }
                )
            }

            MainTab.SETTINGS -> {
                SettingsTabContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    serviceRunning = uiState.serviceRunning,
                    selectedLanguage = selectedLanguage,
                    selectedNightMode = selectedNightMode,
                    selectedPreset = selectedPreset,
                    autoStopEnabled = autoStopEnabled,
                    autoStopHours = autoStopHours,
                    audioPermissionGranted = audioPermissionGranted,
                    permissionDeniedPermanently = permissionDeniedPermanently,
                    notificationPermissionGranted = notificationPermissionGranted,
                    batteryOptimizationEnabled = batteryOptimizationEnabled,
                    onPresetSelected = { preset ->
                        selectedPreset = preset
                        preferences.saveSensitivityPreset(preset)
                    },
                    onLanguageSelected = { language -> saveLanguage(language) },
                    onNightModeSelected = { mode -> saveNightMode(mode) },
                    onAutoStopEnabledChange = { enabled ->
                        autoStopEnabled = enabled
                        saveAutoStop(if (enabled) autoStopHours else 0)
                    },
                    onAutoStopHoursDraftChange = { hours ->
                        autoStopHours = hours.coerceIn(1, 12)
                    },
                    onAutoStopHoursCommit = {
                        if (autoStopEnabled) {
                            saveAutoStop(autoStopHours)
                        }
                    },
                    onOpenAppSettings = { openAppSettings(context) },
                    onOpenNotificationSettings = { openNotificationSettings(context) },
                    onOpenBatterySettings = { openBatteryOptimizationSettings(context) }
                )
            }
        }
    }

    filePendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { filePendingDelete = null },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = {
                Text(stringResource(R.string.dialog_delete_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (playback.path == file.path) {
                            playbackController.clear()
                        }
                        filePendingDelete = null
                        actionScope.launch {
                            var retry: Boolean
                            do {
                                val result = withContext(Dispatchers.IO) { repository.deleteRecording(file.path) }
                                refreshFiles()
                                val message = when (result) {
                                    DeleteOutcome.DELETED, DeleteOutcome.ALREADY_ABSENT -> null
                                    DeleteOutcome.METADATA_REMAINS -> R.string.delete_metadata_remains
                                    else -> R.string.delete_audio_failed
                                }
                                retry = message != null && snackbar.showSnackbar(
                                    context.getString(message), context.getString(R.string.action_retry)
                                ) == SnackbarResult.ActionPerformed
                            } while (retry)
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { filePendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun HomeTabContent(
    modifier: Modifier,
    uiState: RecorderUiState,
    latestRecording: RecordingFile?,
    autoStopEnabled: Boolean,
    autoStopHours: Int,
    errorMessage: String?,
    fileLoadError: String?,
    permissionDeniedPermanently: Boolean,
    audioPermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    batteryOptimizationEnabled: Boolean,
    latestNightGroup: NightRecordingGroup?,
    nightGroups: List<NightRecordingGroup>,
    playback: PlaybackState,
    playbackEnabled: Boolean,
    readinessNeedsAttention: Boolean,
    onPrimaryAction: () -> Unit,
    onRefresh: () -> Unit,
    onPlayPause: (RecordingFile) -> Unit,
    onSeek: (String, Int) -> Unit,
    onDelete: (RecordingFile) -> Unit,
    transferActionsEnabled: Boolean,
    onShare: (RecordingFile) -> Unit,
    onExport: (RecordingFile) -> Unit,
    onOpenSettingsTab: () -> Unit
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            ActiveRecordingCard(uiState = uiState)
        }

        item {
            ReadinessCard(
                audioPermissionGranted = audioPermissionGranted,
                notificationPermissionGranted = notificationPermissionGranted,
                batteryOptimizationEnabled = batteryOptimizationEnabled,
                needsAttention = readinessNeedsAttention,
                onOpenSettingsTab = onOpenSettingsTab
            )
        }

        item {
            StatusOverviewCard(
                uiState = uiState,
                latestRecording = latestRecording,
                autoStopEnabled = autoStopEnabled,
                autoStopHours = autoStopHours
            )
        }

        item {
            PrimaryActionsCard(
                serviceRunning = uiState.serviceRunning,
                recorderPhase = uiState.recorderPhase,
                permissionDeniedPermanently = permissionDeniedPermanently,
                audioPermissionGranted = audioPermissionGranted,
                onPrimaryAction = onPrimaryAction,
                onRefresh = onRefresh
            )
        }

        errorMessage?.let { message ->
            item {
                TipCard(
                    title = stringResource(R.string.tip_recorder_error_title),
                    body = message
                )
            }
        }

        fileLoadError?.let { message ->
            item {
                TipCard(
                    title = stringResource(R.string.tip_storage_error_title),
                    body = message
                )
            }
        }

        item {
            SectionHeader(
                title = stringResource(R.string.section_last_night_title),
                subtitle = stringResource(R.string.section_last_night_subtitle)
            )
        }

        item {
            if (latestNightGroup == null) {
                EmptyNightSummaryCard()
            } else {
                NightSummaryCard(latestNightGroup)
            }
        }

        item {
            SectionHeader(
                title = stringResource(R.string.section_night_recordings_title),
                subtitle = stringResource(R.string.section_night_recordings_subtitle)
            )
        }

        if (playback.hasError) {
            item {
                TipCard(
                    title = stringResource(R.string.tip_playback_failed_title),
                    body = stringResource(R.string.playback_failed_fallback)
                )
            }
        }

        if (nightGroups.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.empty_clips_message),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            items(
                items = nightGroups,
                key = { group -> group.nightDate.toString() }
            ) { group ->
                NightGroupCard(
                    group = group,
                    playback = playback,
                    playbackEnabled = playbackEnabled,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek,
                    onDelete = onDelete,
                    transferActionsEnabled = transferActionsEnabled,
                    onShare = onShare,
                    onExport = onExport
                )
            }
        }

        item {
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ActiveRecordingCard(uiState: RecorderUiState) {
    if (
        uiState.recorderPhase != RecorderPhase.RECORDING &&
        uiState.recorderPhase != RecorderPhase.WAITING_TO_FINISH
    ) {
        return
    }

    val context = LocalContext.current
    val activelyRecording = uiState.recorderPhase == RecorderPhase.RECORDING
    val containerColor = if (activelyRecording) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (activelyRecording) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    val bodyText = if (activelyRecording) {
        stringResource(R.string.recording_attention_body)
    } else {
        stringResource(
            R.string.recording_wrap_up_body,
            formatCountdown(context, uiState.countdownRemainingMs ?: 0L)
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (activelyRecording) {
                    stringResource(R.string.recording_attention_title)
                } else {
                    stringResource(R.string.recording_wrap_up_title)
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = bodyText,
                style = MaterialTheme.typography.bodyMedium
            )
            uiState.currentFileName?.let { fileName ->
                Text(
                    text = stringResource(R.string.recording_attention_file, fileName),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ReadinessCard(
    audioPermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    batteryOptimizationEnabled: Boolean,
    needsAttention: Boolean,
    onOpenSettingsTab: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.readiness_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (needsAttention) {
                    stringResource(R.string.readiness_attention_body)
                } else {
                    stringResource(R.string.readiness_ready_body)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            InfoLine(
                stringResource(R.string.readiness_microphone_label),
                if (audioPermissionGranted) {
                    stringResource(R.string.readiness_microphone_ready)
                } else {
                    stringResource(R.string.readiness_microphone_missing)
                }
            )
            InfoLine(
                stringResource(R.string.readiness_notifications_label),
                if (notificationPermissionGranted) {
                    stringResource(R.string.readiness_notifications_ready)
                } else {
                    stringResource(R.string.readiness_notifications_missing)
                }
            )
            InfoLine(
                stringResource(R.string.readiness_battery_label),
                if (batteryOptimizationEnabled) {
                    stringResource(R.string.readiness_battery_missing)
                } else {
                    stringResource(R.string.readiness_battery_ready)
                }
            )

            if (needsAttention) {
                OutlinedButton(
                    onClick = onOpenSettingsTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.readiness_action))
                }
            }
        }
    }
}

@Composable
private fun SettingsTabContent(
    modifier: Modifier,
    serviceRunning: Boolean,
    selectedLanguage: AppLanguage,
    selectedNightMode: AppNightMode,
    selectedPreset: SensitivityPreset,
    autoStopEnabled: Boolean,
    autoStopHours: Int,
    audioPermissionGranted: Boolean,
    permissionDeniedPermanently: Boolean,
    notificationPermissionGranted: Boolean,
    batteryOptimizationEnabled: Boolean,
    onLanguageSelected: (AppLanguage) -> Unit,
    onNightModeSelected: (AppNightMode) -> Unit,
    onPresetSelected: (SensitivityPreset) -> Unit,
    onAutoStopEnabledChange: (Boolean) -> Unit,
    onAutoStopHoursDraftChange: (Int) -> Unit,
    onAutoStopHoursCommit: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.settings_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            LanguageCard(
                selectedLanguage = selectedLanguage,
                serviceRunning = serviceRunning,
                onLanguageSelected = onLanguageSelected
            )
        }

        item {
            NightModeCard(
                selectedNightMode = selectedNightMode,
                onNightModeSelected = onNightModeSelected
            )
        }

        item {
            AutoStopCard(
                enabled = autoStopEnabled,
                autoStopHours = autoStopHours,
                serviceRunning = serviceRunning,
                onEnabledChange = onAutoStopEnabledChange,
                onHoursDraftChange = onAutoStopHoursDraftChange,
                onHoursCommit = onAutoStopHoursCommit
            )
        }

        item {
            SensitivityCard(
                selectedPreset = selectedPreset,
                serviceRunning = serviceRunning,
                onPresetSelected = onPresetSelected
            )
        }

        if (!audioPermissionGranted) {
            item {
                TipCard(
                    title = stringResource(R.string.tip_microphone_permission_title),
                    body = if (permissionDeniedPermanently) {
                        stringResource(R.string.tip_microphone_permission_body_settings)
                    } else {
                        stringResource(R.string.tip_microphone_permission_body_request)
                    },
                    actionLabel = if (permissionDeniedPermanently) {
                        stringResource(R.string.action_open_settings)
                    } else {
                        null
                    },
                    onAction = if (permissionDeniedPermanently) onOpenAppSettings else null
                )
            }
        }

        if (!notificationPermissionGranted) {
            item {
                TipCard(
                    title = stringResource(R.string.tip_notifications_title),
                    body = stringResource(R.string.tip_notifications_body),
                    actionLabel = stringResource(R.string.action_notification_settings),
                    onAction = onOpenNotificationSettings
                )
            }
        }

        if (batteryOptimizationEnabled) {
            item {
                TipCard(
                    title = stringResource(R.string.tip_battery_title),
                    body = stringResource(R.string.tip_battery_body),
                    actionLabel = stringResource(R.string.action_battery_settings),
                    onAction = onOpenBatterySettings
                )
            }
        }

        item {
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun StatusOverviewCard(
    uiState: RecorderUiState,
    latestRecording: RecordingFile?,
    autoStopEnabled: Boolean,
    autoStopHours: Int
) {
    val context = LocalContext.current
    val finishCountdownText = when (val countdownMs = uiState.countdownRemainingMs) {
        null -> if (uiState.serviceRunning) {
            stringResource(R.string.status_finish_countdown_not_started)
        } else {
            stringResource(R.string.status_not_listening)
        }

        else -> stringResource(
            R.string.status_finish_countdown_remaining,
            formatCountdown(context, countdownMs)
        )
    }
    val autoStopText = when (val autoStopAtMs = uiState.autoStopAtMs) {
        null -> if (uiState.serviceRunning) {
            stringResource(R.string.status_auto_stop_disabled_session)
        } else if (autoStopEnabled) {
            formatAutoStopNextSession(context, autoStopHours)
        } else {
            stringResource(R.string.status_auto_stop_disabled)
        }

        else -> stringResource(R.string.status_auto_stop_at, formatDateTime(context, autoStopAtMs))
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (uiState.serviceRunning) {
                    stringResource(R.string.status_listening_active)
                } else {
                    stringResource(R.string.status_listening_off)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            StatusPill(
                text = if (uiState.speechDetected) {
                    stringResource(R.string.status_voice_detected)
                } else {
                    stringResource(R.string.status_voice_not_detected)
                },
                active = uiState.speechDetected
            )

            InfoLine(stringResource(R.string.label_current_state), formatRecorderState(context, uiState))
            InfoLine(stringResource(R.string.label_finish_countdown), finishCountdownText)
            InfoLine(stringResource(R.string.label_auto_stop), autoStopText)
            InfoLine(
                stringResource(R.string.label_current_clip),
                uiState.currentFileName ?: stringResource(R.string.current_clip_none)
            )
            InfoLine(stringResource(R.string.label_saved_session), formatClipCount(context, uiState.savedCount))

            latestRecording?.let { file ->
                HorizontalDivider()
                InfoLine(
                    stringResource(R.string.label_latest_saved_clip),
                    friendlyRecordingLabel(context, file)
                )
                InfoLine(
                    stringResource(R.string.label_last_captured_at),
                    "${formatDateTime(context, file.lastModified)} / ${formatDuration(context, file.durationMs)}"
                )
            }

            if (uiState.currentFileName != null) {
                Text(
                    stringResource(R.string.current_clip_stop_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LanguageCard(
    selectedLanguage: AppLanguage,
    serviceRunning: Boolean,
    onLanguageSelected: (AppLanguage) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.language_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.language_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppLanguage.entries.forEach { language ->
                    FilterChip(
                        selected = language == selectedLanguage,
                        onClick = { onLanguageSelected(language) },
                        label = { Text(stringResource(language.labelRes)) }
                    )
                }
            }

            Text(
                if (serviceRunning) {
                    stringResource(R.string.language_effect_running)
                } else {
                    stringResource(R.string.language_effect_stopped)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NightModeCard(
    selectedNightMode: AppNightMode,
    onNightModeSelected: (AppNightMode) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.theme_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.theme_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppNightMode.entries.forEach { mode ->
                    FilterChip(
                        selected = mode == selectedNightMode,
                        onClick = { onNightModeSelected(mode) },
                        label = { Text(stringResource(mode.labelRes)) }
                    )
                }
            }

            Text(
                stringResource(R.string.theme_effect),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PrimaryActionsCard(
    serviceRunning: Boolean,
    recorderPhase: RecorderPhase,
    permissionDeniedPermanently: Boolean,
    audioPermissionGranted: Boolean,
    onPrimaryAction: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        when {
                            permissionDeniedPermanently -> stringResource(R.string.action_open_settings)
                            serviceRunning -> stringResource(R.string.action_stop_listening)
                            audioPermissionGranted -> stringResource(R.string.action_start_listening)
                            else -> stringResource(R.string.action_grant_and_start)
                        }
                    )
                }
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_refresh_list))
                }
            }

            Text(
                when {
                    recorderPhase == RecorderPhase.RECORDING -> {
                        stringResource(R.string.primary_action_recording_body)
                    }

                    recorderPhase == RecorderPhase.WAITING_TO_FINISH -> {
                        stringResource(R.string.primary_action_finishing_body)
                    }

                    serviceRunning -> {
                        stringResource(R.string.primary_action_running_body)
                    }

                    else -> {
                        stringResource(R.string.primary_action_stopped_body)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AutoStopCard(
    enabled: Boolean,
    autoStopHours: Int,
    serviceRunning: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onHoursDraftChange: (Int) -> Unit,
    onHoursCommit: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.auto_stop_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.auto_stop_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        if (enabled) {
                            stringResource(R.string.auto_stop_on)
                        } else {
                            stringResource(R.string.auto_stop_off)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (serviceRunning) {
                            stringResource(R.string.auto_stop_changes_immediate)
                        } else {
                            stringResource(R.string.auto_stop_changes_next_session)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }

            if (enabled) {
                Text(
                    pluralStringResource(R.plurals.auto_stop_after_hours, autoStopHours, autoStopHours),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = autoStopHours.toFloat(),
                    onValueChange = { value ->
                        onHoursDraftChange(value.toInt().coerceIn(1, 12))
                    },
                    valueRange = 1f..12f,
                    steps = 10,
                    onValueChangeFinished = onHoursCommit
                )
                Text(
                    stringResource(R.string.auto_stop_range),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SensitivityCard(
    selectedPreset: SensitivityPreset,
    serviceRunning: Boolean,
    onPresetSelected: (SensitivityPreset) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.sensitivity_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.sensitivity_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SensitivityPreset.values().forEach { preset ->
                    FilterChip(
                        selected = preset == selectedPreset,
                        onClick = { onPresetSelected(preset) },
                        enabled = !serviceRunning,
                        label = { Text(stringResource(preset.labelRes)) }
                    )
                }
            }

            Text(
                stringResource(selectedPreset.descriptionRes),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun NightSummaryCard(group: NightRecordingGroup) {
    val context = LocalContext.current

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                formatNightSectionLabel(context, group.nightDate),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            InfoLine(stringResource(R.string.label_captured_clips), formatClipCount(context, group.recordings.size))
            InfoLine(stringResource(R.string.label_total_duration), formatDuration(context, group.totalDurationMs))
            InfoLine(stringResource(R.string.label_latest_clip_time), formatClockTime(context, group.latestModified))
        }
    }
}

@Composable
private fun EmptyNightSummaryCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.night_summary_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.night_summary_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NightGroupCard(
    group: NightRecordingGroup,
    playback: PlaybackState,
    playbackEnabled: Boolean,
    onPlayPause: (RecordingFile) -> Unit,
    onSeek: (String, Int) -> Unit,
    onDelete: (RecordingFile) -> Unit,
    transferActionsEnabled: Boolean,
    onShare: (RecordingFile) -> Unit,
    onExport: (RecordingFile) -> Unit
) {
    val context = LocalContext.current

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(
                    R.string.recording_group_summary,
                    formatNightSectionLabel(context, group.nightDate),
                    formatClipCount(context, group.recordings.size),
                    formatDuration(context, group.totalDurationMs)
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            group.recordings.forEachIndexed { index, file ->
                RecordingItemCard(
                    file = file,
                    playback = playback.takeIf { it.path == file.path },
                    playbackEnabled = playbackEnabled,
                    onPlayPause = { onPlayPause(file) },
                    onSeek = { onSeek(file.path, it) },
                    onDelete = { onDelete(file) },
                    transferActionsEnabled = transferActionsEnabled,
                    onShare = { onShare(file) },
                    onExport = { onExport(file) }
                )

                if (index != group.recordings.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RecordingItemCard(
    file: RecordingFile,
    playback: PlaybackState?,
    playbackEnabled: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Int) -> Unit,
    onDelete: () -> Unit,
    transferActionsEnabled: Boolean,
    onShare: () -> Unit,
    onExport: () -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by remember(file.path) { mutableStateOf(false) }
    val moreDescription = stringResource(R.string.recording_more_actions, file.name)

    Column(
        modifier = Modifier.fillMaxWidth().testTag("recording:${file.name}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            friendlyRecordingLabel(context, file),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Text(
            "${formatDateTime(context, file.lastModified)} / ${formatDuration(context, file.durationMs)} / ${formatFileSize(context, file.sizeBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            file.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (file.isCorrupted || !file.isFinalized) {
            Text(stringResource(R.string.recording_incomplete), color = MaterialTheme.colorScheme.error)
        }
        if (playback != null) {
            PlaybackProgress(playback = playback, onSeek = onSeek)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onPlayPause,
                enabled = playbackEnabled && file.isFinalized && !file.isCorrupted && playback?.isLoading != true,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    stringResource(
                        when {
                            !playbackEnabled -> R.string.playback_stop_listening_first
                            playback?.isLoading == true -> R.string.playback_loading
                            playback?.isPlaying == true -> R.string.action_pause_playback
                            playback?.isComplete == true -> R.string.action_replay
                            playback != null && playback.positionMs > 0 -> R.string.action_resume_playback
                            else -> R.string.action_play
                        }
                    )
                )
            }
            Box(Modifier.weight(1f)) {
                TextButton(
                    onClick = { menuExpanded = true },
                    enabled = transferActionsEnabled,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = moreDescription }
                ) { Text(stringResource(R.string.action_more)) }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_share)) },
                        enabled = transferActionsEnabled && file.isFinalized && !file.isCorrupted,
                        onClick = { menuExpanded = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_export)) },
                        enabled = transferActionsEnabled && file.isFinalized && !file.isCorrupted,
                        onClick = { menuExpanded = false; onExport() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        enabled = transferActionsEnabled,
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun TipCard(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusPill(
    text: String,
    active: Boolean
) {
    Surface(
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = MaterialTheme.shapes.large
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (active) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            }
        )
    }
}

@Composable
private fun InfoLine(
    label: String,
    value: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
