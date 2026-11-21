package com.viddown.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.viddown.browser.BrowserUserAgent
import com.viddown.browser.ResourceEvidence
import com.viddown.browser.UrlDecision
import com.viddown.browser.UrlPolicy

enum class PageErrorKind { SSL, NETWORK, OTHER }

/** A main-frame load that failed. [description] is plain words; nothing is hidden behind a \"proceed anyway\" button. */
data class PageError(val url: String?, val kind: PageErrorKind, val description: String)

/** What the WebView clients report to the screen. All calls except resource interception happen on the main thread. */
interface BrowserWebHost {
    fun onPageStarted(tabId: String, url: String)
    fun onPageFinished(tabId: String, url: String, title: String?)
    fun onNavigationState(tabId: String, canGoBack: Boolean, canGoForward: Boolean)
    fun onBlocked(tabId: String, block: UrlDecision.Block)
    fun onLoadError(tabId: String, error: PageError)
    fun onRenderGone(tabId: String)
    fun onDownloadLink(tabId: String, url: String, mimeType: String?)

    /** A long press on a link or video: [url] is the address the page itself would copy (never the address of the page shown). */
    fun onLongPressLink(tabId: String, url: String) {}
    fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
    fun onHideCustomView()
}

/**
 * One client per tab. It enforces the URL policy, never lets a certificate error through, and reports every sub-request to the media
 * detection of this tab. It holds no WebView (the view is passed in by each callback).
 */
