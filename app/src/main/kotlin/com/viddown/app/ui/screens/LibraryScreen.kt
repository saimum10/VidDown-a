package com.viddown.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.viddown.app.R
import com.viddown.app.download.FileActions
import com.viddown.app.presentation.LibraryItemUi
import com.viddown.app.presentation.LibraryUiState
import com.viddown.app.presentation.ListState
import com.viddown.app.ui.UiTags
import com.viddown.core.model.LibraryFilter
import com.viddown.core.model.LibrarySort
import com.viddown.core.ui.VidDownIcons
import com.viddown.core.ui.components.EmptyState
import com.viddown.core.ui.components.ErrorState
import com.viddown.core.ui.components.LoadingState
import com.viddown.core.ui.theme.spacing
import java.text.DateFormat
import java.util.Date

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onSearch: (String) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onFilter: (LibraryFilter) -> Unit,
    onRemoveEntry: (id: String) -> Unit,
    missing: Set<String>,
    message: String?,
    onDeleteFile: (id: String) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    var pendingRemove by remember { mutableStateOf<LibraryItemUi?>(null) }
    var pendingDeleteFile by remember { mutableStateOf<LibraryItemUi?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    fun launch(item: LibraryItemUi, share: Boolean) {
        val location = item.outputPath ?: return
        val intent = if (share) FileActions.shareIntent(context, location) else FileActions.openIntent(context, location)
        if (intent == null || !FileActions.launch(context, intent)) {
            Toast.makeText(context, R.string.file_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = spacing.lg)) {
        OutlinedTextField(
            value = state.query.search,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.md).testTag(UiTags.LIBRARY_SEARCH),
            singleLine = true,
            label = { Text(stringResource(R.string.library_search)) },
        )
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.sm).testTag(UiTags.LIBRARY_MESSAGE),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.query.filter == filter,
                    onClick = { onFilter(filter) },
                    label = { Text(filter.label) },
                )
            }
            Box {
                OutlinedButton(onClick = { sortMenuOpen = true }) { Text(state.query.sort.label) }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    LibrarySort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.label) },
                            onClick = {
                                onSort(sort)
                                sortMenuOpen = false
                            },
                        )
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val list = state.list) {
                is ListState.Loading -> LoadingState(label = stringResource(R.string.loading))
                is ListState.Empty -> if (state.isFiltered) {
                    EmptyState(
                        iconRes = VidDownIcons.Library,
                        title = stringResource(R.string.library_no_match_title),
                        message = stringResource(R.string.library_no_match_message),
                        modifier = Modifier.testTag(UiTags.LIBRARY_NO_MATCH),
                    )
                } else {
                    EmptyState(
                        iconRes = VidDownIcons.Library,
                        title = stringResource(R.string.library_empty_title),
                        message = stringResource(R.string.library_empty_message),
                        modifier = Modifier.testTag(UiTags.LIBRARY_EMPTY),
                    )
                }
                is ListState.Error -> ErrorState(title = list.message, message = stringResource(R.string.error_try_again_later), technicalDetail = list.detail)
                is ListState.Content -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    items(list.items, key = { it.id }) { item ->
                        Card(modifier = Modifier.fillMaxWidth().testTag(UiTags.libraryItem(item.id))) {
                            Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (item.details.isNotEmpty()) {
                                    Text(item.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.createdAtMillis)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val isMissing = item.id in missing
                                if (isMissing) {
                                    Text(
                                        stringResource(R.string.library_file_missing),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Text(
                                        stringResource(R.string.file_missing_message),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!isMissing && item.outputPath != null) {
                                        TextButton(onClick = { launch(item, share = false) }, modifier = Modifier.testTag(UiTags.libraryOpen(item.id))) {
                                            Text(stringResource(R.string.downloads_open))
                                        }
                                        TextButton(onClick = { launch(item, share = true) }, modifier = Modifier.testTag(UiTags.libraryShare(item.id))) {
                                            Text(stringResource(R.string.downloads_share))
                                        }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { pendingRemove = item }, modifier = Modifier.testTag(UiTags.libraryRemove(item.id))) {
                                        Text(stringResource(R.string.library_remove_entry))
                                    }
                                    if (!isMissing && item.outputPath != null) {
                                        TextButton(onClick = { pendingDeleteFile = item }, modifier = Modifier.testTag(UiTags.libraryDeleteFile(item.id))) {
                                            Text(stringResource(R.string.library_delete_file))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val deleting = pendingDeleteFile
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteFile = null },
            title = { Text(stringResource(R.string.library_delete_file_title)) },
            text = { Text(stringResource(R.string.library_delete_file_message, deleting.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteFile(deleting.id)
                        pendingDeleteFile = null
                    },
                    modifier = Modifier.testTag(UiTags.LIBRARY_CONFIRM_DELETE_FILE),
                ) { Text(stringResource(R.string.library_delete_file)) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteFile = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    val removing = pendingRemove
    if (removing != null) {
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(stringResource(R.string.library_remove_title)) },
            text = { Text(stringResource(R.string.library_remove_message, removing.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveEntry(removing.id)
                        pendingRemove = null
                    },
                    modifier = Modifier.testTag(UiTags.LIBRARY_CONFIRM_REMOVE),
                ) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
