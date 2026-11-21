package com.viddown.app

import android.app.Application
import android.content.Context
import com.viddown.app.browser.BrowserHub
import com.viddown.app.download.AndroidOutputStore
import com.viddown.app.download.ConnectivityMonitor
import com.viddown.app.download.DownloadRuntime
import com.viddown.app.download.Notifications
import com.viddown.app.download.RepositoryDownloadStore
import com.viddown.app.engines.AppEngineRegistry
import com.viddown.app.presentation.LinkDownloader
import com.viddown.app.presentation.MediaDownloader
import com.viddown.data.db.VidDownDatabase
import com.viddown.data.repo.BookmarkRepository
import com.viddown.data.repo.DownloadRepository
import com.viddown.data.repo.HistoryRepository
import com.viddown.data.settings.SettingsRepository
import com.viddown.data.settings.createSettingsRepository
import com.viddown.download.DownloadManager
import com.viddown.download.ManagerSettings
import com.viddown.engine.selector.EngineSelector
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Manual dependency container (no DI framework: the object graph is small). Everything is created lazily. */
class AppContainer(private val context: Context) {
    val settings: SettingsRepository by lazy { createSettingsRepository(context) }
    val database: VidDownDatabase by lazy { VidDownDatabase.create(context) }
    val downloads: DownloadRepository by lazy { DownloadRepository(database.downloadDao()) }
    val history: HistoryRepository by lazy { HistoryRepository(database.pageVisitDao()) }
    val bookmarks: BookmarkRepository by lazy { BookmarkRepository(database.bookmarkDao()) }

    /**
     * The only object that may create an engine; it follows the persisted choice. A saved choice this version does not know means
     * NO engine (null), never the default: nothing is picked for the user.
     */
    val engineSelector: EngineSelector by lazy {
        EngineSelector(
            AppEngineRegistry(context),
            settings.settings.map { s -> if (s.unrecognizedEngineKey != null) null else s.engineMode }.distinctUntilChanged(),
        )
    }

    /** Lives as long as the process; downloads must not depend on any screen. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val connectivity: ConnectivityMonitor by lazy { ConnectivityMonitor(context) }

    /**
     * Partial downloads live in the app's own external folder (not the cache, which the system or the user may clear),
     * or in internal storage when external storage is not available.
     */
    private val partialDir: File by lazy { context.getExternalFilesDir("partial") ?: File(context.filesDir, "partial") }

    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            store = RepositoryDownloadStore(downloads),
            engines = engineSelector,
            output = AndroidOutputStore(context, treeUri = { settings.settings.first().downloadTreeUri }),
            tempRoot = partialDir,
            settings = settings.settings.map { ManagerSettings(it.maxSimultaneousDownloads, it.wifiOnly) }.distinctUntilChanged(),
            network = connectivity.state,
            scope = appScope,
        )
    }

    val linkDownloader: LinkDownloader by lazy {
        // Only the Cookie of the in-app browser is added to a pasted link (the User-Agent and Referer of a page are not).
        LinkDownloader(engineSelector, downloadManager, { url -> browser.requestHeaders(null, url).filterKeys { it.equals("Cookie", ignoreCase = true) } }) {
            settings.settings.first()
        }
    }

    /** Tabs, media detections and saved page state of the in-app browser. Holds no WebView. */
    val browser: BrowserHub by lazy { BrowserHub(context, appScope) }

    /** The browser's download flow: inspect with the selected engine, show its real formats, queue the choice. */
    val mediaDownloader: MediaDownloader by lazy {
        MediaDownloader(engineSelector, downloadManager, { settings.settings.first() }) { pageUrl, url -> browser.requestHeaders(pageUrl, url) }
    }

    val downloadRuntime: DownloadRuntime by lazy { DownloadRuntime(context, appScope, { downloadManager }, settings) }
}

class VidDownApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        container.downloadRuntime.start()
    }
}
