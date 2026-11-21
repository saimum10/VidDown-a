package com.viddown.app.ui.viewmodels

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.content.Context
import android.net.Uri
import android.os.Build
import com.viddown.app.AppContainer
import com.viddown.app.BuildConfig
import com.viddown.app.download.TreePermissions
import com.viddown.app.presentation.AdvancedOptions
import com.viddown.app.presentation.LinkSession
import com.viddown.app.presentation.LinkState
import com.viddown.app.presentation.DownloadsPresenter
import com.viddown.app.presentation.EngineChoice
import com.viddown.app.presentation.EnginePresenter
import com.viddown.app.presentation.EngineSettingsUiState
import com.viddown.app.presentation.HomePresenter
import com.viddown.app.presentation.HomeUiState
import com.viddown.app.presentation.LibraryPresenter
import com.viddown.app.presentation.LibraryQueryState
import com.viddown.app.presentation.LibraryUiState
import com.viddown.app.presentation.ListState
import com.viddown.core.model.DiagnosticsReport
import com.viddown.core.model.DownloadItemUi
import com.viddown.core.model.Formatters
import com.viddown.core.model.LibraryFilter
import com.viddown.core.model.LibrarySort
import com.viddown.download.DeleteFileResult
import com.viddown.engine.api.EngineAvailability
import com.viddown.engine.api.EngineMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Creates a ViewModel that needs the [AppContainer]. The ViewModels only forward to the tested presenters. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(container: AppContainer, crossinline create: (AppContainer) -> VM): VM =
    viewModel(factory = viewModelFactory { initializer { create(container) } })

private val whileVisible = SharingStarted.WhileSubscribed(5_000)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    /** null until the first data arrives. */
    val state: StateFlow<HomeUiState?> =
        HomePresenter(container.downloads.downloads, container.history.recent(2), container.settings.settings)
            .state.stateIn(viewModelScope, whileVisible, null)

    private val _link = MutableStateFlow<LinkState>(LinkState.Idle)

    /** The "Download from link" flow: Idle -> Checking -> Ready/Failed/Queued. */
    val link: StateFlow<LinkState> = _link
    private var linkJob: Job? = null

    /** Reads the clipboard link (and any link typed into the field) in the background while the dialog is open. */
    private val session = LinkSession(container.linkDownloader, viewModelScope)

    /** The dialog opened with an empty field: start reading the link on the clipboard so its qualities are ready sooner. */
    fun prefetchClipboardLink(text: String) = session.prefetchClipboard(text)

    /** The link field changed: another link drops the background read of the previous one. */
    fun linkTextChanged(text: String) = session.fieldChanged(text)

    /** "Try download": uses the background result when the field is empty or holds the same link, otherwise reads the typed link. */
    fun checkLink(text: String) {
        if (_link.value is LinkState.Checking) return
        _link.value = LinkState.Checking
        linkJob = viewModelScope.launch { _link.value = session.tryDownload(text) }
    }

    /** The user picked another quality in the list before downloading. */
    fun selectLinkFormat(id: String) {
        val ready = _link.value as? LinkState.Ready ?: return
        _link.value = container.linkDownloader.select(ready, id)
    }

    /** The user changed subtitle, codec or container in the advanced options: the sizes follow, the same quality stays selected. */
    fun setLinkAdvanced(advanced: AdvancedOptions) {
        val ready = _link.value as? LinkState.Ready ?: return
        _link.value = container.linkDownloader.setAdvanced(ready, advanced)
    }

    /** The user ticked or unticked one video of the listed playlist. */
    fun toggleLinkPlaylistEntry(url: String) {
        val playlist = _link.value as? LinkState.Playlist ?: return
        _link.value = container.linkDownloader.togglePlaylistEntry(playlist, url)
    }

    /** "Select all" / "Unselect all" of the listed playlist. */
    fun toggleLinkPlaylistAll() {
        val playlist = _link.value as? LinkState.Playlist ?: return
        _link.value = container.linkDownloader.togglePlaylistAll(playlist)
    }

    /** "Download selected": the quality sheet of the playlist opens, before anything is queued. */
    fun chooseLinkPlaylistQuality() {
        val playlist = _link.value as? LinkState.Playlist ?: return
        viewModelScope.launch {
            val opened = container.linkDownloader.openPlaylistQuality(playlist)
            // The state may have moved on (sheet closed, another link) while the settings were read: only the same playlist is updated.
            if (_link.value === playlist) _link.value = opened
        }
    }

    /** The user picked another quality in the playlist's sheet. */
    fun selectLinkPlaylistQuality(key: String) {
        val playlist = _link.value as? LinkState.Playlist ?: return
        _link.value = container.linkDownloader.selectPlaylistQuality(playlist, key)
    }

    /** Back from the playlist's quality sheet to its list of videos. */
    fun closeLinkPlaylistQuality() {
        val playlist = _link.value as? LinkState.Playlist ?: return
        _link.value = container.linkDownloader.closePlaylistQuality(playlist)
    }

    /** Reads and queues the ticked videos of the playlist; the state shows how far it is. Closing the sheet stops reading the rest. */
    fun confirmLinkPlaylist() {
        val playlist = _link.value as? LinkState.Playlist ?: return
        if (playlist.selectedCount == 0) return
        // Like for one video: the next panel (and the next playlist) opens with this quality selected.
        playlist.qualityKey?.let { key -> container.appScope.launch { container.settings.update { it.copy(lastQuality = key) } } }
        _link.value = LinkState.QueueingPlaylist(0, playlist.selectedCount, 0, 0)
        linkJob = viewModelScope.launch {
            _link.value = container.linkDownloader.confirmPlaylist(playlist) { _link.value = it }
        }
    }

    fun confirmLink() {
        val ready = _link.value as? LinkState.Ready ?: return
        // Like in the browser: the panel opens with this quality (and codec and container) selected next time.
        val quality = ready.quality
        val key = quality.panel.keyOf(quality.selectedId)
        container.appScope.launch {
            container.settings.update {
                it.copy(lastQuality = key ?: it.lastQuality, preferredCodec = quality.advanced.codec, preferredContainer = quality.advanced.container)
            }
        }
        _link.value = LinkState.Checking
        linkJob = viewModelScope.launch { _link.value = container.linkDownloader.confirm(ready) }
    }

    /** Closing the dialog also abandons a check that is still running, and the background read. */
    fun dismissLink() {
        linkJob?.cancel()
        session.close()
        _link.value = LinkState.Idle
    }
}

