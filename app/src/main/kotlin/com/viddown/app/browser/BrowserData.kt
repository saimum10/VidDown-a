package com.viddown.app.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** What the web engine stores on the device besides the history list: cookies, site storage and the page cache. */
object BrowserData {
    /** Removes every cookie and all site storage. Returns false when the web engine reported that nothing could be removed. */
    suspend fun clearCookiesAndSiteData(): Boolean {
        val removed = suspendCancellableCoroutine { cont ->
            try {
                CookieManager.getInstance().removeAllCookies { ok -> if (cont.isActive) cont.resume(ok) }
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(false)
            }
        }
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (t: Throwable) {
            // Cookies are what matters for being signed in; storage is best effort.
        }
        return removed
    }

    /** Empties the page cache shared by all tabs. Must run on the main thread (it creates a short-lived WebView). */
    fun clearCache(context: Context) {
        val view = WebView(context)
        try {
            view.clearCache(true)
        } finally {
            view.destroy()
        }
    }
}
