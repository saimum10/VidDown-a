package com.viddown.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viddown.app.AppContainer
import com.viddown.core.model.AppSettings
import com.viddown.core.ui.theme.VidDownTheme
import kotlinx.coroutines.launch

/** Connects the persisted settings to the UI: the theme follows the stored choice immediately. */
@Composable
fun VidDownRoot(container: AppContainer, externalOpen: ExternalOpen? = null, onExternalHandled: () -> Unit = {}) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    VidDownTheme(themeMode = settings.themeMode) {
        VidDownApp(
            container = container,
            settings = settings,
            onSettingsChange = { change -> scope.launch { container.settings.update(change) } },
            externalOpen = externalOpen,
            onExternalHandled = onExternalHandled,
        )
    }
}
