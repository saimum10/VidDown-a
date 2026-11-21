package com.viddown.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viddown.app.AppContainer
import com.viddown.app.R
import com.viddown.app.ui.UiTags
import com.viddown.app.ui.viewmodels.BrowserPagesViewModel
import com.viddown.app.ui.viewmodels.containerViewModel
import com.viddown.browser.UrlPolicy
import com.viddown.core.ui.VidDownIcons
import com.viddown.core.ui.theme.spacing

/** Saved bookmarks and the list of visited pages. Choosing one opens it in the browser. */
@Composable
fun BrowserPagesScreen(container: AppContainer, onOpened: () -> Unit) {
    val vm = containerViewModel(container) { BrowserPagesViewModel(it) }
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val spacing = MaterialTheme.spacing

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selected) {
            Tab(selected = selected == 0, onClick = { selected = 0 }, text = { Text(stringResource(R.string.pages_bookmarks)) }, modifier = Modifier.testTag(UiTags.PAGES_TAB_BOOKMARKS))
            Tab(selected = selected == 1, onClick = { selected = 1 }, text = { Text(stringResource(R.string.pages_history)) }, modifier = Modifier.testTag(UiTags.PAGES_TAB_HISTORY))
        }
        if (selected == 0) {
            if (bookmarks.isEmpty()) {
                Text(
                    stringResource(R.string.pages_bookmarks_empty),
                    modifier = Modifier.padding(spacing.lg).testTag(UiTags.PAGES_BOOKMARKS_EMPTY),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(bookmarks, key = { _, b -> b.url }) { index, b ->
                        PageRow(
                            title = b.title, url = b.url, rowTag = UiTags.bookmarkItem(index), removeTag = UiTags.bookmarkRemove(index),
                            onOpen = { vm.open(b.url); onOpened() }, onRemove = { vm.removeBookmark(b.url) },
                        )
                    }
                }
            }
        } else {
            if (history.isEmpty()) {
                Text(
                    stringResource(R.string.pages_history_empty),
                    modifier = Modifier.padding(spacing.lg).testTag(UiTags.PAGES_HISTORY_EMPTY),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = spacing.sm), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                    TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.settings_clear_history)) }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(history, key = { _, v -> v.url }) { index, v ->
                        PageRow(
                            title = v.title, url = v.url, rowTag = UiTags.historyItem(index), removeTag = UiTags.historyRemove(index),
                            onOpen = { vm.open(v.url); onOpened() }, onRemove = { vm.removeHistoryEntry(v.url) },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_clear_history)) },
            text = { Text(stringResource(R.string.settings_clear_history_message)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearHistory()
                    confirmClear = false
                }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PageRow(title: String?, url: String, rowTag: String, removeTag: String, onOpen: () -> Unit, onRemove: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val label = title?.takeIf { it.isNotBlank() } ?: UrlPolicy.displayHost(url) ?: url
    Column {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = spacing.touchTarget).clickable(onClick = onOpen).padding(start = spacing.lg).testTag(rowTag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = spacing.sm)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onRemove, modifier = Modifier.testTag(removeTag)) {
                Icon(painterResource(VidDownIcons.Delete), contentDescription = stringResource(R.string.pages_remove, label))
            }
        }
        HorizontalDivider()
    }
}
