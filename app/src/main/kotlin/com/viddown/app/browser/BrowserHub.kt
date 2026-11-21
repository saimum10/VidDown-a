package com.viddown.app.browser

import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.viddown.browser.BrowserUserAgent
import com.viddown.browser.HttpUrlMediaProber
import com.viddown.browser.MediaDetectionManager
import com.viddown.browser.TabManager
import com.viddown.browser.TabsSnapshotCodec
import com.viddown.browser.UrlPolicy
import com.viddown.browser.toSnapshot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Load [url] in [tabId]". It stays pending until the screen that shows the tab has handled it, so it is not lost when the request
 * is made a moment before the screen starts listening (a navigation from Home) or while the tab's WebView does not exist yet.
 */
data class LoadRequest(val id: Long, val tabId: String, val url: String)

/**
 * Process-wide browser state: the tab list, what each tab's page contains (media detections) and the saved WebView state of tabs that
 * are not on screen. It never holds a WebView: the one live WebView belongs to the screen that shows the active tab and is created
 * and destroyed with it. The tab list is written to a small file (address and title only), so tabs survive the process being killed.
 */
class BrowserHub(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val tabsFile = TabsFile(File(appContext.filesDir, "browser/tabs.txt"))

    val tabs = TabManager()
    val webStates = WebViewStateStore()

    /** The string every page gets (see BrowserWebViews.create), so a download looks like the page that found it. */
    private val userAgent: String by lazy {
        try {
            BrowserUserAgent.facebookInApp(WebSettings.getDefaultUserAgent(appContext))
        } catch (e: Throwable) {
            BrowserUserAgent.facebookInApp("")
        }
    }

    val detections = MediaDetectionManager(
        prober = HttpUrlMediaProber(),
        scope = scope,
        probeHeaders = { tabId, url -> requestHeaders(tabs.state.value.tabs.firstOrNull { it.id == tabId }?.url, url) },
    )

    private val handledLaunchKeys = HashSet<String>()

    /** True the first time a navigation entry with an address is seen: returning to the screen must not reload the address again. */
    @Synchronized
    fun markLaunchHandled(key: String): Boolean {
        if (handledLaunchKeys.size > 64) handledLaunchKeys.clear()
        return handledLaunchKeys.add(key)
    }

    private var loadCounter = 0L
    private val _pendingLoad = MutableStateFlow<LoadRequest?>(null)
    val pendingLoad: StateFlow<LoadRequest?> = _pendingLoad.asStateFlow()

    /** The screen loaded (or deliberately ignored) request [requestId]. */
    fun loadHandled(requestId: Long) {
        _pendingLoad.update { if (it?.id == requestId) null else it }
    }

    init {
        tabsFile.load()?.let { tabs.restore(it) }
        tabs.ensureTab()
        startPersisting()
    }

    /** Opens [url] in the active tab (the first tab is created when none exists yet). */
    @Synchronized
    fun openInActiveTab(url: String) {
        val id = tabs.ensureTab()
        // The saved page of this tab is not what the user asked for any more.
        webStates.remove(id)
        tabs.navigate(id, url)
        _pendingLoad.value = LoadRequest(++loadCounter, id, url)
    }

    /** Cookie, Referer and User-Agent as the page itself would send them to [url]. Kept in memory only, never stored. */
    fun requestHeaders(pageUrl: String?, url: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = userAgent
        if (pageUrl != null && UrlPolicy.isHttp(pageUrl)) headers["Referer"] = pageUrl
        val cookie = try {
            CookieManager.getInstance().getCookie(url)
        } catch (e: Throwable) {
            null
        }
        if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
        return headers
    }

    fun closeTab(id: String) {
        _pendingLoad.update { if (it?.tabId == id) null else it }
        tabs.close(id)
        webStates.remove(id)
        detections.closeTab(id)
    }

    fun closeAllTabs() {
        val ids = tabs.state.value.tabs.map { it.id }
        _pendingLoad.value = null
        tabs.closeAll()
        ids.forEach {
            webStates.remove(it)
            detections.closeTab(it)
        }
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun startPersisting() {
        scope.launch(Dispatchers.IO) {
            tabs.state.map { it.toSnapshot() }.distinctUntilChanged().debounce(500).collect { snapshot ->
                tabsFile.save(snapshot)
                webStates.retainOnly(snapshot.tabs.map { it.id }.toSet())
            }
        }
    }
}

/** The tab list as a small text file, written atomically. A damaged or missing file simply means \"no saved tabs\". */
class TabsFile(private val file: File) {
    fun load(): com.viddown.browser.TabsSnapshot? = try {
        if (file.isFile) TabsSnapshotCodec.decode(file.readText()) else null
    } catch (e: Exception) {
        null
    }

    fun save(snapshot: com.viddown.browser.TabsSnapshot) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(TabsSnapshotCodec.encode(snapshot))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            // Tabs are a convenience: a failed write must never disturb browsing.
        }
    }
}

/**
 * Saved WebView state (history, scroll position, form data) of tabs that are not on screen, so that switching tabs or leaving the
 * browser screen does not lose the page. A Bundle is plain data, not a WebView: holding it here leaks nothing. At most [max] states
 * are kept, the oldest saved ones go first, and the state of a closed tab is dropped. It is memory only: after the process is killed
 * a tab reloads its last address.
 */
class WebViewStateStore(private val max: Int = 16) {
    private val states = LinkedHashMap<String, Bundle>()

    @Synchronized
    fun put(tabId: String, state: Bundle) {
        states.remove(tabId)
        states[tabId] = state
        while (states.size > max) states.remove(states.keys.first())
    }

    @Synchronized
    fun get(tabId: String): Bundle? = states[tabId]

    @Synchronized
    fun remove(tabId: String) {
        states.remove(tabId)
    }

    @Synchronized
    fun retainOnly(ids: Set<String>) {
        states.keys.retainAll(ids)
    }

    @Synchronized
    fun size(): Int = states.size
}
