package com.viddown.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.viddown.app.AppContainer
import com.viddown.app.R
import com.viddown.app.ui.screens.AboutScreen
import com.viddown.app.ui.screens.BrowserPagesScreen
import com.viddown.app.ui.screens.BrowserScreen
import com.viddown.app.ui.screens.DownloadsScreen
import com.viddown.app.ui.screens.HomeScreen
import com.viddown.app.ui.screens.LibraryScreen
import com.viddown.app.ui.screens.LicensesScreen
import com.viddown.app.ui.screens.SettingsScreen
import com.viddown.app.ui.screens.StorageAccessDialog
import com.viddown.app.ui.screens.rememberStorageAccess
import com.viddown.app.ui.viewmodels.DownloadsViewModel
import com.viddown.app.ui.viewmodels.HomeViewModel
import com.viddown.app.ui.viewmodels.LibraryViewModel
import com.viddown.app.ui.viewmodels.SettingsViewModel
import com.viddown.app.ui.viewmodels.containerViewModel
import com.viddown.core.model.AppSettings
import com.viddown.core.ui.VidDownIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VidDownApp(
    container: AppContainer,
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    externalOpen: ExternalOpen? = null,
    onExternalHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val storageAccess = rememberStorageAccess()
    StorageAccessDialog(storageAccess, needed = settings.downloadTreeUri == null)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val route = currentDestination?.route
    val secondary = route in SECONDARY_ROUTES
    val currentTop = TopLevelDestination.entries.firstOrNull { dest ->
        currentDestination?.hierarchy?.any { it.route?.substringBefore('?') == dest.route } == true
    }

    fun openTab(dest: TopLevelDestination) {
        if (dest == TopLevelDestination.Home) {
            // Home is the start of the graph: go straight back to it and drop everything above it (the browser included).
            // The browser's tabs and pages live in the process-wide hub, so none of them is lost by leaving the screen.
            val reached = navController.popBackStack(TopLevelDestination.Home.route, inclusive = false)
            if (reached || navController.currentDestination?.route == TopLevelDestination.Home.route) return
        }
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Another app asked to open an address: show it in the browser, once.
    LaunchedEffect(externalOpen) {
        externalOpen?.let {
            navController.navigate(browserRoute(it.url)) { launchSingleTop = true }
            onExternalHandled()
        }
    }

    Scaffold(
        topBar = {
            // The browser draws its own address bar and toolbar; every other screen has the usual top bar.
            if (currentTop != TopLevelDestination.Browser || secondary) TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (route) {
                                SETTINGS_ROUTE -> R.string.nav_settings
                                ABOUT_ROUTE -> R.string.settings_about
                                LICENSES_ROUTE -> R.string.about_licenses
                                BROWSER_PAGES_ROUTE -> R.string.pages_title
                                else -> (currentTop ?: TopLevelDestination.Home).label
                            },
                        ),
                    )
                },
                navigationIcon = {
                    if (secondary) {
                        IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.testTag("action_back")) {
                            Icon(painterResource(VidDownIcons.Back), contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    if (!secondary) {
                        IconButton(
                            onClick = { navController.navigate(SETTINGS_ROUTE) { launchSingleTop = true } },
                            modifier = Modifier.testTag("action_settings"),
                        ) {
                            Icon(painterResource(VidDownIcons.Settings), contentDescription = stringResource(R.string.nav_settings))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!secondary) {
                SlimNavigationBar {
                    TopLevelDestination.entries.forEach { dest ->
                        SlimNavigationItem(
                            selected = dest == currentTop,
                            onClick = { openTab(dest) },
                            icon = dest.icon,
                            label = dest.label,
                            modifier = Modifier.testTag("nav_${dest.route}"),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelDestination.Home.route) {
                val vm = containerViewModel(container) { HomeViewModel(it) }
                val state by vm.state.collectAsStateWithLifecycle()
                val link by vm.link.collectAsStateWithLifecycle()
                Box(Modifier.testTag("screen_home")) {
                    HomeScreen(
                        state = state,
                        link = link,
                        notificationsEnabled = settings.notificationsEnabled,
                        onOpenBrowser = { url -> navController.navigate(browserRoute(url)) { launchSingleTop = true } },
                        onOpenDownloads = { openTab(TopLevelDestination.Downloads) },
                        onCheckLink = vm::checkLink,
                        onConfirmLink = vm::confirmLink,
                        onSelectLinkFormat = vm::selectLinkFormat,
                        onLinkAdvanced = vm::setLinkAdvanced,
                        onPrefetchClipboardLink = vm::prefetchClipboardLink,
                        onLinkTextChanged = vm::linkTextChanged,
                        onTogglePlaylistEntry = vm::toggleLinkPlaylistEntry,
                        onTogglePlaylistAll = vm::toggleLinkPlaylistAll,
                        onChoosePlaylistQuality = vm::chooseLinkPlaylistQuality,
                        onSelectPlaylistQuality = vm::selectLinkPlaylistQuality,
                        onClosePlaylistQuality = vm::closeLinkPlaylistQuality,
                        onConfirmPlaylist = vm::confirmLinkPlaylist,
                        onDismissLink = vm::dismissLink,
                        onOpenSettings = { navController.navigate(SETTINGS_ROUTE) { launchSingleTop = true } },
                    )
                }
            }
            composable(
                route = BROWSER_ROUTE_PATTERN,
                arguments = listOf(navArgument("url") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                Box(Modifier.testTag("screen_browser")) {
                    BrowserScreen(
                        container = container,
                        settings = settings,
                        entryKey = entry.id,
                        launchUrl = entry.arguments?.getString("url"),
                        onOpenHome = { openTab(TopLevelDestination.Home) },
                        onOpenDownloads = { openTab(TopLevelDestination.Downloads) },
                        onOpenSettings = { navController.navigate(SETTINGS_ROUTE) { launchSingleTop = true } },
                        onOpenPages = { navController.navigate(BROWSER_PAGES_ROUTE) { launchSingleTop = true } },
                    )
                }
            }
            composable(BROWSER_PAGES_ROUTE) {
                Box(Modifier.testTag("screen_browser_pages")) {
                    BrowserPagesScreen(container = container, onOpened = { navController.popBackStack() })
                }
            }
            composable(TopLevelDestination.Downloads.route) {
                val vm = containerViewModel(container) { DownloadsViewModel(it) }
                val state by vm.state.collectAsStateWithLifecycle()
                Box(Modifier.testTag("screen_downloads")) {
                    DownloadsScreen(
                        state = state,
                        onDeleteRecord = vm::deleteRecord,
                        onClearUnsuccessful = vm::clearUnsuccessful,
                        onCancel = vm::cancel,
                        onRetry = vm::retry,
                    )
                }
            }
            composable(TopLevelDestination.Library.route) {
                val vm = containerViewModel(container) { LibraryViewModel(it) }
                val state by vm.state.collectAsStateWithLifecycle()
                val missing by vm.missing.collectAsStateWithLifecycle()
                val libraryMessage by vm.message.collectAsStateWithLifecycle()
                Box(Modifier.testTag("screen_library")) {
                    LibraryScreen(
                        state = state,
                        onSearch = vm::setSearch,
                        onSort = vm::setSort,
                        onFilter = vm::setFilter,
                        onRemoveEntry = vm::removeEntry,
                        missing = missing,
                        message = libraryMessage,
                        onDeleteFile = vm::deleteFile,
                    )
                }
            }
            composable(SETTINGS_ROUTE) {
                val vm = containerViewModel(container) { SettingsViewModel(it) }
                val engineState by vm.engineState.collectAsStateWithLifecycle()
                val message by vm.message.collectAsStateWithLifecycle()
                val tempBytes by vm.tempBytes.collectAsStateWithLifecycle()
                val appContext = LocalContext.current.applicationContext
                Box(Modifier.testTag("screen_settings")) {
                    SettingsScreen(
                        settings = settings,
                        engineState = engineState,
                        message = message,
                        onThemeChange = { mode -> onSettingsChange { it.copy(themeMode = mode) } },
                        onChooseEngine = vm::chooseEngine,
                        onClearHistory = vm::clearBrowsingHistory,
                        onClearCookies = vm::clearCookies,
                        onClearCache = { vm.clearCache(appContext) },
                        onOpenAbout = { navController.navigate(ABOUT_ROUTE) { launchSingleTop = true } },
                        onSettingsChange = onSettingsChange,
                        tempBytes = tempBytes,
                        onRefreshStorage = vm::refreshStorage,
                        onCleanTemporary = vm::cleanTemporaryFiles,
                        onClearDownloads = vm::clearDownloadHistory,
                        onFolderPicked = { uri ->
                            vm.chooseFolder(appContext, uri, settings.downloadTreeUri) { chosen ->
                                onSettingsChange { it.copy(downloadTreeUri = chosen) }
                            }
                        },
                        onResetFolder = {
                            vm.resetFolder(appContext, settings.downloadTreeUri) { onSettingsChange { it.copy(downloadTreeUri = null) } }
                        },
                        onBuildDiagnostics = vm::buildDiagnostics,
                        storageAccess = storageAccess,
                    )
                }
            }
            composable(ABOUT_ROUTE) {
                Box(Modifier.testTag("screen_about")) {
                    AboutScreen(onOpenLicenses = { navController.navigate(LICENSES_ROUTE) { launchSingleTop = true } })
                }
            }
            composable(LICENSES_ROUTE) {
                Box(Modifier.testTag("screen_licenses")) { LicensesScreen() }
            }
        }
    }
}

/**
 * The bottom bar of the four main screens (Home, Browser, Library, Downloads). The standard Material bar is 80 dp high and leaves
 * a lot of empty space above and below its icons; this one is [SLIM_BAR_HEIGHT] high, with the same colours, the system-bar insets
 * and the same selection behaviour, so the page above it gets the room.
 */
private val SLIM_BAR_HEIGHT = 52.dp

@Composable
private fun SlimNavigationBar(content: @Composable RowScope.() -> Unit) {
    Surface(color = NavigationBarDefaults.containerColor, tonalElevation = NavigationBarDefaults.Elevation) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(NavigationBarDefaults.windowInsets).height(SLIM_BAR_HEIGHT).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun RowScope.SlimNavigationItem(selected: Boolean, onClick: () -> Unit, icon: Int, label: Int, modifier: Modifier = Modifier) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.weight(1f).fillMaxHeight().selectable(selected = selected, onClick = onClick, role = Role.Tab),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}
