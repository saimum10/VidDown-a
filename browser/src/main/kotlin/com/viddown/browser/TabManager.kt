package com.viddown.browser

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One browser tab as the rest of the app sees it. A tab never holds a WebView: that lives only in the screen that shows it. */
data class Tab(
    val id: String,
    /** The address last committed in this tab, or null for a blank tab. */
    val url: String? = null,
    val title: String? = null,
    val isLoading: Boolean = false,
    /** 0..100, meaningful while [isLoading]. */
    val progress: Int = 0,
) {
    /** What a list or the tab switcher shows for this tab. */
    val displayTitle: String? get() = title?.takeIf { it.isNotBlank() } ?: UrlPolicy.displayHost(url)
}

data class TabsState(val tabs: List<Tab> = emptyList(), val activeId: String? = null) {
    val active: Tab? get() = tabs.firstOrNull { it.id == activeId }
}

sealed interface OpenTabResult {
    data class Opened(val id: String) : OpenTabResult
    data class LimitReached(val max: Int) : OpenTabResult
}

/** The part of a tab that survives the process: no loading state, no progress. */
data class SavedTab(val id: String, val url: String?, val title: String?)

data class TabsSnapshot(val tabs: List<SavedTab>, val activeId: String?)

/** What would be saved for this state: progress and loading flags are left out, so they never cause a write. */
fun TabsState.toSnapshot(): TabsSnapshot = TabsSnapshot(tabs.map { SavedTab(it.id, it.url, it.title) }, activeId)

/**
 * Open, close and switch tabs; remember what each tab shows. Pure bookkeeping with no Android classes and no WebView: the screen
 * that shows the active tab owns the one live WebView, saves its state when it goes away and restores it later. At most
 * [maxTabs] tabs exist, so the number of saved WebView states stays bounded too.
 */
