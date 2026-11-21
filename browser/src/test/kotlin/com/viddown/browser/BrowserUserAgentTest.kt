package com.viddown.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUserAgentTest {
    private val webView =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UP1A.231105.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36"

    @Test
    fun theResultIsAnAndroidChromeWithTheWebViewsChromeVersion() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            BrowserUserAgent.androidChrome(webView),
        )
    }

    @Test
    fun theChromeMajorVersionFollowsTheInstalledWebView() {
        val newer = webView.replace("Chrome/120.0.6099.230", "Chrome/131.0.6778.39")
        assertTrue(BrowserUserAgent.androidChrome(newer).contains("Chrome/131.0.0.0"))
    }

    @Test
    fun theResultHasNeitherEmbeddedViewMarker() {
        val out = BrowserUserAgent.androidChrome(webView)
        assertFalse(out.contains("wv"))
        assertFalse(out.contains("Version/"))
        assertTrue(out.contains("Android"))
        assertTrue(out.contains("Mobile Safari"))
    }

    @Test
    fun applyingItToItsOwnResultChangesNothing() {
        val once = BrowserUserAgent.androidChrome(webView)
        assertEquals(once, BrowserUserAgent.androidChrome(once))
    }

    @Test
    fun anUnreadableInputFallsBackToTheDefaultChromeVersion() {
        val expected = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${BrowserUserAgent.FALLBACK_CHROME_MAJOR}.0.0.0 Mobile Safari/537.36"
        assertEquals(expected, BrowserUserAgent.androidChrome(""))
        assertEquals(expected, BrowserUserAgent.androidChrome("Mozilla/5.0 (Linux; Android) VidDown"))
    }

    @Test
    fun facebookInAppKeepsTheWebViewStringAndAddsTheFacebookMarker() {
        val out = BrowserUserAgent.facebookInApp(webView)
        assertTrue(out.startsWith(webView))
        assertTrue(out.contains("; wv)"))
        assertTrue(out.contains("Version/4.0"))
        assertTrue(out.endsWith("[FB_IAB/FB4A;FBAV/450.0.0.38.108;]"))
    }

    @Test
    fun facebookInAppDoesNotAddTheMarkerTwice() {
        val once = BrowserUserAgent.facebookInApp(webView)
        assertEquals(once, BrowserUserAgent.facebookInApp(once))
    }

    @Test
    fun facebookInAppWithAnUnreadableStringUsesATypicalWebViewString() {
        val out = BrowserUserAgent.facebookInApp("")
        assertTrue(out.contains("; wv)"))
        assertTrue(out.contains("Chrome/${BrowserUserAgent.FALLBACK_CHROME_MAJOR}.0.0.0"))
        assertTrue(out.endsWith("[FB_IAB/FB4A;FBAV/450.0.0.38.108;]"))
    }
}
