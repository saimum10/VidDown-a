package com.viddown.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.webkit.WebChromeClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viddown.app.AppContainer
import com.viddown.app.R
import com.viddown.app.browser.BrowserHub
import com.viddown.app.browser.BrowserWebHost
import com.viddown.app.browser.LinkLongPress
import com.viddown.app.browser.BrowserWebViews
import com.viddown.app.browser.PageError
import com.viddown.app.browser.PageErrorKind
import com.viddown.app.browser.TabChromeClient
import com.viddown.app.browser.TabWebViewClient
import com.viddown.app.browser.WebViewController
import com.viddown.app.presentation.LinkState
import com.viddown.app.presentation.QualityState
import com.viddown.app.ui.UiTags
import com.viddown.app.ui.viewmodels.BrowserViewModel
import com.viddown.app.ui.viewmodels.containerViewModel
import com.viddown.browser.PageLimitation
import com.viddown.browser.Tab
import com.viddown.browser.UrlDecision
import com.viddown.browser.UrlPolicy
import com.viddown.core.model.AppSettings
import com.viddown.core.ui.VidDownIcons
import com.viddown.core.ui.theme.spacing
import kotlinx.coroutines.delay

/** Receives what the WebView clients report. One instance per tab; the screen re-points its fields on every composition. */
private class ScreenHost : BrowserWebHost {
    var vm: BrowserViewModel? = null
    var controller: WebViewController? = null
    var setError: (PageError?) -> Unit = {}
    var setCrashed: (Boolean) -> Unit = {}
    var setCustomView: (View?, WebChromeClient.CustomViewCallback?) -> Unit = { _, _ -> }
    var onLongPress: (String) -> Unit = {}
    private var errorUrl: String? = null

    override fun onPageStarted(tabId: String, url: String) {
        errorUrl = null
        setError(null)
        setCrashed(false)
    }

    override fun onPageFinished(tabId: String, url: String, title: String?) {
        // A page that failed to load is not a page that was visited.
        if (errorUrl == null) vm?.recordVisit(url, title)
    }

    override fun onNavigationState(tabId: String, canGoBack: Boolean, canGoForward: Boolean) {
        controller?.canGoBack = canGoBack
        controller?.canGoForward = canGoForward
    }

    override fun onBlocked(tabId: String, block: UrlDecision.Block) {
        vm?.say(block.userMessage)
    }

    override fun onLoadError(tabId: String, error: PageError) {
        errorUrl = error.url ?: ""
        setError(error)
    }

    override fun onRenderGone(tabId: String) {
        controller?.discardState = true
        setCrashed(true)
    }

    override fun onDownloadLink(tabId: String, url: String, mimeType: String?) {
        vm?.downloadLink(url)
    }

    override fun onLongPressLink(tabId: String, url: String) = onLongPress(url)

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) = setCustomView(view, callback)

    override fun onHideCustomView() = setCustomView(null, null)
}

/**
 * The in-app browser: address bar, one live WebView for the active tab, tab switcher, bookmarks, and the media found on the page.
 * Everything that is not drawing lives in [BrowserViewModel] and the process-wide hub.
 */
