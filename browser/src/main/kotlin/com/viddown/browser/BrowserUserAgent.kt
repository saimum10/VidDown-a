package com.viddown.browser

/**
 * The User-Agent strings the in-app browser can send. [facebookInApp] is the one in use: the WebView's own string plus the marker of
 * Facebook's in-app browser. [androidChrome] (an Android Chrome look-alike without the WebView markers) is kept for reference and tests.
 * Only the Chrome major version is ever taken from the installed WebView.
 */
object BrowserUserAgent {
    /** Used when the WebView's own string has no readable Chrome version. */
    const val FALLBACK_CHROME_MAJOR = 120

    private val chromeVersion = Regex("Chrome/(\\d+)")

    /** The Chrome major version in [raw] (the WebView's default User-Agent), or [FALLBACK_CHROME_MAJOR]. */
    fun chromeMajor(raw: String): Int =
        chromeVersion.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: FALLBACK_CHROME_MAJOR

    /** An Android Chrome User-Agent for the Chrome major version found in [raw]. The same [raw] always gives the same string. */
    fun androidChrome(raw: String): String {
        val major = chromeMajor(raw)
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Mobile Safari/537.36"
    }

    /** The tail the Facebook and Messenger in-app browser adds to the WebView's own User-Agent. */
    private const val FACEBOOK_MARKER = "[FB_IAB/FB4A;FBAV/450.0.0.38.108;]"

    /** What a WebView says when its own string cannot be read: the usual shape of an Android WebView string. */
    private const val GENERIC_WEBVIEW =
        "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/$FALLBACK_CHROME_MAJOR.0.0.0 Mobile Safari/537.36"

    /**
     * The User-Agent of the in-app browser: the WebView's own default string, unchanged, plus the marker Facebook's and Messenger's
     * own in-app browser appends. Facebook knows that marker as "my own app's browser" and does not push the app to it. Nothing is
     * removed from the WebView string ("; wv" and "Version/4.0" stay), so it agrees with the WebView's other signals. An empty [raw]
     * (the default string could not be read) gives a typical WebView string; a string that already has the marker stays as it is.
     */
    fun facebookInApp(raw: String): String {
        val base = raw.trim().ifEmpty { GENERIC_WEBVIEW }
        return if (base.contains("FB_IAB/") || base.contains("FBAV/")) base else "$base $FACEBOOK_MARKER"
    }
}
