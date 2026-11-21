package com.viddown.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viddown.app.R
import com.viddown.app.presentation.AdvancedOptions
import com.viddown.app.presentation.DetectionItemUi
import com.viddown.app.presentation.DetectionsUi
import com.viddown.app.presentation.FormatGroup
import com.viddown.app.presentation.FormatRowUi
import com.viddown.app.presentation.QualityState
import com.viddown.app.ui.UiTags
import com.viddown.browser.DetectionStatus
import com.viddown.core.ui.theme.spacing
import com.viddown.engine.api.SuggestedAction

/** The media found on the current page. Only an item the selected engine can really download has a Download button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaListSheet(
    ui: DetectionsUi,
    onDownload: (DetectionItemUi) -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
    /** Set when the selected engine can read the page's own address (YouTube and similar): lists its qualities from the page link. */
    onDownloadPage: (() -> Unit)? = null,
) {
    val spacing = MaterialTheme.spacing
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(UiTags.MEDIA_SHEET),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.xl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.media_title), style = MaterialTheme.typography.titleLarge)
                    if (ui.engineLabel.isNotEmpty()) {
                        Text(
                            stringResource(R.string.media_engine, ui.engineLabel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (ui.checking) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                TextButton(onClick = onRescan, modifier = Modifier.testTag(UiTags.MEDIA_RESCAN)) { Text(stringResource(R.string.media_rescan)) }
            }
            ui.emptyExplanation?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = spacing.md).testTag(UiTags.MEDIA_EMPTY))
            }
            if (onDownloadPage != null) {
                Text(stringResource(R.string.media_page_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = onDownloadPage,
                    modifier = Modifier.defaultMinSize(minHeight = spacing.touchTarget).testTag(UiTags.MEDIA_DOWNLOAD_PAGE),
                ) { Text(stringResource(R.string.media_download_page)) }
            }
            ui.items.forEachIndexed { index, item ->
                HorizontalDivider()
                MediaRow(index, item, onDownload)
            }
            if (ui.dropped > 0) {
                Text(
                    stringResource(R.string.media_dropped, ui.dropped),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MediaRow(index: Int, item: DetectionItemUi, onDownload: (DetectionItemUi) -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(Modifier.fillMaxWidth().padding(vertical = spacing.sm).testTag(UiTags.mediaItem(index)), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
        Text(item.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (val status = item.status) {
            DetectionStatus.Downloadable -> Button(
                onClick = { onDownload(item) },
                modifier = Modifier.defaultMinSize(minHeight = spacing.touchTarget).testTag(UiTags.mediaDownload(index)),
            ) { Text(stringResource(R.string.media_download)) }
            is DetectionStatus.UnsupportedByEngine -> Explanation(status.explanation)
            is DetectionStatus.EngineUnavailable -> Explanation(status.explanation)
            is DetectionStatus.Protected -> Explanation(status.explanation)
        }
    }
}

@Composable
private fun Explanation(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** Quality selection and confirmation. Everything shown comes from the selected engine's own answer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySheet(
    state: QualityState,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Subtitle, codec or container was changed in the advanced options. */
    onAdvanced: (AdvancedOptions) -> Unit = {},
) {
    val spacing = MaterialTheme.spacing
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(UiTags.QUALITY_SHEET),
    ) {
        // The panel scrolls its own list so Download stays in view; the other states are short.
        if (state is QualityState.Ready) {
            QualityReadyContent(state, onSelect, onAdvanced, onConfirm)
        } else Column(
            modifier = Modifier
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.xl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            when (state) {
                QualityState.Hidden -> Unit
                is QualityState.Loading -> {
                    Text(stringResource(R.string.quality_title), style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(UiTags.QUALITY_LOADING)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.quality_loading), modifier = Modifier.padding(start = spacing.md))
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                }
                is QualityState.Ready -> Unit // shown by QualityReadyContent above
                is QualityState.Failed -> {
                    Text(stringResource(R.string.quality_title), style = MaterialTheme.typography.titleLarge)
                    Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(UiTags.QUALITY_ERROR))
                    state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        if (state.action == SuggestedAction.CHANGE_ENGINE) {
                            OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.link_open_settings)) }
                        }
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag(UiTags.QUALITY_CLOSE)) { Text(stringResource(R.string.quality_close)) }
                    }
                }
                is QualityState.Queued -> {
                    Text(stringResource(R.string.quality_queued), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag(UiTags.QUALITY_QUEUED))
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        Button(onClick = onOpenDownloads, modifier = Modifier.testTag(UiTags.QUALITY_OPEN_DOWNLOADS)) { Text(stringResource(R.string.link_open_downloads)) }
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag(UiTags.QUALITY_CLOSE)) { Text(stringResource(R.string.quality_close)) }
                    }
                }
            }
        }
    }
}

/** The options an engine listed, grouped (video + audio, video only, audio only), one selectable row each. Shared by the browser and Home. */
@Composable
internal fun FormatList(rows: List<FormatRowUi>, selectedId: String?, onSelect: (String) -> Unit) {
    val spacing = MaterialTheme.spacing
    var lastGroup: FormatGroup? = null
    rows.forEach { row ->
        if (row.group != lastGroup) {
            Text(row.group.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = spacing.sm))
            lastGroup = row.group
        }
        FormatRow(row, selected = row.id == selectedId, onSelect = onSelect)
    }
}

@Composable
private fun FormatRow(row: FormatRowUi, selected: Boolean, onSelect: (String) -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = spacing.touchTarget)
            .selectable(selected = selected, onClick = { onSelect(row.id) }, role = Role.RadioButton)
            .testTag(UiTags.qualityRow(row.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = spacing.md).weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge)
            Text(row.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The menu of a long press on a link or a video, like a browser's context menu: the address that was pressed (the one "Copy link"
 * copies) on top, then what can be done with it. "Download" reads that very address and opens the quality panel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkActionsSheet(
    url: String,
    onOpen: () -> Unit,
    onOpenInNewTab: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(UiTags.LINK_MENU),
    ) {
        Column(Modifier.padding(bottom = spacing.xl)) {
            Text(
                url,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
            )
            HorizontalDivider()
            LinkAction(R.string.browser_link_open, UiTags.LINK_MENU_OPEN, onOpen)
            LinkAction(R.string.browser_link_new_tab, UiTags.LINK_MENU_NEW_TAB, onOpenInNewTab)
            LinkAction(R.string.browser_link_copy, UiTags.LINK_MENU_COPY, onCopy)
            LinkAction(R.string.browser_link_share, UiTags.LINK_MENU_SHARE, onShare)
            LinkAction(R.string.browser_link_download, UiTags.LINK_MENU_DOWNLOAD, onDownload)
        }
    }
}

@Composable
private fun LinkAction(label: Int, tag: String, onClick: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Text(
        stringResource(label),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = spacing.touchTarget)
            .padding(horizontal = spacing.lg, vertical = spacing.md)
            .testTag(tag),
    )
}
