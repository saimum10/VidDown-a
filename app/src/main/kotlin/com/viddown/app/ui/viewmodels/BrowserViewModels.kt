package com.viddown.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viddown.app.AppContainer
import com.viddown.app.browser.LoadRequest
import com.viddown.app.presentation.AdvancedOptions
import com.viddown.app.presentation.DetectionPresenter
import com.viddown.app.presentation.DetectionsUi
import com.viddown.app.presentation.DownloadTarget
import com.viddown.app.presentation.EngineInfo
import com.viddown.app.presentation.EngineRoute
import com.viddown.app.presentation.HomeAction
import com.viddown.app.presentation.HomePresenter
import com.viddown.app.presentation.LinkState
import com.viddown.app.presentation.QualityState
import com.viddown.browser.DetectedMedia
import com.viddown.browser.OpenTabResult
import com.viddown.browser.TabDetectionState
import com.viddown.browser.TabsState
import com.viddown.browser.UrlPolicy
import com.viddown.core.model.SearchEngine
import com.viddown.data.repo.Bookmark
import com.viddown.data.repo.PageVisit
import com.viddown.engine.api.EngineAvailability
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineResult
import com.viddown.engine.api.MediaType
import com.viddown.engine.api.PlaylistLinks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val whileVisible = SharingStarted.WhileSubscribed(5_000)