@Composable
fun BrowserScreen(
    container: AppContainer,
    settings: AppSettings,
    entryKey: String,
    launchUrl: String?,
    onOpenHome: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPages: () -> Unit,
) {
    val vm = containerViewModel(container) { BrowserViewModel(it) }
    val hub = container.browser
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    val tabsState by vm.tabs.collectAsStateWithLifecycle()
    val detections by vm.detections.collectAsStateWithLifecycle()
    val bookmarked by vm.isBookmarked.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val tab = tabsState.active

    val controller = remember { WebViewController() }
    val snackbar = remember { SnackbarHostState() }
    var showTabs by rememberSaveable { mutableStateOf(false) }
    var showMedia by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var pageError by remember(tab?.id) { mutableStateOf<PageError?>(null) }
    var crashed by remember(tab?.id) { mutableStateOf(false) }
    var renderKey by remember(tab?.id) { mutableIntStateOf(0) }
    var customView by remember { mutableStateOf<View?>(null) }
    // The address under a long press (a link, a video thumbnail, a video): the link menu is shown for it.
    var pressedLink by remember(tab?.id) { mutableStateOf<String?>(null) }
    var customCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    val host = remember(tab?.id) { ScreenHost() }
    SideEffect {
        host.vm = vm
        host.controller = controller
        host.setError = { pageError = it }
        host.setCrashed = { crashed = it }
        host.onLongPress = { pressedLink = it }
        host.setCustomView = { view, callback ->
            if (view == null) customCallback?.onCustomViewHidden()
            customView = view
            customCallback = callback
        }
    }

    // An address that came with the navigation (from Home, a recent page, another app) is opened once per navigation entry.
    LaunchedEffect(entryKey, launchUrl) { vm.consumeLaunch(entryKey, launchUrl) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it, duration = SnackbarDuration.Long) } }
    // A request to load an address in a tab stays pending until the tab's WebView exists and has taken it.
    LaunchedEffect(Unit) {
        vm.pendingLoad.collect { request ->
            if (request != null && controller.tabId == request.tabId) {
                pageError = null
                controller.load(request.url)
                vm.loadHandled(request.id)
            }
        }
    }

    // Ask the page about its players shortly after it settles; the answer enriches and explains what the requests showed.
    LaunchedEffect(tab?.id, tab?.url, tab?.isLoading, settings.javascriptEnabled) {
        val t = tab
        if (t != null && t.url != null && !t.isLoading && settings.javascriptEnabled) {
            delay(1_200)
            controller.scanPlayers { hub.detections.onPlayerReport(t.id, it) }
            delay(3_500)
            controller.scanPlayers { hub.detections.onPlayerReport(t.id, it) }
        }
    }
    LaunchedEffect(showMedia) {
        if (showMedia) tab?.let { t -> controller.scanPlayers { hub.detections.onPlayerReport(t.id, it) } }
    }

    // Pause and resume the web engine with the screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> controller.onPause()
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A download that started by itself (Ask before downloading is off): say so, no sheet.
    val autoQueued = (quality as? QualityState.Queued)?.takeIf { it.automatic }
    LaunchedEffect(autoQueued) {
        if (autoQueued != null) {
            vm.dismissQuality()
            val result = snackbar.showSnackbar(context.getString(R.string.quality_queued_auto), actionLabel = context.getString(R.string.link_open_downloads))
            if (result == SnackbarResult.ActionPerformed) onOpenDownloads()
        }
    }

    // The first queued download may need the notification permission, exactly like the link dialog.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val anyQueued = quality is QualityState.Queued
    LaunchedEffect(anyQueued) {
        if (anyQueued && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = customView != null || showTabs || controller.canGoBack) {
        when {
            customView != null -> host.onHideCustomView()
            showTabs -> showTabs = false
            else -> controller.goBack()
        }
    }

    // The download button floats over a page only when the page has a video: media was found on it, or it plays through a streaming
    // player (YouTube and the like) whose page address the selected engine can read. On every other page there is no button.
    val pageIsWeb = tab?.url?.let(UrlPolicy::isHttp) == true
    val streamingVideoPage = detections.limitation == PageLimitation.BLOB_OR_MSE_PLAYER && detections.pageDownload && pageIsWeb
    val showDownloadButton = pageIsWeb && pageError == null && !crashed && (detections.count > 0 || streamingVideoPage)
    val downloadReady = detections.downloadableCount > 0 || streamingVideoPage
    val onDownloadClick: () -> Unit = {
        val downloadable = detections.items.filter { it.status.canDownload }
        when {
            detections.count == 1 && downloadable.size == 1 -> vm.download(downloadable.single().media)
            // A streaming (blob:/MSE) player such as YouTube has no file address: read the page's own address instead.
            downloadable.isEmpty() && streamingVideoPage -> vm.downloadPage()
            else -> showMedia = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AddressRow(
                tab = tab,
                settings = settings,
                canGoBack = controller.canGoBack,
                canGoForward = controller.canGoForward,
                tabCount = tabsState.tabs.size,
                bookmarked = bookmarked,
                onBookmark = vm::toggleBookmark,
                onGo = { text -> vm.go(text, settings.searchEngine) },
                onHome = onOpenHome,
                onBack = controller::goBack,
                onForward = controller::goForward,
                onReloadOrStop = { if (tab?.isLoading == true) controller.stop() else controller.reload() },
                onTabs = { showTabs = !showTabs },
                menuOpen = menuOpen,
                onMenuOpen = { menuOpen = it },
                onNewTab = {
                    vm.newTab()
                    showTabs = false
                },
                onPages = onOpenPages,
                onShare = { tab?.url?.let { shareText(context, it) } },
                onCopy = {
                    tab?.url?.let {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", it))
                        vm.say(context.getString(R.string.browser_link_copied))
                    }
                },
                onSettings = onOpenSettings,
            )
            if (tab?.isLoading == true) {
                LinearProgressIndicator(
                    progress = { (tab.progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp).testTag(UiTags.BROWSER_PROGRESS),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab != null) {
                    if (tab.url == null) {
                        BlankTab(onHome = { vm.openHomepage(settings.homepage) })
                    } else {
                        key(tab.id, renderKey) {
                            TabWebView(tab = tab, hub = hub, settings = settings, controller = controller, host = host, modifier = Modifier.fillMaxSize())
                        }
                    }
                    pageError?.let { error ->
                        PageErrorPanel(
                            error = error,
                            onRetry = {
                                pageError = null
                                controller.reload()
                            },
                            onHome = {
                                pageError = null
                                vm.openHomepage(settings.homepage)
                            },
                        )
                    }
                    if (crashed) {
                        CrashedPanel(onReload = {
                            controller.discardState = true
                            hub.webStates.remove(tab.id)
                            crashed = false
                            renderKey++
                        })
                    }
                }
                if (showDownloadButton) {
                    FloatingActionButton(
                        onClick = onDownloadClick,
                        containerColor = if (downloadReady) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (downloadReady) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(spacing.lg).testTag(UiTags.BROWSER_MEDIA),
                    ) {
                        BadgedBox(
                            badge = {
                                if (detections.count > 0) Badge(modifier = Modifier.testTag(UiTags.BROWSER_MEDIA_BADGE)) { Text(detections.count.toString()) }
                            },
                        ) {
                            Icon(painterResource(VidDownIcons.Download), contentDescription = stringResource(R.string.browser_media))
                        }
                    }
                }
                if (showTabs) {
                    TabSwitcher(
                        state = tabsState,
                        onSelect = {
                            vm.switchTab(it)
                            showTabs = false
                        },
                        onClose = vm::closeTab,
                        onNewTab = {
                            vm.newTab()
                            showTabs = false
                        },
                        onCloseAll = {
                            vm.closeAllTabs()
                            showTabs = false
                        },
                    )
                }
                customView?.let { view ->
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().testTag("browser_fullscreen"))
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    if (showMedia && quality is QualityState.Hidden) {
        MediaListSheet(
            ui = detections,
            onDownload = {
                showMedia = false
                vm.download(it.media)
            },
            onRescan = { tab?.let { t -> controller.scanPlayers { hub.detections.onPlayerReport(t.id, it) } } },
            onDismiss = { showMedia = false },
            onDownloadPage = if (detections.pageDownload && tab?.url?.let(UrlPolicy::isHttp) == true) {
                {
                    showMedia = false
                    vm.downloadPage()
                }
            } else null,
        )
    }
    if (quality !is QualityState.Hidden && autoQueued == null) {
        QualitySheet(
            state = quality,
            onSelect = vm::selectFormat,
            onConfirm = vm::confirmDownload,
            onAdvanced = vm::setAdvanced,
            onDismiss = vm::dismissQuality,
            onOpenDownloads = {
                vm.dismissQuality()
                onOpenDownloads()
            },
            onOpenSettings = {
                vm.dismissQuality()
                onOpenSettings()
            },
        )
    }
    // A playlist address: its videos are listed with a tick box each, then the quality is chosen (the same sheet as on Home).
    if (playlist is LinkState.Playlist || playlist is LinkState.QueueingPlaylist || playlist is LinkState.PlaylistQueued) {
        val inPlaylist = playlist as? LinkState.Playlist
        PlaylistSheet(
            link = playlist,
            onToggleEntry = vm::togglePlaylistEntry,
            onToggleAll = vm::togglePlaylistAll,
            onChooseQuality = vm::choosePlaylistQuality,
            onSelectQuality = vm::selectPlaylistQuality,
            onCloseQuality = vm::closePlaylistQuality,
            onConfirm = vm::confirmPlaylist,
            onDismiss = vm::dismissPlaylist,
            onOpenDownloads = {
                vm.dismissPlaylist()
                onOpenDownloads()
            },
            onSingleVideo = if (inPlaylist != null && !inPlaylist.choosingQuality && vm.singleVideoOf(inPlaylist) != null) vm::downloadOnlyThisVideo else null,
        )
    }
    pressedLink?.let { url ->
        LinkActionsSheet(
            url = url,
            onOpen = {
                pressedLink = null
                vm.openLinkHere(url)
            },
            onOpenInNewTab = {
                pressedLink = null
                vm.openLinkInNewTab(url)
            },
            onCopy = {
                pressedLink = null
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", url))
                vm.say(context.getString(R.string.browser_link_copied))
            },
            onShare = {
                pressedLink = null
                shareText(context, url)
            },
            // The copied address itself is read: the quality panel opens exactly like after the download button.
            onDownload = {
                pressedLink = null
                vm.downloadPressedLink(url)
            },
            onDismiss = { pressedLink = null },
        )
    }
}

// ----------------------------------------------------------------------------------------------------- chrome

/** Height of the address field and of the icon buttons beside it: slim on purpose, so the page gets the room. */
private val ADDRESS_HEIGHT = 38.dp
private val BAR_BUTTON = 40.dp

@Composable
private fun AddressRow(
    tab: Tab?,
    settings: AppSettings,
    canGoBack: Boolean,
    canGoForward: Boolean,
    tabCount: Int,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    onGo: (String) -> String?,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReloadOrStop: () -> Unit,
    onTabs: () -> Unit,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
    onNewTab: () -> Unit,
    onPages: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onSettings: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val colors = MaterialTheme.colorScheme
    var field by remember(tab?.id, tab?.url) { mutableStateOf(TextFieldValue(tab?.url.orEmpty())) }
    var error by remember(tab?.id) { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val go = {
        error = onGo(field.text)
        focusManager.clearFocus()
    }
    // Tapping the bar selects the whole address: typing or pasting replaces it, and a tap again places the cursor.
    LaunchedEffect(focused) {
        if (focused) {
            delay(40)
            field = field.copy(selection = TextRange(0, field.text.length))
        }
    }
    val url = tab?.url
    val loading = tab?.isLoading == true
    val errorText = error
    val shape = RoundedCornerShape(ADDRESS_HEIGHT / 2)
    val outline = when {
        errorText != null -> colors.error
        focused -> colors.primary
        else -> Color.Transparent
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = spacing.xs, vertical = spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onHome, modifier = Modifier.size(BAR_BUTTON).testTag(UiTags.BROWSER_HOME)) {
            Icon(painterResource(VidDownIcons.Home), contentDescription = stringResource(R.string.browser_home))
        }
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().height(ADDRESS_HEIGHT).background(colors.surfaceVariant, shape).border(1.dp, outline, shape),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (url != null) {
                    val secure = UrlPolicy.isHttps(url)
                    Icon(
                        painterResource(if (secure) VidDownIcons.Lock else VidDownIcons.LockOpen),
                        contentDescription = stringResource(if (secure) R.string.browser_secure else R.string.browser_not_secure),
                        tint = if (secure) colors.onSurfaceVariant else colors.error,
                        modifier = Modifier.padding(start = spacing.md).size(16.dp),
                    )
                }
                Box(
                    Modifier.weight(1f).padding(start = if (url != null) spacing.sm else spacing.md, end = spacing.sm),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (field.text.isEmpty()) {
                        Text(stringResource(R.string.home_address_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                    BasicTextField(
                        value = field,
                        onValueChange = {
                            field = it
                            error = null
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { go() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { focused = it.isFocused }
                            .testTag(UiTags.BROWSER_ADDRESS),
                    )
                }
                if (focused && field.text.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            field = TextFieldValue("")
                            error = null
                            focusRequester.requestFocus()
                        },
                        modifier = Modifier.size(ADDRESS_HEIGHT).testTag(UiTags.BROWSER_ADDRESS_CLEAR),
                    ) {
                        Icon(painterResource(VidDownIcons.Close), contentDescription = stringResource(R.string.action_clear), modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (errorText != null) {
                Text(
                    errorText,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.error,
                    modifier = Modifier.padding(start = spacing.md, top = spacing.xxs),
                )
            }
        }
        // The tabs button sits right of the address bar; its number is how many tabs are open.
        IconButton(onClick = onTabs, modifier = Modifier.size(BAR_BUTTON).testTag(UiTags.BROWSER_TABS)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(VidDownIcons.Tabs), contentDescription = stringResource(R.string.browser_tabs))
                Text(tabCount.toString(), style = MaterialTheme.typography.labelSmall)
            }
        }
        Box {
            IconButton(onClick = { onMenuOpen(true) }, modifier = Modifier.size(BAR_BUTTON).testTag(UiTags.BROWSER_MENU)) {
                Icon(painterResource(VidDownIcons.More), contentDescription = stringResource(R.string.browser_menu))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpen(false) }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_back)) },
                    leadingIcon = { Icon(painterResource(VidDownIcons.Back), contentDescription = null) },
                    enabled = canGoBack,
                    onClick = { onMenuOpen(false); onBack() },
                    modifier = Modifier.testTag(UiTags.BROWSER_BACK),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_forward)) },
                    leadingIcon = { Icon(painterResource(VidDownIcons.Forward), contentDescription = null) },
                    enabled = canGoForward,
                    onClick = { onMenuOpen(false); onForward() },
                    modifier = Modifier.testTag(UiTags.BROWSER_FORWARD),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(if (loading) R.string.browser_stop else R.string.browser_reload)) },
                    leadingIcon = { Icon(painterResource(if (loading) VidDownIcons.Close else VidDownIcons.Refresh), contentDescription = null) },
                    enabled = url != null,
                    onClick = { onMenuOpen(false); onReloadOrStop() },
                    modifier = Modifier.testTag(UiTags.BROWSER_RELOAD),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(if (bookmarked) R.string.browser_bookmark_remove else R.string.browser_bookmark_add)) },
                    leadingIcon = {
                        Icon(painterResource(VidDownIcons.Bookmark), contentDescription = null, tint = if (bookmarked) colors.primary else LocalContentColor.current)
                    },
                    enabled = url != null,
                    onClick = { onMenuOpen(false); onBookmark() },
                    modifier = Modifier.testTag(UiTags.BROWSER_BOOKMARK),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_menu_new_tab)) },
                    onClick = { onMenuOpen(false); onNewTab() },
                    modifier = Modifier.testTag(UiTags.BROWSER_MENU_NEW_TAB),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_menu_pages)) },
                    onClick = { onMenuOpen(false); onPages() },
                    modifier = Modifier.testTag(UiTags.BROWSER_MENU_PAGES),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_menu_share)) },
                    enabled = url != null,
                    onClick = { onMenuOpen(false); onShare() },
                    modifier = Modifier.testTag(UiTags.BROWSER_MENU_SHARE),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_menu_copy)) },
                    enabled = url != null,
                    onClick = { onMenuOpen(false); onCopy() },
                    modifier = Modifier.testTag(UiTags.BROWSER_MENU_COPY),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_menu_settings)) },
                    onClick = { onMenuOpen(false); onSettings() },
                    modifier = Modifier.testTag(UiTags.BROWSER_MENU_SETTINGS),
                )
            }
        }
    }
}

