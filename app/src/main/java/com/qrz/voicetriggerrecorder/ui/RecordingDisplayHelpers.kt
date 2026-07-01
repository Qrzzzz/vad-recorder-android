package com.qrz.voicetriggerrecorder.ui

import android.os.Build
import android.text.format.Formatter
import com.qrz.voicetriggerrecorder.R
import com.qrz.voicetriggerrecorder.record.RecordingFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class NightRecordingGroup(
    val nightDate: LocalDate,
    val recordings: List<RecordingFile>,
    val totalDurationMs: Long,
    val latestModified: Long
)

fun buildNightGroups(files: List<RecordingFile>): List<NightRecordingGroup> {
    if (files.isEmpty()) return emptyList()

    val zoneId = ZoneId.systemDefault()
    return files
        .sortedByDescending { it.lastModified }
        .groupBy { nightBucketDate(it.lastModified, zoneId) }
        .map { (nightDate, groupFiles) ->
            NightRecordingGroup(
                nightDate = nightDate,
                recordings = groupFiles.sortedByDescending { it.lastModified },
                totalDurationMs = groupFiles.sumOf { it.durationMs ?: 0L },
                latestModified = groupFiles.maxOf { it.lastModified }
            )
        }
        .sortedByDescending { it.nightDate }
}

private fun nightBucketDate(millis: Long, zoneId: ZoneId): LocalDate {
    val localDateTime = Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDateTime()
    return if (localDateTime.hour < 12) {
        localDateTime.toLocalDate().minusDays(1)
    } else {
        localDateTime.toLocalDate()
    }
}

fun friendlyRecordingLabel(
    context: android.content.Context,
    file: RecordingFile
): String {
    return context.getString(R.string.recording_clip_at, formatClockTime(context, file.lastModified))
}

fun formatNightSectionLabel(
    context: android.content.Context,
    date: LocalDate
): String {
    val formatter = DateTimeFormatter.ofPattern(
        context.getString(R.string.date_pattern_month_day),
        currentLocale(context)
    )
    return context.getString(R.string.night_of, date.format(formatter))
}

fun formatCountdown(
    context: android.content.Context,
    millis: Long
): String {
    val seconds = ((millis + 999L) / 1000L).coerceAtLeast(0L)
    val quantity = seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return context.resources.getQuantityString(R.plurals.duration_seconds, quantity, quantity)
}

fun formatDuration(
    context: android.content.Context,
    durationMs: Long?
): String {
    if (durationMs == null || durationMs <= 0L) {
        return context.getString(R.string.duration_unknown)
    }
    return formatKnownDuration(context, durationMs)
}

fun formatKnownDuration(
    context: android.content.Context,
    durationMs: Long
): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) {
        context.getString(R.string.duration_minutes_seconds, minutes, seconds)
    } else {
        val quantity = seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        context.resources.getQuantityString(R.plurals.duration_seconds, quantity, quantity)
    }
}

fun formatFileSize(
    context: android.content.Context,
    bytes: Long
): String {
    return Formatter.formatShortFileSize(context, bytes)
}

fun formatDateTime(
    context: android.content.Context,
    millis: Long
): String {
    val formatter = DateTimeFormatter.ofPattern(
        context.getString(R.string.date_pattern_date_time),
        currentLocale(context)
    )
    return Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}

fun formatClockTime(
    context: android.content.Context,
    millis: Long
): String {
    val formatter = DateTimeFormatter.ofPattern(
        context.getString(R.string.date_pattern_clock_time),
        currentLocale(context)
    )
    return Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}

fun formatClipCount(
    context: android.content.Context,
    count: Int
): String {
    return context.resources.getQuantityString(R.plurals.clip_count, count, count)
}

fun formatAutoStopNextSession(
    context: android.content.Context,
    hours: Int
): String {
    return context.resources.getQuantityString(R.plurals.status_auto_stop_next_session, hours, hours)
}

fun formatRecorderState(
    context: android.content.Context,
    uiState: RecorderUiState
): String {
    return when (uiState.recorderPhase) {
        RecorderPhase.IDLE -> context.getString(R.string.recorder_state_idle)
        RecorderPhase.LISTENING -> context.getString(R.string.recorder_state_listening)
        RecorderPhase.RECORDING -> context.getString(R.string.recorder_state_recording)
        RecorderPhase.WAITING_TO_FINISH -> context.getString(R.string.recorder_state_waiting_to_finish)
        RecorderPhase.SAVED -> {
            val fileName = uiState.lastSavedFileName ?: uiState.currentFileName
            if (fileName == null) {
                context.getString(R.string.recorder_state_listening)
            } else {
                context.getString(R.string.recorder_state_saved, fileName)
            }
        }
        RecorderPhase.MICROPHONE_SETUP_FAILED -> context.getString(R.string.recorder_state_microphone_setup_failed)
        RecorderPhase.RECORDER_FAILED -> context.getString(R.string.recorder_state_recorder_failed)
    }
}

private fun currentLocale(context: android.content.Context): Locale {
    val configuration = context.resources.configuration
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        configuration.locales.get(0) ?: Locale.getDefault()
    } else {
        @Suppress("DEPRECATION")
        configuration.locale
    }
}