class TabWebViewClient(
    private val tabId: String,
    private val hub: BrowserHub,
    private val host: BrowserWebHost,
) : WebViewClient() {
    @Volatile private var mainUrl: String? = null
    private var lastFallback: String? = null
    private var lastFallbackAt = 0L

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!LINK_CHECKS_ENABLED) return !passesWithoutChecks(request.url.toString())
        val decision = UrlPolicy.decide(request.url.toString())
        return when (decision) {
            UrlDecision.Load -> false
            is UrlDecision.WebFallback -> {
                // Only the page itself is sent to its web version: a frame inside a page must never take over the tab.
                if (request.isForMainFrame) openWebVersion(view, decision)
                true
            }
            is UrlDecision.Block -> {
                if (request.isForMainFrame) {
                    val blank = view.url.isNullOrEmpty() || view.url == "about:blank"
                    if (blank && decision.scheme == "fb") {
                        // Facebook answered the very first load with an app link we have no web address for: open its own web
                        // version (a fixed https address, nothing taken from the link) instead of leaving the tab empty.
                        openWebVersion(view, UrlDecision.WebFallback(decision.scheme, FACEBOOK_WEB_HOME))
                    } else {
                        // A page that already shows something and tries to start its app by itself (no tap) stays as it is, quietly.
                        if (blank || request.hasGesture()) host.onBlocked(tabId, decision)
                        // Nothing was ever shown for this tab: say why instead of leaving a white page.
                        if (blank) host.onLoadError(tabId, PageError(request.url.toString(), PageErrorKind.OTHER, decision.userMessage))
                    }
                }
                true
            }
        }
    }

    /**
     * The link checks are switched off (see [LINK_CHECKS_ENABLED]): no block message, no web-version redirect, no error page for a link.
     * Pages, scripts and in-page content (http, https, about, javascript, data, blob) go straight to the WebView. Anything else is an app
     * or system link (fb:, tiktok:, intent:, market:, mailto:, file: ...): the WebView cannot load it and VidDown never starts another
     * app, so the navigation is dropped quietly and the page stays as it is, where the WebView would otherwise replace it with its own
     * error page.
     */
    private fun passesWithoutChecks(url: String): Boolean =
        UrlPolicy.schemeOf(url).let { it == null || it in OPEN_SCHEMES }

    /**
     * A site (Facebook, TikTok, ...) tried to open its app and gave a safe https address for the same page: load that address here.
     * The app is not started and nothing is handed to the system.
     */
    private fun openWebVersion(view: WebView, decision: UrlDecision.WebFallback) {
        val now = SystemClock.uptimeMillis()
        // A site that sends its web version straight back to the app link would loop for ever: the same target twice in a row stops.
        if (decision.url == lastFallback && now - lastFallbackAt < LOOP_WINDOW_MS) {
            host.onBlocked(
                tabId,
                UrlDecision.Block(
                    decision.scheme,
                    "This site keeps trying to open its app (\"${decision.scheme}:\"). VidDown cannot open apps, so the page was left as it is.",
                ),
            )
            return
        }
        lastFallback = decision.url
        lastFallbackAt = now
        view.loadUrl(decision.url)
    }

    /** Background thread. Only reports what the page loads; the request itself is never changed or blocked. */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (!request.isForMainFrame) {
            try {
                hub.detections.onRequest(tabId, ResourceEvidence(request.url.toString(), request.method ?: "GET", request.requestHeaders ?: emptyMap()))
            } catch (t: Throwable) {
                // Detection is a convenience: it must never break page loading.
            }
        }
        return null
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        val u = url ?: return
        mainUrl = u
        hub.tabs.onPageStarted(tabId, u)
        hub.detections.beginPage(tabId, u)
        host.onPageStarted(tabId, u)
        host.onNavigationState(tabId, view.canGoBack(), view.canGoForward())
    }

    override fun onPageFinished(view: WebView, url: String?) {
        val u = url ?: return
        hub.tabs.onPageFinished(tabId, u, view.title)
        host.onPageFinished(tabId, u, view.title)
        host.onNavigationState(tabId, view.canGoBack(), view.canGoForward())
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        if (url != null && UrlPolicy.isHttp(url)) hub.tabs.onUrlChanged(tabId, url)
        host.onNavigationState(tabId, view.canGoBack(), view.canGoForward())
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        val code = error.errorCode
        val kind = if (code == ERROR_FAILED_SSL_HANDSHAKE) PageErrorKind.SSL else PageErrorKind.NETWORK
        val raw = error.description?.toString().orEmpty()
        val text = if (raw.contains("CLEARTEXT", ignoreCase = true)) {
            "This page uses an unencrypted connection (http). VidDown only opens pages over https."
        } else when (code) {
            ERROR_HOST_LOOKUP -> "The server could not be found. Check the address and your connection."
            ERROR_CONNECT, ERROR_IO -> "The connection to the server failed."
            ERROR_TIMEOUT -> "The server took too long to answer."
            ERROR_FAILED_SSL_HANDSHAKE -> "A secure connection could not be established."
            ERROR_BAD_URL, ERROR_UNSUPPORTED_SCHEME -> "This address cannot be opened."
            ERROR_TOO_MANY_REQUESTS -> "The server is limiting requests. Try again later."
            else -> raw.takeIf { it.isNotBlank() } ?: "The page could not be loaded."
        }
        host.onLoadError(tabId, PageError(request.url.toString(), kind, text))
    }

    /** Certificate errors are never bypassed: the load is cancelled and, for the page itself, explained. */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        val failing = error.url
        if (failing == null || failing == mainUrl || failing.substringBefore('#') == mainUrl?.substringBefore('#')) {
            host.onLoadError(tabId, PageError(failing, PageErrorKind.SSL, sslText(error.primaryError)))
        }
    }

    private fun sslText(primary: Int): String = when (primary) {
        SslError.SSL_EXPIRED -> "The site's security certificate has expired."
        SslError.SSL_NOTYETVALID -> "The site's security certificate is not valid yet. Check the date on your device."
        SslError.SSL_IDMISMATCH -> "The certificate belongs to a different site than the one you asked for."
        SslError.SSL_UNTRUSTED -> "The certificate was not issued by an authority this device trusts."
        SslError.SSL_DATE_INVALID -> "The certificate's date is not valid. Check the date on your device."
        else -> "The site's security certificate is not valid."
    } + " VidDown does not open sites with an invalid certificate."

    /** The renderer crashed or was killed: tell the screen to rebuild the view instead of letting the app die. */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        host.onRenderGone(tabId)
        return true
    }

    private companion object {
        /**
         * Master switch of the link block system for links inside pages. false = off (nothing is blocked with a message, nothing is
         * redirected to a web version); true = the UrlPolicy rules decide again (messages, web-version fallbacks, Facebook home).
         * Addresses typed, shared or restored from saved tabs are still checked by UrlPolicy elsewhere.
         */
        const val LINK_CHECKS_ENABLED = false
        val OPEN_SCHEMES = setOf("http", "https", "about", "javascript", "data", "blob")
        const val LOOP_WINDOW_MS = 5_000L
        const val FACEBOOK_WEB_HOME = "https://m.facebook.com/"
    }
}

