package com.viddown.app.browser

import android.os.SystemClock
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viddown.browser.PagePlayer
import com.viddown.browser.PagePlayerReport
import com.viddown.browser.UrlDecision
import com.viddown.browser.UrlPolicy

/**
 * The screen's handle on the one live WebView of the active tab. It exists only inside the composition that created it (a
 * `remember`ed object), never in a ViewModel or a singleton, so it cannot keep a WebView alive after the screen is gone.
 */
class WebViewController {
    private var view: WebView? = null
    var tabId: String? = null
        private set

    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    /** Set when the renderer crashed: the saved state is not trusted and is dropped instead of restored. */
    var discardState = false

    private var lastUrl: String? = null
    private var lastAt = 0L

    fun attach(view: WebView, tabId: String) {
        this.view = view
        this.tabId = tabId
        discardState = false
        canGoBack = false
        canGoForward = false
    }

    fun detach(view: WebView) {
        if (this.view === view) {
            this.view = null
            tabId = null
            canGoBack = false
            canGoForward = false
        }
    }

    val isAttached: Boolean get() = view != null

    /** Loads an http(s) address. The same address asked twice within a second is loaded once. */
    fun load(url: String) {
        val v = view ?: return
        if (UrlPolicy.decide(url) != UrlDecision.Load) return
        val now = SystemClock.uptimeMillis()
        if (url == lastUrl && now - lastAt < 1_000) return
        lastUrl = url
        lastAt = now
        v.loadUrl(url)
    }

    fun goBack() {
        view?.takeIf { it.canGoBack() }?.goBack()
    }

    fun goForward() {
        view?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload() {
        view?.reload()
    }

    fun stop() {
        view?.stopLoading()
    }

    fun onResume() {
        view?.onResume()
    }

    fun onPause() {
        view?.onPause()
    }

    /**
     * Asks the page which <video>/<audio> elements it has. A fixed read-only script: no bridge is exposed to the page, and the
     * answer is parsed strictly and treated as untrusted. Does nothing when JavaScript is off or no page is shown.
     */
    fun scanPlayers(onReport: (PagePlayerReport) -> Unit) {
        val v = view ?: return
        if (!v.settings.javaScriptEnabled) return
        try {
            v.evaluateJavascript(PagePlayer.SCRIPT) { raw -> PagePlayer.parse(raw)?.let(onReport) }
        } catch (t: Throwable) {
            // A view that is being torn down cannot run scripts: nothing to report.
        }
    }
}
