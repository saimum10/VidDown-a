package com.viddown.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.viddown.app.R
import com.viddown.app.RuntimeCheckActivity
import com.viddown.app.presentation.EngineSettingsUiState
import com.viddown.app.ui.UiTags
import com.viddown.core.model.AppSettings
import com.viddown.core.model.SearchEngine
import com.viddown.core.model.Formatters
import com.viddown.core.model.SettingsValidator
import com.viddown.core.model.ThemeMode
import com.viddown.core.ui.theme.spacing
import com.viddown.engine.api.EngineAvailability
import com.viddown.engine.api.EngineMode
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Settings. Only settings that really work are shown; the others get their controls together with the feature that
 * uses them (default quality arrives with the engines that offer a choice of quality).
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    engineState: EngineSettingsUiState,
    message: String?,
    onThemeChange: (ThemeMode) -> Unit,
    onChooseEngine: (EngineMode) -> Unit,
    onClearHistory: () -> Unit,
    onClearCookies: () -> Unit,
    onClearCache: () -> Unit,
    onOpenAbout: () -> Unit,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    tempBytes: Long?,
    onRefreshStorage: () -> Unit,
    onCleanTemporary: () -> Unit,
    onClearDownloads: () -> Unit,
    onFolderPicked: (Uri) -> Unit,
    onResetFolder: () -> Unit,
    onBuildDiagnostics: suspend () -> String,
    storageAccess: StorageAccessState,
) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmClearCookies by remember { mutableStateOf(false) }
    var confirmCleanTemp by remember { mutableStateOf(false) }
    var confirmClearDownloads by remember { mutableStateOf(false) }
    var showEngineDialog by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) onFolderPicked(uri)
    }
    LaunchedEffect(Unit) { onRefreshStorage() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(spacing.lg),
    ) {
        Title(stringResource(R.string.settings_appearance))
        Column(Modifier.selectableGroup()) {
            ThemeOption(ThemeMode.SYSTEM, R.string.theme_system, settings.themeMode, onThemeChange)
            ThemeOption(ThemeMode.LIGHT, R.string.theme_light, settings.themeMode, onThemeChange)
            ThemeOption(ThemeMode.DARK, R.string.theme_dark, settings.themeMode, onThemeChange)
        }

        Title(stringResource(R.string.settings_downloads))
        engineState.warning?.let { warning ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = spacing.sm).testTag(UiTags.ENGINE_BANNER),
            ) {
                Text(warning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(spacing.md))
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = spacing.touchTarget)
                .clickable { showEngineDialog = true }
                .padding(vertical = spacing.sm)
                .testTag(UiTags.SETTINGS_ENGINE_ROW),
        ) {
            Text(stringResource(R.string.settings_engine), style = MaterialTheme.typography.bodyLarge)
            Text(
                engineState.engines.firstOrNull { it.selected }?.label ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        SwitchRow(
            label = stringResource(R.string.settings_ask_before),
            checked = settings.askBeforeDownloading,
            tag = UiTags.SETTINGS_ASK_BEFORE,
        ) { value -> onSettingsChange { it.copy(askBeforeDownloading = value) } }
        SwitchRow(
            label = stringResource(R.string.settings_wifi_only),
            checked = settings.wifiOnly,
            tag = UiTags.SETTINGS_WIFI_ONLY,
        ) { value -> onSettingsChange { it.copy(wifiOnly = value) } }
        SwitchRow(
            label = stringResource(R.string.settings_notifications),
            checked = settings.notificationsEnabled,
            tag = UiTags.SETTINGS_NOTIFICATIONS,
        ) { value -> onSettingsChange { it.copy(notificationsEnabled = value) } }

        var sliderValue by remember(settings.maxSimultaneousDownloads) { mutableFloatStateOf(settings.maxSimultaneousDownloads.toFloat()) }
        Text(
            stringResource(R.string.settings_max_downloads, sliderValue.roundToInt()),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = spacing.md),
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                val n = SettingsValidator.coerceMaxDownloads(sliderValue.roundToInt())
                sliderValue = n.toFloat()
                if (n != settings.maxSimultaneousDownloads) onSettingsChange { it.copy(maxSimultaneousDownloads = n) }
            },
            valueRange = SettingsValidator.MIN_DOWNLOADS.toFloat()..SettingsValidator.MAX_DOWNLOADS.toFloat(),
            steps = SettingsValidator.MAX_DOWNLOADS - SettingsValidator.MIN_DOWNLOADS - 1,
            modifier = Modifier.testTag(UiTags.SETTINGS_MAX_DOWNLOADS_SLIDER),
        )

        Text(
            stringResource(R.string.settings_location),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = spacing.lg),
        )
        Text(
            when {
                settings.downloadTreeUri != null -> stringResource(R.string.settings_location_custom)
                storageAccess.granted -> stringResource(R.string.settings_location_main)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> stringResource(R.string.settings_location_downloads)
                else -> stringResource(R.string.settings_location_app)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.padding(top = spacing.xs)) {
            OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.testTag(UiTags.SETTINGS_CHOOSE_FOLDER)) {
                Text(stringResource(R.string.settings_location_choose))
            }
            if (settings.downloadTreeUri != null) {
                OutlinedButton(onClick = onResetFolder, modifier = Modifier.testTag(UiTags.SETTINGS_RESET_FOLDER)) {
                    Text(stringResource(R.string.settings_location_reset))
                }
            }
        }
        if (settings.downloadTreeUri == null && !storageAccess.granted) {
            OutlinedButton(
                onClick = storageAccess.request,
                modifier = Modifier.padding(top = spacing.xs).testTag(UiTags.SETTINGS_ALLOW_STORAGE),
            ) { Text(stringResource(R.string.settings_allow_storage)) }
        }

        Title(stringResource(R.string.settings_storage))
        Text(
            when {
                tempBytes == null -> ""
                tempBytes > 0 -> stringResource(R.string.settings_temp_usage, Formatters.bytes(tempBytes) ?: "")
                else -> stringResource(R.string.settings_temp_none)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionRow(stringResource(R.string.settings_temp_clean), UiTags.SETTINGS_CLEAN_TEMP) { confirmCleanTemp = true }
        ActionRow(stringResource(R.string.settings_clear_downloads), UiTags.SETTINGS_CLEAR_DOWNLOADS) { confirmClearDownloads = true }

        Title(stringResource(R.string.settings_browser))
        var homepageText by remember(settings.homepage) { mutableStateOf(settings.homepage) }
        var homepageInvalid by remember { mutableStateOf(false) }
        Text(stringResource(R.string.settings_homepage), style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            OutlinedTextField(
                value = homepageText,
                onValueChange = {
                    homepageText = it
                    homepageInvalid = false
                },
                singleLine = true,
                isError = homepageInvalid,
                supportingText = if (homepageInvalid) {
                    { Text(stringResource(R.string.settings_homepage_invalid)) }
                } else {
                    null
                },
                modifier = Modifier.weight(1f).testTag(UiTags.SETTINGS_HOMEPAGE),
            )
            TextButton(
                onClick = {
                    val normalized = SettingsValidator.normalizeHomepage(homepageText)
                    if (normalized == null) {
                        homepageInvalid = true
                    } else {
                        homepageText = normalized
                        onSettingsChange { it.copy(homepage = normalized) }
                    }
                },
                modifier = Modifier.defaultMinSize(minHeight = spacing.touchTarget).testTag(UiTags.SETTINGS_HOMEPAGE_SAVE),
            ) { Text(stringResource(R.string.settings_homepage_save)) }
        }
        Text(stringResource(R.string.settings_search_engine), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = spacing.sm))
        Column(Modifier.selectableGroup()) {
            SearchEngine.entries.forEach { engine ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = spacing.touchTarget)
                        .selectable(
                            selected = engine == settings.searchEngine,
                            onClick = { onSettingsChange { it.copy(searchEngine = engine) } },
                            role = Role.RadioButton,
                        )
                        .testTag(UiTags.searchEngine(engine.storageKey)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = engine == settings.searchEngine, onClick = null)
                    Text(engine.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = spacing.md))
                }
            }
        }
        SwitchRow(
            stringResource(R.string.settings_javascript),
            settings.javascriptEnabled,
            UiTags.SETTINGS_JAVASCRIPT,
        ) { value -> onSettingsChange { it.copy(javascriptEnabled = value) } }
        ActionRow(stringResource(R.string.settings_clear_history), UiTags.SETTINGS_CLEAR_HISTORY) { confirmClear = true }
        ActionRow(stringResource(R.string.settings_clear_cookies), UiTags.SETTINGS_CLEAR_COOKIES) { confirmClearCookies = true }
        ActionRow(stringResource(R.string.settings_clear_cache), UiTags.SETTINGS_CLEAR_CACHE, onClearCache)

        Title(stringResource(R.string.settings_diagnostics))
        ActionRow(stringResource(R.string.settings_runtime_check), "settings_runtime_check") {
            context.startActivity(Intent(context, RuntimeCheckActivity::class.java))
        }
        ActionRow(stringResource(R.string.settings_share_diagnostics), UiTags.SETTINGS_SHARE_DIAGNOSTICS) {
            scope.launch {
                val report = onBuildDiagnostics()
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                context.startActivity(Intent.createChooser(send, null))
            }
        }

        Title(stringResource(R.string.settings_about))
        ActionRow(stringResource(R.string.settings_about_open), UiTags.SETTINGS_ABOUT, onOpenAbout)

        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = spacing.lg).testTag(UiTags.SETTINGS_MESSAGE))
        }
    }

    if (showEngineDialog) {
        AlertDialog(
            onDismissRequest = { showEngineDialog = false },
            title = { Text(stringResource(R.string.settings_engine)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    engineState.engines.forEach { engine ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = spacing.touchTarget)
                                .alpha(if (engine.selectable) 1f else 0.6f)
                                .selectable(
                                    selected = engine.selected && engine.selectable,
                                    enabled = engine.selectable,
                                    onClick = {
                                        onChooseEngine(engine.mode)
                                        showEngineDialog = false
                                    },
                                    role = Role.RadioButton,
                                )
                                .testTag(UiTags.engineOption(engine.mode.storageKey)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = engine.selected && engine.selectable, onClick = null, enabled = engine.selectable)
                            Column(Modifier.padding(start = spacing.md)) {
                                Text(engine.label, style = MaterialTheme.typography.bodyLarge)
                                (engine.availability as? EngineAvailability.NotAvailable)?.let { na ->
                                    Text(na.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (engine.mode == EngineMode.AUTO) {
                                    Text(engine.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showEngineDialog = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }

    if (confirmCleanTemp) {
        AlertDialog(
            onDismissRequest = { confirmCleanTemp = false },
            title = { Text(stringResource(R.string.settings_temp_clean)) },
            text = { Text(stringResource(R.string.settings_temp_clean_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCleanTemporary()
                        confirmCleanTemp = false
                    },
                    modifier = Modifier.testTag(UiTags.SETTINGS_CONFIRM_CLEAN_TEMP),
                ) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmCleanTemp = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (confirmClearDownloads) {
        AlertDialog(
            onDismissRequest = { confirmClearDownloads = false },
            title = { Text(stringResource(R.string.settings_clear_downloads)) },
            text = { Text(stringResource(R.string.settings_clear_downloads_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearDownloads()
                        confirmClearDownloads = false
                    },
                    modifier = Modifier.testTag(UiTags.SETTINGS_CONFIRM_CLEAR_DOWNLOADS),
                ) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClearDownloads = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (confirmClearCookies) {
        AlertDialog(
            onDismissRequest = { confirmClearCookies = false },
            title = { Text(stringResource(R.string.settings_clear_cookies)) },
            text = { Text(stringResource(R.string.settings_clear_cookies_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearCookies()
                        confirmClearCookies = false
                    },
                    modifier = Modifier.testTag(UiTags.SETTINGS_CONFIRM_CLEAR_COOKIES),
                ) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClearCookies = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_clear_history)) },
            text = { Text(stringResource(R.string.settings_clear_history_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        confirmClear = false
                    },
                    modifier = Modifier.testTag(UiTags.SETTINGS_CONFIRM_CLEAR),
                ) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = MaterialTheme.spacing.lg, bottom = MaterialTheme.spacing.xs),
    )
}

@Composable
private fun ActionRow(label: String, tag: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MaterialTheme.spacing.touchTarget)
            .clickable(onClick = onClick)
            .padding(vertical = MaterialTheme.spacing.md)
            .testTag(tag),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = spacing.touchTarget)
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(vertical = spacing.sm)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = spacing.md))
    }
}

@Composable
private fun ThemeOption(mode: ThemeMode, label: Int, current: ThemeMode, onChange: (ThemeMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MaterialTheme.spacing.touchTarget)
            .selectable(selected = mode == current, onClick = { onChange(mode) }, role = Role.RadioButton)
            .testTag("theme_${mode.storageKey}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = mode == current, onClick = null)
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = MaterialTheme.spacing.md),
        )
    }
}