class TabChromeClient(
    private val tabId: String,
    private val hub: BrowserHub,
    private val host: BrowserWebHost,
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView, newProgress: Int) {
        hub.tabs.onProgress(tabId, newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        hub.tabs.onTitle(tabId, title)
    }

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) = host.onShowCustomView(view, callback)

    override fun onHideCustomView() = host.onHideCustomView()

    /** A web page never gets the camera, the microphone or other device resources. */
    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        callback.invoke(origin, false, false)
    }
}

object BrowserWebViews {
    /** A WebView with the settings VidDown allows. Everything a page does not need to show video stays switched off. */
    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, javascriptEnabled: Boolean): WebView {
        val view = WebView(context)
        val s = view.settings
        s.setJavaScriptEnabled(javascriptEnabled)
        s.setDomStorageEnabled(true)
        // The WebView's own User-Agent plus the marker of Facebook's and Messenger's in-app browser: Facebook knows that browser and
        // does not push its app to it (see BrowserUserAgent.facebookInApp).
        s.setUserAgentString(BrowserUserAgent.facebookInApp(s.userAgentString))
        // WebView also sends "X-Requested-With: <package name>" with every request. Sites read it to detect an embedded view, so it is
        // switched off for every origin (an empty allow-list means the header is never added).
        // androidx.webkit marks the WebViewFeature constant as library-group only (lint: RestrictedApi), but its own @RequiresFeature
        // annotation on setRequestedWithHeaderOriginAllowList names the feature by this plain string, which isFeatureSupported accepts.
        if (WebViewFeature.isFeatureSupported("REQUESTED_WITH_HEADER_ALLOW_LIST")) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }
        // No local files, no content:// reads, whatever the page asks for.
        s.setAllowFileAccess(false)
        s.setAllowContentAccess(false)
        @Suppress("DEPRECATION") s.setAllowFileAccessFromFileURLs(false)
        @Suppress("DEPRECATION") s.setAllowUniversalAccessFromFileURLs(false)
        s.setGeolocationEnabled(false)
        s.setMediaPlaybackRequiresUserGesture(true)
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW)
        // target=_blank and window.open load in the same view: a page cannot open windows by itself.
        s.setSupportMultipleWindows(false)
        s.setJavaScriptCanOpenWindowsAutomatically(false)
        s.setSupportZoom(true)
        s.setBuiltInZoomControls(true)
        s.setDisplayZoomControls(false)
        s.setUseWideViewPort(true)
        s.setLoadWithOverviewMode(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s.setSafeBrowsingEnabled(true)
        // Sign-in of big sites (Google/YouTube, Facebook) is spread over several of their own domains and needs cookies that are set
        // while another address is shown. With this off, a page can be signed in and a second one on the same site still look signed out.
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        return view
    }

    /** Saves the page state when asked, then releases the view completely. Safe on a view whose renderer is gone. */
    fun dispose(view: WebView, saveInto: ((Bundle) -> Unit)?) {
        if (saveInto != null) {
            try {
                val bundle = Bundle()
                view.saveState(bundle)
                saveInto(bundle)
            } catch (t: Throwable) {
                // A crashed renderer cannot save: the tab reloads its address instead.
            }
        }
        try {
            view.stopLoading()
            view.setDownloadListener(null)
            view.webViewClient = WebViewClient()
            view.webChromeClient = WebChromeClient()
            (view.parent as? ViewGroup)?.removeView(view)
            view.removeAllViews()
            view.destroy()
        } catch (t: Throwable) {
            // Nothing more can be done for a view that is already gone.
        }
    }
}