class DownloadsViewModel(private val container: AppContainer) : ViewModel() {
    private val manager get() = container.downloadManager

    val state: StateFlow<ListState<DownloadItemUi>> =
        DownloadsPresenter(container.downloads.downloads, manager.live, manager.work)
            .state.stateIn(viewModelScope, whileVisible, ListState.Loading)

    fun cancel(id: String) {
        viewModelScope.launch { manager.cancel(id) }
    }

    fun retry(id: String) {
        viewModelScope.launch { manager.retry(id) }
    }

    /** Removes the record (and any partial data). A running download is refused by the manager. */
    fun deleteRecord(id: String) {
        viewModelScope.launch { manager.deleteRecord(id) }
    }

    /** Removes failed and cancelled records. Completed ones are the library and stay. */
    fun clearUnsuccessful() {
        viewModelScope.launch { manager.clearUnsuccessful() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    private val query = MutableStateFlow(LibraryQueryState())

    val state: StateFlow<LibraryUiState> = LibraryPresenter(container.downloads.downloads, query)
        .state.stateIn(viewModelScope, whileVisible, LibraryUiState(ListState.Loading, LibraryQueryState(), false))

    fun setSearch(text: String) { query.value = query.value.copy(search = text) }
    fun setSort(sort: LibrarySort) { query.value = query.value.copy(sort = sort) }
    fun setFilter(filter: LibraryFilter) { query.value = query.value.copy(filter = filter) }

    /** Ids of completed downloads whose file is gone (deleted or moved by another app). Checked whenever the list changes. */
    val missing: StateFlow<Set<String>> = container.downloads.downloads
        .map { all -> all.filter { it.outputPath != null }.map { it.id } }
        .distinctUntilChanged()
        .mapLatest { ids -> ids.filterNot { container.downloadManager.fileExists(it) }.toSet() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, whileVisible, emptySet())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Removes the library entry (the record). The file on the device is not touched. */
    fun removeEntry(id: String) {
        _message.value = null
        viewModelScope.launch { container.downloadManager.deleteRecord(id) }
    }

    /** Deletes the file from the device, then the entry. When the file cannot be deleted the entry stays. */
    fun deleteFile(id: String) {
        viewModelScope.launch {
            _message.value = when (val r = container.downloadManager.deleteFile(id)) {
                is DeleteFileResult.Failed -> "The file could not be deleted: ${r.reason}"
                else -> null
            }
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val engines = EnginePresenter(container.engineSelector)

    val engineState: StateFlow<EngineSettingsUiState> =
        engines.state.stateIn(viewModelScope, whileVisible, EngineSettingsUiState(emptyList(), null))

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun chooseEngine(mode: EngineMode) {
        when (val choice = engines.choose(mode)) {
            is EngineChoice.Persist -> viewModelScope.launch {
                val before = container.settings.settings.first()
                val previous = if (before.unrecognizedEngineKey != null) null else before.engineMode
                // Choosing is the only way out of an unrecognised saved value, so it is cleared here (and the new key is then written).
                container.settings.update { it.copy(engineMode = choice.mode, unrecognizedEngineKey = null) }
                // Running and queued downloads keep their engine; say so when it matters. The engine that was selected before is closed
                // by the download manager as soon as no download uses it.
                _message.value = EnginePresenter.switchNotice(choice.mode, previous, container.downloadManager.engineCommitments.value)
            }
            is EngineChoice.Refused -> _message.value = choice.reason
        }
    }

    fun clearBrowsingHistory() {
        viewModelScope.launch {
            val n = container.history.clear()
            _message.value = if (n == 0) "Browsing history was already empty." else "Browsing history cleared ($n entries)."
        }
    }

    fun clearCookies() {
        viewModelScope.launch {
            val ok = com.viddown.app.browser.BrowserData.clearCookiesAndSiteData()
            _message.value = if (ok) "Cookies and site data cleared." else "There were no cookies to clear."
        }
    }

    fun clearCache(context: Context) {
        try {
            com.viddown.app.browser.BrowserData.clearCache(context.applicationContext)
            _message.value = "Page cache cleared."
        } catch (e: Exception) {
            _message.value = "The page cache could not be cleared: the web engine is not available."
        }
    }

    // ---- downloads ----------------------------------------------------------------------------------------------

    /** Records only: the files on the device are never deleted here. */
    fun clearDownloadHistory() {
        viewModelScope.launch {
            val n = container.downloadManager.clearHistory()
            _message.value = if (n == 0) "There was no finished download to clear." else "Download history cleared ($n entries)."
        }
    }

    private val _tempBytes = MutableStateFlow<Long?>(null)

    /** Space used by unfinished downloads, or null until measured. */
    val tempBytes: StateFlow<Long?> = _tempBytes

    fun refreshStorage() {
        viewModelScope.launch { _tempBytes.value = withContext(Dispatchers.IO) { container.downloadManager.temporaryBytes() } }
    }

    fun cleanTemporaryFiles() {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) { container.downloadManager.cleanupTemporaryFiles() }
            _message.value = "Freed ${Formatters.bytes(freed) ?: "0 B"}."
            refreshStorage()
        }
    }

    // ---- save location --------------------------------------------------------------------------------------------

    /** Keeps access to the folder the user picked, gives back the old one, and only then reports success to [apply]. */
    fun chooseFolder(context: Context, picked: Uri, current: String?, apply: (String?) -> Unit) {
        val error = TreePermissions.take(context, picked)
        if (error != null) {
            _message.value = "That folder cannot be used: $error"
            return
        }
        if (current != picked.toString()) TreePermissions.release(context, current)
        _message.value = null
        apply(picked.toString())
    }

    fun resetFolder(context: Context, current: String?, apply: (String?) -> Unit) {
        TreePermissions.release(context, current)
        _message.value = null
        apply(null)
    }

    // ---- diagnostics ----------------------------------------------------------------------------------------------

    /** The text of the diagnostics report. Nothing is sent anywhere: the screen hands it to the system share sheet. */
    suspend fun buildDiagnostics(): String {
        val settings = container.settings.settings.first()
        val tasks = container.downloads.downloads.first()
        // "loaded" = the engine object exists right now. At most the selected engine, plus engines that queued or running downloads still use.
        val loaded = container.engineSelector.createdEngineIds()
        val engines = container.engineSelector.statuses.first().map { e ->
            val status = when (val a = e.availability) {
                EngineAvailability.Available -> "Available"
                is EngineAvailability.NotAvailable -> a.reason
            }
            e.label to (status + (if (e.id in loaded) " (loaded)" else " (not loaded)"))
        }
        return DiagnosticsReport.build(
            appVersion = BuildConfig.VERSION_NAME,
            androidApi = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            settings = settings,
            engines = engines,
            tasks = tasks,
        )
    }
}