// --------------------------------------------------------------------------------------------------- web view

@Composable
private fun TabWebView(
    tab: Tab,
    hub: BrowserHub,
    settings: AppSettings,
    controller: WebViewController,
    host: BrowserWebHost,
    modifier: Modifier,
) {
    AndroidView(
        modifier = modifier.testTag(UiTags.BROWSER_WEBVIEW),
        factory = { ctx ->
            BrowserWebViews.create(ctx, settings.javascriptEnabled).also { view ->
                view.webViewClient = TabWebViewClient(tab.id, hub, host)
                view.webChromeClient = TabChromeClient(tab.id, hub, host)
                view.setDownloadListener { url, _, _, mimeType, _ -> host.onDownloadLink(tab.id, url, mimeType) }
                LinkLongPress.install(view, { view.settings.javaScriptEnabled }) { url -> host.onLongPressLink(tab.id, url) }
                controller.attach(view, tab.id)
                // An address that was asked for while this view did not exist yet wins over the saved page.
                val pending = hub.pendingLoad.value?.takeIf { it.tabId == tab.id }
                if (pending != null) {
                    hub.loadHandled(pending.id)
                    controller.load(pending.url)
                } else {
                    val saved = hub.webStates.get(tab.id)
                    val restored = saved != null && view.restoreState(saved) != null
                    if (!restored) tab.url?.let { controller.load(it) }
                }
            }
        },
        update = { view -> view.settings.setJavaScriptEnabled(settings.javascriptEnabled) },
        onRelease = { view ->
            val keepState = !controller.discardState && hub.tabs.state.value.tabs.any { it.id == tab.id }
            controller.detach(view)
            BrowserWebViews.dispose(view, if (keepState) ({ bundle -> hub.webStates.put(tab.id, bundle) }) else null)
        },
    )
}

