package com.viddown.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.viddown.app.R
import com.viddown.app.presentation.LinkState
import com.viddown.app.ui.UiTags
import com.viddown.core.ui.VidDownIcons
import com.viddown.core.ui.theme.spacing
import com.viddown.engine.api.SuggestedAction

/**
 * "Download from link": the link on the clipboard is read in the background as soon as the dialog opens, so "Try download" shows
 * the qualities at once; the paste icon puts that link in the field, and another link replaces the background read. Nothing is
 * queued before the engine has really inspected the link, and an unknown size is shown as unknown.
 */
@Composable
fun LinkDialog(
    link: LinkState,
    onCheck: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectFormat: (String) -> Unit = {},
    onPrefetchClipboard: (String) -> Unit = {},
    onTextChanged: (String) -> Unit = {},
) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }

    // The field starts empty; the clipboard link is only read in the background, it is not typed into the field.
    LaunchedEffect(Unit) {
        if (text.isBlank()) readClipboardText(context)?.let(onPrefetchClipboard)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                when (link) {
                    is LinkState.Queued -> Text(stringResource(R.string.link_queued), modifier = Modifier.testTag(UiTags.LINK_QUEUED))
                    LinkState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.link_checking))
                    }
                    // Home shows a read link in the quality sheet (the same one as the browser), never in this dialog.
                    is LinkState.Ready -> Unit
                    // Playlists are shown by PlaylistSheet (Home), never in this dialog.
                    is LinkState.Playlist, is LinkState.QueueingPlaylist, is LinkState.PlaylistQueued -> Unit
                    LinkState.Idle, is LinkState.Failed -> {
                        OutlinedTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                onTextChanged(it)
                            },
                            modifier = Modifier.fillMaxWidth().testTag(UiTags.LINK_FIELD),
                            singleLine = true,
                            label = { Text(stringResource(R.string.link_hint)) },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        readClipboardText(context)?.let {
                                            text = it
                                            onTextChanged(it)
                                        }
                                    },
                                    modifier = Modifier.testTag(UiTags.LINK_PASTE),
                                ) { Icon(painterResource(VidDownIcons.Paste), contentDescription = stringResource(R.string.action_paste)) }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { onCheck(text) }),
                        )
                        if (link is LinkState.Failed) {
                            Text(
                                link.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag(UiTags.LINK_ERROR),
                            )
                            link.detail?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (link.action == SuggestedAction.CHANGE_ENGINE) {
                                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.link_open_settings)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (link) {
                is LinkState.Queued -> TextButton(onClick = onOpenDownloads) { Text(stringResource(R.string.link_open_downloads)) }
                LinkState.Checking -> Unit
                is LinkState.Playlist, is LinkState.QueueingPlaylist, is LinkState.PlaylistQueued -> Unit
                is LinkState.Ready -> TextButton(onClick = onConfirm, enabled = link.format != null || link.rows.isEmpty(), modifier = Modifier.testTag(UiTags.LINK_DOWNLOAD)) {
                    Text(stringResource(R.string.link_download))
                }
                // Also works with an empty field: then the link read in the background from the clipboard is used.
                LinkState.Idle, is LinkState.Failed -> TextButton(
                    onClick = { onCheck(text) },
                    modifier = Modifier.testTag(UiTags.LINK_CHECK),
                ) { Text(stringResource(R.string.link_try_download)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (link is LinkState.Queued) R.string.action_close else R.string.action_cancel))
            }
        },
    )
}
