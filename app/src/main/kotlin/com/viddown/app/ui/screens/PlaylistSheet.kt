package com.viddown.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viddown.app.R
import com.viddown.app.presentation.LinkState
import com.viddown.app.presentation.PlaylistQuality
import com.viddown.app.ui.UiTags
import com.viddown.core.model.Formatters
import com.viddown.core.ui.theme.spacing
import com.viddown.engine.api.PlaylistEntry

/**
 * A playlist link: every video of the playlist (at most 100) with a tick box, "Select all" / "Unselect all" on top, and one button
 * that downloads the ticked videos. While they are being added it shows how far it is, and afterwards how many were added.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSheet(
    link: LinkState,
    onToggleEntry: (String) -> Unit,
    onToggleAll: () -> Unit,
    /** "Download selected": opens the quality step; nothing is queued yet. */
    onChooseQuality: () -> Unit,
    onSelectQuality: (String) -> Unit,
    /** Back from the quality step to the list of videos. */
    onCloseQuality: () -> Unit,
    /** The quality step's Download button: the ticked videos are queued in the chosen quality. */
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onOpenDownloads: () -> Unit,
    /** Shown under the Download button when the address is one video of the playlist (the in-app browser): saves only that video. */
    onSingleVideo: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(UiTags.PLAYLIST_SHEET),
    ) {
        when (link) {
            is LinkState.Playlist ->
                if (link.choosingQuality) {
                    PlaylistQualityContent(link, onSelectQuality, onCloseQuality, onConfirm)
                } else {
                    PlaylistPicker(link, onToggleEntry, onToggleAll, onChooseQuality, onSingleVideo)
                }
            is LinkState.QueueingPlaylist -> PlaylistProgress(link, onDismiss)
            is LinkState.PlaylistQueued -> PlaylistResult(link, onOpenDownloads, onDismiss)
            else -> Unit
        }
    }
}

@Composable
private fun PlaylistPicker(
    link: LinkState.Playlist,
    onToggleEntry: (String) -> Unit,
    onToggleAll: () -> Unit,
    onChooseQuality: () -> Unit,
    onSingleVideo: (() -> Unit)?,
) {
    val spacing = MaterialTheme.spacing
    val entries = link.info.entries
    // The list scrolls inside the sheet, so a bounded height keeps the buttons in view.
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    val count = if (link.info.hasMore) {
        stringResource(R.string.playlist_count_more, entries.size)
    } else {
        stringResource(R.string.playlist_count, entries.size)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .padding(horizontal = spacing.lg)
            .padding(bottom = spacing.xl),
    ) {
        Text(
            link.info.title ?: stringResource(R.string.playlist_title),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(link.info.uploader, count).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.playlist_selected, link.selectedCount, entries.size), style = MaterialTheme.typography.bodyMedium)
            // The label follows the state: "Select all" until every video is ticked, then "Unselect all".
            TextButton(onClick = onToggleAll, modifier = Modifier.testTag(UiTags.PLAYLIST_SELECT_ALL)) {
                Text(stringResource(if (link.allSelected) R.string.playlist_unselect_all else R.string.playlist_select_all))
            }
        }
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(entries, key = { it.url }) { entry ->
                PlaylistRow(entry, selected = entry.url in link.selected, onToggle = { onToggleEntry(entry.url) })
            }
        }
        Button(
            onClick = onChooseQuality,
            enabled = link.selectedCount > 0,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.md)
                .defaultMinSize(minHeight = spacing.touchTarget)
                .testTag(UiTags.PLAYLIST_DOWNLOAD),
        ) {
            Text(
                if (link.selectedCount > 0) {
                    stringResource(R.string.playlist_download, link.selectedCount)
                } else {
                    stringResource(R.string.playlist_download_none)
                },
            )
        }
        if (onSingleVideo != null) {
            TextButton(onClick = onSingleVideo, modifier = Modifier.fillMaxWidth().testTag(UiTags.PLAYLIST_ONLY_THIS)) {
                Text(stringResource(R.string.playlist_only_this))
            }
        }
    }
}

