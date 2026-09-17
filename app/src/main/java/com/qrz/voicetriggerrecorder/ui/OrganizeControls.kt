package com.qrz.voicetriggerrecorder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qrz.voicetriggerrecorder.R

@Composable
internal fun OrganizeControls(
    state: RecordingHistoryState,
    enabled: Boolean,
    onMode: () -> Unit,
    onSelect: (List<String>) -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onExport: () -> Unit
) {
    val context = LocalContext.current
    val selected = state.files.filter { it.path in state.selected }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.storage_overview), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.storage_totals, state.files.size,
                formatFileSize(context, state.files.sumOf { it.sizeBytes }),
                formatFileSize(context, state.files.filter { it.isFavorite }.sumOf { it.sizeBytes }),
                formatFileSize(context, state.files.filterNot { it.isFavorite }.sumOf { it.sizeBytes })))
            Text(stringResource(R.string.storage_scope), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onMode, enabled = enabled && state.files.isNotEmpty()) {
                Text(stringResource(if (state.selecting) R.string.finish_selecting else R.string.batch_organize))
            }
            if (state.selecting) {
                Text(stringResource(R.string.selection_summary, selected.size,
                    formatFileSize(context, selected.sumOf { it.sizeBytes }),
                    formatFileSize(context, selected.filterNot { it.isFavorite }.sumOf { it.sizeBytes })))
                TextButton(onClick = { onSelect(state.files.map { it.path }) }, enabled = enabled) {
                    Text(stringResource(if (state.selected.size == state.files.size) R.string.deselect_all else R.string.select_all))
                }
                TextButton(onClick = onExport,
                    enabled = enabled && selected.isNotEmpty() && selected.all { it.isFinalized && !it.isCorrupted }) {
                    Text(stringResource(R.string.export_selected))
                }
                TextButton(onClick = onDelete, enabled = enabled && selected.any { !it.isFavorite }) {
                    Text(stringResource(R.string.delete_selected))
                }
                if (selected.any { !it.isFinalized || it.isCorrupted }) {
                    Text(stringResource(R.string.archive_unavailable))
                }
            }
            if (state.deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.favoriteFailed) Text(stringResource(R.string.favorite_failed), color = MaterialTheme.colorScheme.error)
            state.batchResult?.let {
                Text(stringResource(R.string.batch_result, it.deleted, it.protected, it.failed))
                if (state.batchRetry.isNotEmpty()) {
                    TextButton(onClick = onRetry, enabled = enabled) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
    }
}