class TabManager(
    val maxTabs: Int = DEFAULT_MAX_TABS,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _state = MutableStateFlow(TabsState())
    val state: StateFlow<TabsState> = _state.asStateFlow()

    /** Makes sure at least one tab exists (the first start) and returns the active tab's id. */
    fun ensureTab(): String {
        var id = ""
        _state.update { s ->
            if (s.tabs.isNotEmpty()) {
                id = s.activeId ?: s.tabs.first().id
                if (s.activeId == id) s else s.copy(activeId = id)
            } else {
                val tab = Tab(newId())
                id = tab.id
                TabsState(listOf(tab), tab.id)
            }
        }
        return id
    }

    /** Opens a tab after the active one. A blank tab has no address. */
    fun open(url: String? = null, activate: Boolean = true): OpenTabResult {
        var result: OpenTabResult = OpenTabResult.LimitReached(maxTabs)
        _state.update { s ->
            if (s.tabs.size >= maxTabs) {
                result = OpenTabResult.LimitReached(maxTabs)
                return@update s
            }
            val tab = Tab(id = newId(), url = url, isLoading = url != null)
            result = OpenTabResult.Opened(tab.id)
            val at = s.tabs.indexOfFirst { it.id == s.activeId }.let { if (it < 0) s.tabs.size else it + 1 }
            val list = s.tabs.toMutableList().also { it.add(at, tab) }
            TabsState(list, if (activate || s.activeId == null) tab.id else s.activeId)
        }
        return result
    }

    /**
     * Closes a tab. The neighbour on the right (else the left) becomes active. Closing the last tab leaves a fresh blank tab,
     * so the browser never has zero tabs. Returns the id of the tab that is active afterwards.
     */
    fun close(id: String): String? {
        var active: String? = null
        _state.update { s ->
            val index = s.tabs.indexOfFirst { it.id == id }
            if (index < 0) {
                active = s.activeId
                return@update s
            }
            val rest = s.tabs.filterNot { it.id == id }
            if (rest.isEmpty()) {
                val tab = Tab(newId())
                active = tab.id
                return@update TabsState(listOf(tab), tab.id)
            }
            val nextActive = if (s.activeId != id) s.activeId else rest[minOf(index, rest.size - 1)].id
            active = nextActive
            TabsState(rest, nextActive)
        }
        return active
    }

    /** Closes every tab and leaves one blank tab. */
    fun closeAll(): String {
        val tab = Tab(newId())
        _state.value = TabsState(listOf(tab), tab.id)
        return tab.id
    }

    fun switchTo(id: String) {
        _state.update { s -> if (s.tabs.any { it.id == id } && s.activeId != id) s.copy(activeId = id) else s }
    }

    /** The user (or the app) asked [id] to open [url]: it is loading now, the old title no longer applies. */
    fun navigate(id: String, url: String) = change(id) { it.copy(url = url, title = null, isLoading = true, progress = 0) }

    fun onPageStarted(id: String, url: String?) = change(id) { it.copy(url = url ?: it.url, title = if (url != null && url != it.url) null else it.title, isLoading = true, progress = 0) }

    fun onProgress(id: String, progress: Int) = change(id) { it.copy(progress = progress.coerceIn(0, 100), isLoading = progress < 100) }

    fun onTitle(id: String, title: String?) = change(id) { it.copy(title = title?.trim()?.takeIf { t -> t.isNotEmpty() }) }

    fun onPageFinished(id: String, url: String?, title: String?) = change(id) {
        it.copy(
            url = url ?: it.url,
            title = title?.trim()?.takeIf { t -> t.isNotEmpty() } ?: it.title,
            isLoading = false,
            progress = 100,
        )
    }

    /** The address changed without a new page load (a single-page app changed its address, or a redirect finished). */
    fun onUrlChanged(id: String, url: String) = change(id) { if (it.url == url) it else it.copy(url = url) }

    fun onLoadStopped(id: String) = change(id) { it.copy(isLoading = false) }

    private fun change(id: String, f: (Tab) -> Tab) {
        _state.update { s ->
            val index = s.tabs.indexOfFirst { it.id == id }
            if (index < 0) return@update s
            val updated = f(s.tabs[index])
            if (updated == s.tabs[index]) s else s.copy(tabs = s.tabs.toMutableList().also { it[index] = updated })
        }
    }

    // -------------------------------------------------------------------------------------------------------- persistence

    fun snapshot(): TabsSnapshot = _state.value.toSnapshot()

    /** Replaces the tabs with a saved list (after the process was recreated). Does nothing for an empty snapshot. */
    fun restore(snapshot: TabsSnapshot) {
        val tabs = snapshot.tabs.distinctBy { it.id }.take(maxTabs).map { Tab(id = it.id, url = it.url, title = it.title) }
        if (tabs.isEmpty()) return
        _state.value = TabsState(tabs, snapshot.activeId?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.first().id)
    }

    companion object {
        const val DEFAULT_MAX_TABS = 12
    }
}

/**
 * Text form of a [TabsSnapshot] (one line per tab, fields URI-encoded), so the tabs can be written to a small file. Unreadable
 * lines are skipped; a file that is not ours decodes to null.
 */
object TabsSnapshotCodec {
    private const val HEADER = "viddown-tabs-v1"

    fun encode(snapshot: TabsSnapshot): String = buildString {
        append(HEADER).append('\n')
        append("active\t").append(enc(snapshot.activeId.orEmpty())).append('\n')
        for (t in snapshot.tabs) {
            append("tab\t").append(enc(t.id)).append('\t').append(enc(t.url.orEmpty())).append('\t').append(enc(t.title.orEmpty())).append('\n')
        }
    }

    fun decode(text: String?): TabsSnapshot? {
        if (text == null) return null
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != HEADER) return null
        var active: String? = null
        val tabs = ArrayList<SavedTab>()
        for (line in lines.drop(1)) {
            val f = line.split('\t')
            try {
                when {
                    f.size == 2 && f[0] == "active" -> active = dec(f[1]).takeIf { it.isNotEmpty() }
                    f.size == 4 && f[0] == "tab" -> {
                        val id = dec(f[1])
                        if (id.isEmpty()) continue
                        val url = dec(f[2]).takeIf { it.isNotEmpty() && UrlPolicy.decide(it) == UrlDecision.Load }
                        tabs += SavedTab(id, url, dec(f[3]).takeIf { it.isNotEmpty() })
                    }
                }
            } catch (e: IllegalArgumentException) {
                continue
            }
        }
        return TabsSnapshot(tabs, active)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
}