/**
 * The browser screen's logic. It never sees a WebView: tabs, detections and downloads live in the process-wide hub and the
 * repositories, the WebView only in the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModel(private val container: AppContainer) : ViewModel() {
    private val hub = container.browser

    val tabs: StateFlow<TabsState> = hub.tabs.state
    val pendingLoad: StateFlow<LoadRequest?> = hub.pendingLoad

    fun loadHandled(requestId: Long) = hub.loadHandled(requestId)

    // Follows the selector (the single owner of the choice). A fixed mode describes only its own engine, so an engine that is no longer
    // selected is never created just to be described. In Auto the route holds both engines and picks one per link (YouTube -> NewPipe,
    // others -> yt-dlp); describing an engine only creates the object, nothing is started until a download needs it.
    private val engineRoute: Flow<EngineRoute> = container.engineSelector.selection.mapLatest { mode ->
        val fixed = mode?.fixed
        when {
            mode == null -> EngineRoute.fixed(EngineInfo(null, "No engine", EngineAvailability.NotAvailable("No download engine is selected."), null))
            fixed == null -> EngineRoute.auto(describe(EngineId.NEWPIPE), describe(EngineId.YTDLP))
            else -> EngineRoute.fixed(describe(fixed))
        }
    }

    private suspend fun describe(id: EngineId): EngineInfo = when (val r = container.engineSelector.engineFor(id)) {
        is EngineResult.Success -> EngineInfo(r.value.id, r.value.displayName, EngineAvailability.Available, r.value.capabilities)
        is EngineResult.Failure -> EngineInfo(id, id.label, EngineAvailability.NotAvailable(r.error.technicalDetail.ifBlank { r.error.userMessage }), null)
    }

    /** The detections of the active tab, judged against the engine selected right now. */
    val detections: StateFlow<DetectionsUi> = tabs.map { it.activeId }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(TabDetectionState()) else hub.detections.state(id) }
        .combine(engineRoute) { state, route -> state to route }
        .combine(tabs.map { it.active?.url }.distinctUntilChanged()) { (state, route), pageUrl -> DetectionPresenter.build(state, route, pageUrl) }
        .stateIn(viewModelScope, whileVisible, DetectionsUi())

    val isBookmarked: StateFlow<Boolean> = tabs.map { it.active?.url }.distinctUntilChanged()
        .flatMapLatest { url -> if (url == null) flowOf(false) else container.bookmarks.isBookmarked(url) }
        .stateIn(viewModelScope, whileVisible, false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Short notices for the screen's snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ---------------------------------------------------------------------------------------------------- navigation

    fun consumeLaunch(entryKey: String, url: String?) {
        if (url != null && hub.markLaunchHandled(entryKey)) hub.openInActiveTab(url)
    }

    /** Opens what was typed in the address bar. Returns an explanation when it cannot be opened, null when it was accepted. */
    fun go(text: String, searchEngine: SearchEngine): String? = when (val action = HomePresenter.interpret(text, searchEngine)) {
        is HomeAction.OpenInBrowser -> {
            hub.openInActiveTab(action.url)
            null
        }
        is HomeAction.ShowError -> action.message
    }

    fun openHomepage(homepage: String) {
        hub.openInActiveTab(homepage)
    }

    fun newTab() {
        when (val r = hub.tabs.open(null)) {
            is OpenTabResult.Opened -> Unit
            is OpenTabResult.LimitReached -> _messages.tryEmit("You can have at most ${r.max} tabs open. Close one first.")
        }
    }

    fun switchTab(id: String) = hub.tabs.switchTo(id)

    fun closeTab(id: String) = hub.closeTab(id)

    fun closeAllTabs() = hub.closeAllTabs()

    fun say(message: String) {
        _messages.tryEmit(message)
    }

    // ------------------------------------------------------------------------------------------ history and bookmarks

    /** Records a finished page in the history (only http(s); the repository refuses everything else). */
    fun recordVisit(url: String, title: String?) {
        container.appScope.launch { container.history.record(url, title) }
    }

    fun toggleBookmark() {
        val tab = tabs.value.active ?: return
        val url = tab.url ?: return
        viewModelScope.launch {
            val saved = container.bookmarks.toggle(url, tab.title)
            _messages.tryEmit(if (saved) "Bookmark added" else "Bookmark removed")
        }
    }

    // --------------------------------------------------------------------------------------------------- downloading

    private val _quality = MutableStateFlow<QualityState>(QualityState.Hidden)

    /** The quality bottom sheet. */
    val quality: StateFlow<QualityState> = _quality
    private var qualityJob: Job? = null

    fun download(media: DetectedMedia) = inspect(DownloadTarget(media.url, media.pageUrl, media.title, media.type))

    /** A link the WebView treated as a download (a file the user tapped). Its type is not known until the engine looks at it. */
    fun downloadLink(url: String) {
        if (!UrlPolicy.isHttp(url)) {
            _messages.tryEmit("This download cannot be saved: it is not a web address (for example a blob: link created by the page).")
            return
        }
        inspect(DownloadTarget(url, tabs.value.active?.url, null, null))
    }

    /**
     * "Download" in the long-press menu: the address that was copied from the pressed link or video goes to the same quality panel
     * as the download button. The page being shown is only the Referer of the request, never the address that is read.
     */
    fun downloadPressedLink(url: String) {
        if (!UrlPolicy.isHttp(url)) {
            _messages.tryEmit("This link cannot be downloaded: it is not a web address.")
            return
        }
        inspect(DownloadTarget(url, tabs.value.active?.url, null, null))
    }

    /** "Open in new tab" in the long-press menu. */
    fun openLinkInNewTab(url: String) {
        when (val r = hub.tabs.open(url)) {
            is OpenTabResult.Opened -> Unit
            is OpenTabResult.LimitReached -> _messages.tryEmit("You can have at most ${r.max} tabs open. Close one first.")
        }
    }

    /** "Open" in the long-press menu: the link replaces the page of the active tab. */
    fun openLinkHere(url: String) {
        hub.openInActiveTab(url)
    }

    /**
     * Downloads what the page itself shows, by its address: the engine (yt-dlp, NewPipe) lists the qualities. This is how YouTube and
     * other players that stream through blob:/MSE are downloaded, since they never expose a file address.
     */
    fun downloadPage() {
        val tab = tabs.value.active ?: return
        val url = tab.url ?: return
        if (!UrlPolicy.isHttp(url)) {
            _messages.tryEmit("This page does not have a web address that can be downloaded.")
            return
        }
        inspect(DownloadTarget(url, url, tab.title, MediaType.PAGE))
    }

    private fun inspect(target: DownloadTarget) {
        qualityJob?.cancel()
        _quality.value = QualityState.Loading(target)
        qualityJob = viewModelScope.launch {
            // A playlist address is listed first (reading it as one video would only give its first video, or nothing at all).
            val listed = if (PlaylistLinks.looksLikePlaylist(target.url)) openPlaylist(target) else null
            _quality.value = listed ?: container.mediaDownloader.inspect(target).let { read ->
                // Any other site: the engine only finds out that it is a playlist when it reads the address.
                if (read is QualityState.Failed && read.isPlaylist) openPlaylist(target) ?: read else read
            }
        }
    }

    /**
     * Lists the playlist at [target] and shows its videos (the same sheet as "Download from link"). Returns what the quality sheet then
     * shows: nothing when the playlist is listed, the reason when it cannot be, or null when the engine does not list this address as a
     * playlist (it is then read as one video).
     */
    private suspend fun openPlaylist(target: DownloadTarget): QualityState? =
        when (val listed = container.linkDownloader.listPlaylist(target.url, target.pageUrl)) {
            is LinkState.Playlist -> {
                _playlist.value = listed
                QualityState.Hidden
            }
            is LinkState.Failed -> QualityState.Failed(target, listed.message, listed.detail, listed.action)
            else -> null
        }

    // ------------------------------------------------------------------------------------------------ playlists

    private val _playlist = MutableStateFlow<LinkState>(LinkState.Idle)

    /** A playlist the browser listed: Idle -> Playlist (pick videos, then the quality) -> QueueingPlaylist -> PlaylistQueued. */
    val playlist: StateFlow<LinkState> = _playlist
    private var playlistJob: Job? = null

    fun togglePlaylistEntry(url: String) {
        val playlist = _playlist.value as? LinkState.Playlist ?: return
        _playlist.value = container.linkDownloader.togglePlaylistEntry(playlist, url)
    }

    fun togglePlaylistAll() {
        val playlist = _playlist.value as? LinkState.Playlist ?: return
        _playlist.value = container.linkDownloader.togglePlaylistAll(playlist)
    }

    /** "Download selected": the quality sheet of the playlist opens, before anything is queued. */
    fun choosePlaylistQuality() {
        val playlist = _playlist.value as? LinkState.Playlist ?: return
        viewModelScope.launch {
            val opened = container.linkDownloader.openPlaylistQuality(playlist)
            if (_playlist.value === playlist) _playlist.value = opened
        }
    }

    fun selectPlaylistQuality(key: String) {
        val playlist = _playlist.value as? LinkState.Playlist ?: return
        _playlist.value = container.linkDownloader.selectPlaylistQuality(playlist, key)
    }

    fun closePlaylistQuality() {
        val playlist = _playlist.value as? LinkState.Playlist ?: return
        _playlist.value = container.linkDownloader.closePlaylistQuality(playlist)
    }

    /** Reads and queues the ticked videos in the chosen quality; the state shows how far it is. Closing the sheet stops reading the rest. */
    fun confirmPlaylist() {
        val playlist = _playlist.value as? LinkState.Playlist ?: return
        if (playlist.selectedCount == 0) return
        playlist.qualityKey?.let { key -> container.appScope.launch { container.settings.update { it.copy(lastQuality = key) } } }
        _playlist.value = LinkState.QueueingPlaylist(0, playlist.selectedCount, 0, 0)
        playlistJob = viewModelScope.launch {
            _playlist.value = container.linkDownloader.confirmPlaylist(playlist) { _playlist.value = it }
        }
    }

    /**
     * A watch page that belongs to a playlist can also be saved as the one video it shows: the address without its playlist goes to
     * the quality sheet of one video. Null when the address names no single video.
     */
    fun singleVideoOf(playlist: LinkState.Playlist): String? = PlaylistLinks.singleVideoUrl(playlist.request.url)

    fun downloadOnlyThisVideo() {
        val playlist = _playlist.value as? LinkState.Playlist ?: return
        val single = singleVideoOf(playlist) ?: return
        _playlist.value = LinkState.Idle
        inspect(DownloadTarget(single, playlist.request.pageUrl, null, MediaType.PAGE))
    }

    fun dismissPlaylist() {
        playlistJob?.cancel()
        _playlist.value = LinkState.Idle
    }

    fun selectFormat(id: String) {
        val ready = _quality.value as? QualityState.Ready ?: return
        _quality.value = ready.withSelected(id)
    }

    /** The user changed subtitle, codec or container: the panel is rebuilt, and the row that was selected stays selected. */
    fun setAdvanced(advanced: AdvancedOptions) {
        val ready = _quality.value as? QualityState.Ready ?: return
        _quality.value = ready.withAdvanced(advanced)
    }

    fun confirmDownload() {
        val ready = _quality.value as? QualityState.Ready ?: return
        // The panel opens with this quality selected next time (and with the same codec and container).
        val key = ready.panel.keyOf(ready.selectedId)
        container.appScope.launch {
            container.settings.update {
                it.copy(lastQuality = key ?: it.lastQuality, preferredCodec = ready.advanced.codec, preferredContainer = ready.advanced.container)
            }
        }
        qualityJob?.cancel()
        _quality.value = QualityState.Loading(ready.target)
        qualityJob = viewModelScope.launch { _quality.value = container.mediaDownloader.confirm(ready, ready.selectedId) }
    }

    /** Closing the sheet also abandons a check that is still running. */
    fun dismissQuality() {
        qualityJob?.cancel()
        _quality.value = QualityState.Hidden
    }

    override fun onCleared() {
        qualityJob?.cancel()
        playlistJob?.cancel()
    }
}

/** Bookmarks and history lists. */
class BrowserPagesViewModel(private val container: AppContainer) : ViewModel() {
    val bookmarks: StateFlow<List<Bookmark>> = container.bookmarks.bookmarks.stateIn(viewModelScope, whileVisible, emptyList())
    val history: StateFlow<List<PageVisit>> = container.history.recent(300).stateIn(viewModelScope, whileVisible, emptyList())

    fun removeBookmark(url: String) {
        viewModelScope.launch { container.bookmarks.remove(url) }
    }

    fun removeHistoryEntry(url: String) {
        viewModelScope.launch { container.history.remove(url) }
    }

    fun clearHistory() {
        viewModelScope.launch { container.history.clear() }
    }

    fun open(url: String) = container.browser.openInActiveTab(url)
}
