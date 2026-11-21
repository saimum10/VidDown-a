package com.viddown.app.ui.screens

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.viddown.app.R
import com.viddown.app.presentation.AdvancedOptions
import com.viddown.app.presentation.DownloadTarget
import com.viddown.app.presentation.HomeAction
import com.viddown.app.presentation.HomePresenter
import com.viddown.app.presentation.HomeUiState
import com.viddown.app.presentation.LinkState
import com.viddown.app.presentation.QualityState
import com.viddown.app.ui.UiTags
import com.viddown.core.model.SearchEngine
import com.viddown.core.ui.VidDownIcons
import com.viddown.core.ui.theme.spacing

@Composable
fun HomeScreen(
    state: HomeUiState?,
    link: LinkState,
    notificationsEnabled: Boolean,
    onOpenBrowser: (url: String?) -> Unit,
    onOpenDownloads: () -> Unit,
    onCheckLink: (String) -> Unit,
    onConfirmLink: () -> Unit,
    onDismissLink: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectLinkFormat: (String) -> Unit = {},
    onLinkAdvanced: (AdvancedOptions) -> Unit = {},
    onPrefetchClipboardLink: (String) -> Unit = {},
    onLinkTextChanged: (String) -> Unit = {},
    onTogglePlaylistEntry: (String) -> Unit = {},
    onTogglePlaylistAll: () -> Unit = {},
    onChoosePlaylistQuality: () -> Unit = {},
    onSelectPlaylistQuality: (String) -> Unit = {},
    onClosePlaylistQuality: () -> Unit = {},
    onConfirmPlaylist: () -> Unit = {},
) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    var showLink by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Right after the first download is queued is the natural moment to ask for notifications (Android 13+).
    LaunchedEffect(link) {
        if (link is LinkState.Queued && notificationsEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val searchEngine = state?.searchEngine ?: SearchEngine.DUCKDUCKGO

    fun go() {
        when (val action = HomePresenter.interpret(text, searchEngine)) {
            is HomeAction.OpenInBrowser -> {
                error = null
                onOpenBrowser(action.url)
            }
            is HomeAction.ShowError -> error = action.message
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.viddown_logo),
                contentDescription = null,
                modifier = Modifier.height(48.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        }

        val currentError = error
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            modifier = Modifier.fillMaxWidth().testTag(UiTags.HOME_URL_FIELD),
            singleLine = true,
            label = { Text(stringResource(R.string.home_address_hint)) },
            isError = currentError != null,
            supportingText = if (currentError != null) {
                { Text(currentError, modifier = Modifier.testTag(UiTags.HOME_ERROR)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            OutlinedButton(
                onClick = {
                    val pasted = readClipboardText(context)
                    if (pasted != null) {
                        text = pasted
                        error = null
                    }
                },
                modifier = Modifier.testTag(UiTags.HOME_PASTE),
            ) { Icon(painterResource(VidDownIcons.Paste), contentDescription = stringResource(R.string.action_paste)) }
            Button(onClick = { go() }, modifier = Modifier.testTag(UiTags.HOME_GO)) { Text(stringResource(R.string.action_go)) }
        }

        OutlinedButton(onClick = { showLink = true }, modifier = Modifier.testTag(UiTags.HOME_LINK_BUTTON)) {
            Text(stringResource(R.string.link_button))
        }

        SectionHeader(stringResource(R.string.home_active_downloads))
        Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDownloads)) {
            Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                val active = state?.active
                if (active != null && active.hasWork) {
                    Text("${active.active} downloading · ${active.queued} queued", style = MaterialTheme.typography.bodyLarge)
                    val progress = active.progress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    Text(
                        stringResource(R.string.home_no_active),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(UiTags.HOME_ACTIVE_NONE),
                    )
                }
            }
        }

        SectionHeader(stringResource(R.string.home_recent_pages))
        val pages = state?.recentPages.orEmpty()
        if (pages.isEmpty()) {
            Text(
                stringResource(R.string.home_recent_pages_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.HOME_RECENT_PAGES_EMPTY),
            )
        } else {
            pages.forEach { page ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenBrowser(page.url) }
                        .padding(vertical = spacing.sm),
                ) {
                    Text(page.title ?: page.url, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (page.title != null) {
                        Text(page.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            OutlinedButton(onClick = { onOpenBrowser(null) }) { Text(stringResource(R.string.nav_browser)) }
            OutlinedButton(onClick = onOpenDownloads) { Text(stringResource(R.string.nav_downloads)) }
        }
    }

    if (showLink) {
        val close = {
            showLink = false
            onDismissLink()
        }
        when (link) {
            // Once the link has been read, the qualities are shown in the very same sheet as in the browser.
            is LinkState.Ready -> QualitySheet(
                state = link.quality,
                onSelect = onSelectLinkFormat,
                onConfirm = onConfirmLink,
                onDismiss = close,
                onOpenDownloads = {
                    close()
                    onOpenDownloads()
                },
                onOpenSettings = {
                    close()
                    onOpenSettings()
                },
                onAdvanced = onLinkAdvanced,
            )
            is LinkState.Queued -> QualitySheet(
                state = QualityState.Queued(DownloadTarget("", null, null, null), automatic = false),
                onSelect = {},
                onConfirm = {},
                onDismiss = close,
                onOpenDownloads = {
                    close()
                    onOpenDownloads()
                },
                onOpenSettings = {
                    close()
                    onOpenSettings()
                },
            )
            // A playlist link: its videos are listed with a tick box each, and "Select all" / "Unselect all" on top.
            is LinkState.Playlist, is LinkState.QueueingPlaylist, is LinkState.PlaylistQueued -> PlaylistSheet(
                link = link,
                onToggleEntry = onTogglePlaylistEntry,
                onToggleAll = onTogglePlaylistAll,
                onChooseQuality = onChoosePlaylistQuality,
                onSelectQuality = onSelectPlaylistQuality,
                onCloseQuality = onClosePlaylistQuality,
                onConfirm = onConfirmPlaylist,
                onDismiss = close,
                onOpenDownloads = {
                    close()
                    onOpenDownloads()
                },
            )
            else -> LinkDialog(
                link = link,
                onCheck = onCheckLink,
                onConfirm = onConfirmLink,
                onSelectFormat = onSelectLinkFormat,
                onPrefetchClipboard = onPrefetchClipboardLink,
                onTextChanged = onLinkTextChanged,
                onDismiss = close,
                onOpenDownloads = {
                    close()
                    onOpenDownloads()
                },
                onOpenSettings = {
                    close()
                    onOpenSettings()
                },
            )
        }
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** The text of the last copied item, trimmed, or null when the clipboard is empty or cannot be read. */
internal fun readClipboardText(context: Context): String? = try {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
} catch (e: SecurityException) {
    null
}