// ---------------------------------------------------------------------------------------------------- panels

@Composable
private fun BlankTab(onHome: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(
        Modifier.fillMaxSize().padding(spacing.xl).testTag(UiTags.BROWSER_BLANK),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(VidDownIcons.Browser), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.browser_blank_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = spacing.md))
        Text(
            stringResource(R.string.browser_blank_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.sm),
        )
        OutlinedButton(onClick = onHome, modifier = Modifier.padding(top = spacing.lg).defaultMinSize(minHeight = spacing.touchTarget)) {
            Text(stringResource(R.string.browser_open_homepage))
        }
    }
}

@Composable
private fun PageErrorPanel(error: PageError, onRetry: () -> Unit, onHome: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(
        Modifier.fillMaxSize().padding(spacing.xl).testTag(UiTags.BROWSER_ERROR),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(VidDownIcons.Warning), contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(
            stringResource(if (error.kind == PageErrorKind.SSL) R.string.browser_error_ssl_title else R.string.browser_error_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = spacing.md),
        )
        Text(error.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = spacing.sm))
        UrlPolicy.displayHost(error.url)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = spacing.xs))
        }
        Row(Modifier.padding(top = spacing.lg), horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Button(onClick = onRetry) { Text(stringResource(R.string.browser_error_retry)) }
            OutlinedButton(onClick = onHome) { Text(stringResource(R.string.browser_error_home)) }
        }
    }
}

@Composable
private fun CrashedPanel(onReload: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(
        Modifier.fillMaxSize().padding(spacing.xl).testTag(UiTags.BROWSER_CRASHED),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.browser_crashed_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.browser_crashed_message), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = spacing.sm))
        Button(onClick = onReload, modifier = Modifier.padding(top = spacing.lg)) { Text(stringResource(R.string.browser_reload)) }
    }
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}
