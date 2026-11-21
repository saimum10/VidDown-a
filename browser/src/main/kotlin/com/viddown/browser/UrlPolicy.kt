package com.viddown.browser

import java.net.URI
import java.net.URISyntaxException

sealed interface UrlDecision {
    /** An http(s) page (or about:blank, which WebView uses internally): the browser may load it. */
    data object Load : UrlDecision

    /**
     * A link that would start another app ([scheme]: fb, tiktok, intent, ...) but carries a safe https address of the same page.
     * The browser loads [url] instead; the app behind the link is never started.
     */
    data class WebFallback(val scheme: String, val url: String) : UrlDecision

    /** Anything else: never loaded, never handed to another app. [userMessage] says why in plain words. */
    data class Block(val scheme: String, val userMessage: String) : UrlDecision
}

/**
 * What the in-app browser may open. Only http and https pages are loaded: javascript:, data:, file:, content:, blob:, intent:,
 * market:, mailto:, tel: and every custom scheme are refused instead of being passed on to the system, so a web page can never use
 * a link to start another app or to read local files.
 *
 * One thing is allowed on top of that: an app link (fb:, tiktok:, intent:) that carries a safe https address of its own web version
 * is answered with [UrlDecision.WebFallback], so sites that try to open their app still work in the browser. The app is not started.
 */
object UrlPolicy {
    /** Schemes that are local or script content, not links to an app. */
    internal val NON_APP_SCHEMES = setOf("javascript", "data", "file", "content", "blob", "about", "vbscript", "filesystem")

    private val schemePattern = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

    fun schemeOf(url: String?): String? = url?.trim()?.let { schemePattern.find(it)?.groupValues?.get(1)?.lowercase() }

    fun decide(url: String?): UrlDecision {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return UrlDecision.Block("", "The address is empty.")
        if (trimmed.equals("about:blank", ignoreCase = true)) return UrlDecision.Load
        val scheme = schemeOf(trimmed)
            ?: return UrlDecision.Block("", "The address has no scheme (it must start with http:// or https://).")
        if (scheme == "http" || scheme == "https") {
            return if (hostOf(trimmed) == null) UrlDecision.Block(scheme, "The address has no host name.") else UrlDecision.Load
        }
        AppLinkFallback.resolve(trimmed)?.let { return UrlDecision.WebFallback(scheme, it) }
        return UrlDecision.Block(scheme, blockMessage(scheme))
    }

    private fun blockMessage(scheme: String): String =
        if (scheme in NON_APP_SCHEMES) {
            "VidDown only opens web pages (http and https). A link that uses \"$scheme:\" was blocked."
        } else {
            "This link tried to open another app (\"$scheme:\") and has no web version VidDown can open. " +
                "It was blocked and no app was started."
        }

    fun isHttp(url: String?): Boolean = schemeOf(url).let { it == "http" || it == "https" }

    fun isHttps(url: String?): Boolean = schemeOf(url) == "https"

    /** The lower-case host of an http(s) URL, or null when there is none or the URL does not parse. */
    fun hostOf(url: String?): String? {
        if (url == null || !isHttp(url)) return null
        return try {
            URI(url.trim()).host?.lowercase()?.takeIf { it.isNotEmpty() }
        } catch (e: URISyntaxException) {
            // A space or a stray character: fall back to a plain cut so history still shows a host.
            url.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@').substringBefore(':').lowercase().takeIf { it.isNotEmpty() }
        }
    }

    /** \"www.example.com\" -> \"example.com\": the short form shown in the address bar and in lists. */
    fun displayHost(url: String?): String? = hostOf(url)?.removePrefix("www.")

    /** The same address without its #fragment (a fragment never changes the resource). */
    fun withoutFragment(url: String): String = url.trim().substringBefore('#')

    /** The path of an http(s) URL without query and fragment, or null. */
    fun pathOf(url: String): String? {
        if (!isHttp(url)) return null
        val afterScheme = url.trim().substringAfter("://")
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return "/"
        return afterScheme.substring(slash).substringBefore('?').substringBefore('#')
    }

    /** The lower-case extension of the last path segment (\"mp4\"), or null. The query string is never looked at. */
    fun pathExtension(url: String): String? {
        val last = pathOf(url)?.substringAfterLast('/') ?: return null
        val dot = last.lastIndexOf('.')
        if (dot < 0 || dot == last.length - 1) return null
        return last.substring(dot + 1).lowercase().takeIf { it.length in 1..8 && it.all { c -> c.isLetterOrDigit() } }
    }
}
