package com.viddown.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.viddown.app.R
import com.viddown.app.presentation.ListState
import com.viddown.app.ui.UiTags
import com.viddown.core.model.DownloadItemUi
import com.viddown.core.ui.VidDownIcons
import com.viddown.core.ui.components.EmptyState
import com.viddown.core.ui.components.ErrorState
import com.viddown.core.ui.components.LoadingState
import com.viddown.core.ui.theme.spacing
import com.viddown.engine.api.DownloadState

@Composable
fun DownloadsScreen(
    state: ListState<DownloadItemUi>,
    onDeleteRecord: (id: String) -> Unit,
    onClearUnsuccessful: () -> Unit,
    onCancel: (id: String) -> Unit,
    onRetry: (id: String) -> Unit,
) {
    var pendingCancel by remember { mutableStateOf<DownloadItemUi?>(null) }
    DownloadsContent(state, onDeleteRecord, onClearUnsuccessful, onRetry, onAskCancel = { pendingCancel = it })

    val cancelling = pendingCancel
    if (cancelling != null) {
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            title = { Text(stringResource(R.string.downloads_cancel_title)) },
            text = { Text(stringResource(R.string.downloads_cancel_message, cancelling.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCancel(cancelling.id)
                        pendingCancel = null
                    },
                    modifier = Modifier.testTag(UiTags.DOWNLOADS_CONFIRM_CANCEL),
                ) { Text(stringResource(R.string.downloads_cancel_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pendingCancel = null }) { Text(stringResource(R.string.downloads_keep)) } },
        )
    }
}

@Composable
private fun DownloadsContent(
    state: ListState<DownloadItemUi>,
    onDeleteRecord: (id: String) -> Unit,
    onClearUnsuccessful: () -> Unit,
    onRetry: (id: String) -> Unit,
    onAskCancel: (DownloadItemUi) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    when (state) {
        is ListState.Loading -> LoadingState(label = stringResource(R.string.loading))
        is ListState.Empty -> EmptyState(
            iconRes = VidDownIcons.Downloads,
            title = stringResource(R.string.downloads_empty_title),
            message = stringResource(R.string.downloads_empty_message),
            modifier = Modifier.testTag(UiTags.DOWNLOADS_EMPTY),
        )
        is ListState.Error -> ErrorState(
            title = state.message,
            message = stringResource(R.string.error_try_again_later),
            technicalDetail = state.detail,
            modifier = Modifier.testTag(UiTags.DOWNLOADS_ERROR),
        )
        is ListState.Content -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (state.items.any { it.canRetry }) {
                item(key = "clear-unsuccessful") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClearUnsuccessful, modifier = Modifier.testTag(UiTags.DOWNLOADS_CLEAR)) {
                            Text(stringResource(R.string.downloads_clear_finished))
                        }
                    }
                }
            }
            items(state.items, key = { it.id }) { item -> DownloadCard(item, onDeleteRecord, onRetry, onAskCancel) }
        }
    }
}

@Composable
private fun DownloadCard(
    item: DownloadItemUi,
    onDeleteRecord: (String) -> Unit,
    onRetry: (String) -> Unit,
    onAskCancel: (DownloadItemUi) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Card(modifier = Modifier.fillMaxWidth().testTag(UiTags.download(item.id))) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(item.stateLabel, item.engineLabel, item.qualityLabel).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val progress = item.progress
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.PREPARING || item.state == DownloadState.PROCESSING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val numbers = listOfNotNull(item.progressText, item.speedText, item.etaText).joinToString(" · ")
            if (numbers.isNotEmpty()) Text(numbers, style = MaterialTheme.typography.bodySmall)
            item.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            item.errorDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (item.canCancel || item.canRetry || item.canDeleteRecord) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.canCancel) {
                        TextButton(onClick = { onAskCancel(item) }, modifier = Modifier.testTag(UiTags.downloadCancel(item.id))) {
                            Text(stringResource(R.string.downloads_cancel))
                        }
                    }
                    if (item.canRetry) {
                        TextButton(onClick = { onRetry(item.id) }, modifier = Modifier.testTag(UiTags.downloadRetry(item.id))) {
                            Text(stringResource(R.string.downloads_retry))
                        }
                    }
                    if (item.canDeleteRecord) {
                        TextButton(onClick = { onDeleteRecord(item.id) }, modifier = Modifier.testTag(UiTags.downloadDelete(item.id))) {
                            Text(stringResource(R.string.downloads_delete_record))
                        }
                    }
                }
            }
        }
    }
}