/**
 * The quality step of a playlist: the same look as the quality sheet of one video (Music and Video rows, "More formats", one Download
 * button), but the rows are qualities rather than one video's formats, because every video has its own. Each ticked video is saved in
 * the chosen quality, or in the closest one below it when it does not have exactly that.
 */
@Composable
private fun PlaylistQualityContent(
    link: LinkState.Playlist,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    var showAll by rememberSaveable { mutableStateOf(false) }
    // Back from "More formats" first goes to the short list, then to the list of videos.
    BackHandler { if (showAll) showAll = false else onBack() }
    val backLabel = stringResource(R.string.quality_back)
    val selected = link.qualityKey
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .padding(horizontal = spacing.lg)
            .padding(bottom = spacing.xl),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { if (showAll) showAll = false else onBack() },
                modifier = Modifier.size(spacing.touchTarget).testTag(UiTags.QUALITY_BACK).semantics { contentDescription = backLabel },
                contentPadding = PaddingValues(0.dp),
            ) { BackArrowIcon(LocalContentColor.current) }
            Text(
                stringResource(if (showAll) R.string.quality_more else R.string.playlist_quality_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = spacing.md),
            )
        }
        if (!showAll) {
            Text(
                stringResource(R.string.playlist_quality_for, link.selectedCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            if (showAll) {
                ChoiceSections(PlaylistQuality.audio, PlaylistQuality.video, selected, detailed = true, onSelect = onSelect)
            } else {
                ChoiceSections(PlaylistQuality.audio, PlaylistQuality.mainVideo(selected), selected, detailed = false, onSelect = onSelect)
                MoreFormatsRow { showAll = true }
            }
        }
        Text(
            stringResource(R.string.playlist_quality_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.sm),
        )
        Button(
            onClick = onConfirm,
            enabled = selected != null && link.selectedCount > 0,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.md)
                .defaultMinSize(minHeight = spacing.touchTarget)
                .testTag(UiTags.QUALITY_CONFIRM),
        ) {
            Text(stringResource(R.string.playlist_download, link.selectedCount))
        }
    }
}

/** One video: the tick box (the select icon) on the left, its title and, when known, uploader and length. The whole row toggles it. */
@Composable
private fun PlaylistRow(entry: PlaylistEntry, selected: Boolean, onToggle: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val name = entry.title ?: stringResource(R.string.playlist_untitled)
    val label = stringResource(R.string.playlist_select_entry, name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = spacing.touchTarget)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = spacing.xs)
            .testTag(UiTags.PLAYLIST_ENTRY),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.semantics { contentDescription = label })
        Column(Modifier.weight(1f).padding(start = spacing.md)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(entry.uploader, Formatters.duration(entry.durationSeconds)).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlaylistProgress(state: LinkState.QueueingPlaylist, onDismiss: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg).padding(bottom = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(UiTags.PLAYLIST_PROGRESS)) {
            CircularProgressIndicator(modifier = Modifier.padding(end = spacing.md), strokeWidth = 2.dp)
            Text(stringResource(R.string.playlist_queueing, state.done, state.total))
        }
        LinearProgressIndicator(
            progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
private fun PlaylistResult(state: LinkState.PlaylistQueued, onOpenDownloads: () -> Unit, onDismiss: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg).padding(bottom = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        val message = when {
            state.queued == 0 -> stringResource(R.string.playlist_queued_none)
            state.failed == 0 -> stringResource(R.string.playlist_queued_all, state.queued)
            else -> stringResource(R.string.playlist_queued_some, state.queued, state.failed)
        }
        Text(message, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag(UiTags.PLAYLIST_RESULT))
        state.firstProblem?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            if (state.queued > 0) Button(onClick = onOpenDownloads) { Text(stringResource(R.string.link_open_downloads)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    }
}
