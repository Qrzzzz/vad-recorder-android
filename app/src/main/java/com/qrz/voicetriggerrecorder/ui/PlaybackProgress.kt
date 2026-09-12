package com.qrz.voicetriggerrecorder.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.qrz.voicetriggerrecorder.R
import java.util.Locale

internal fun formatPlaybackTime(milliseconds: Int): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
    }
}

@Composable
internal fun PlaybackProgress(playback: PlaybackState, onSeek: (Int) -> Unit) {
    var draftPosition by remember(playback.path) { mutableStateOf<Float?>(null) }
    val displayedPosition = (draftPosition?.toInt() ?: playback.positionMs).coerceIn(0, playback.durationMs)
    val progressText = stringResource(
        R.string.playback_time,
        formatPlaybackTime(displayedPosition),
        formatPlaybackTime(playback.durationMs)
    )
    val seekLabel = stringResource(R.string.playback_seek)
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = draftPosition ?: playback.positionMs.toFloat(),
            onValueChange = { draftPosition = it },
            onValueChangeFinished = {
                draftPosition?.let { onSeek(it.toInt()) }
                draftPosition = null
            },
            valueRange = 0f..playback.durationMs.coerceAtLeast(1).toFloat(),
            enabled = !playback.isLoading && playback.durationMs > 0,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = seekLabel
                stateDescription = progressText
            }
        )
        Text(
            text = progressText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
