package com.viddown.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLinkFallbackTest {
    /** The https address the browser would load for [link]; fails when the link is not turned into a web fallback. */
    private fun web(link: String): String {
        val decision = UrlPolicy.decide(link)
        assertTrue("$link should have a web fallback but was $decision", decision is UrlDecision.WebFallback)
        return (decision as UrlDecision.WebFallback).url
    }

    private fun assertBlocked(link: String) {
        val decision = UrlPolicy.decide(link)
        assertTrue("$link must stay blocked but was $decision", decision is UrlDecision.Block)
    }

    @Test
    fun anIntentWithABrowserFallbackOpensTheFallback() {
        val link = "intent://story#Intent;scheme=fb;package=com.facebook.katana;" +
            "S.browser_fallback_url=https%3A%2F%2Fm.facebook.com%2Fstory.php%3Fid%3D1%26x%3D2;end"
        assertEquals("https://m.facebook.com/story.php?id=1&x=2", web(link))
    }

    @Test
    fun anIntentForAnHttpsPageWithoutFallbackIsThatPage() {
        val link = "intent://www.tiktok.com/@someone/video/123#Intent;scheme=https;package=com.zhiliaoapp.musically;end"
        assertEquals("https://www.tiktok.com/@someone/video/123", web(link))
    }

    @Test
    fun aCustomSchemeThatCarriesAnHttpsAddressOpensThatAddress() {
        assertEquals("https://m.facebook.com/abc", web("fb://facewebmodal/f?href=https%3A%2F%2Fm.facebook.com%2Fabc"))
        assertEquals("https://example.org/p?q=1+2", web("someapp://open?url=https%3A%2F%2Fexample.org%2Fp%3Fq%3D1%2B2"))
    }

    @Test
    fun knownFacebookLinksOpenTheirMobileWebPage() {
        assertEquals("https://m.facebook.com/profile.php?id=1000123", web("fb://profile/1000123"))
        assertEquals("https://m.facebook.com/55", web("fb://page/55"))
        assertEquals("https://m.facebook.com/groups/9", web("fb://group/9"))
        assertEquals("https://m.facebook.com/events/77", web("fb://event/77"))
        assertEquals("https://m.facebook.com/watch/?v=42", web("fb://video?id=42"))
        assertEquals("https://m.facebook.com/", web("fb://feed"))
    }

    @Test
    fun knownTikTokLinksOpenTheirWebPage() {
        assertEquals("https://www.tiktok.com/@/video/7123456789", web("tiktok://video/7123456789"))
        assertEquals("https://www.tiktok.com/@some.user", web("tiktok://user?username=some.user"))
        assertEquals("https://www.tiktok.com/", web("snssdk1233://home"))
    }

    @Test
    fun linksWithoutASafeWebAddressStayBlocked() {
        // No fallback at all.
        assertBlocked("intent://scan#Intent;end")
        assertBlocked("fb://unknownthing")
        assertBlocked("fb://profile/not-a-number")
        assertBlocked("tiktok://user?username=bad%20name")
        assertBlocked("market://details?id=a")
        assertBlocked("vnd.youtube://abc")
        // A fallback that is not plain https.
        assertBlocked("intent://x#Intent;S.browser_fallback_url=http%3A%2F%2Fexample.com;end")
        assertBlocked("intent://x#Intent;scheme=http;end")
        assertBlocked("intent://x#Intent;S.browser_fallback_url=javascript%3Aalert(1);end")
        assertBlocked("fb://facewebmodal/f?href=https%3A%2F%2Fuser%3Apw%40evil.test%2F")
        assertBlocked("fb://facewebmodal/f?href=https%3A%2F%2Fa.test%2F%20b")
        // A fallback that is itself an app link is not followed.
        assertBlocked("intent://x#Intent;S.browser_fallback_url=intent%3A%2F%2Fy%23Intent%3Bend;end")
        assertBlocked("fb://facewebmodal/f?href=fb%3A%2F%2Fprofile%2F1")
    }

    @Test
    fun localAndScriptContentIsNeverGivenAFallback() {
        for (bad in listOf(
            "javascript:void(0)?url=https://example.org/",
            "data:text/html,hi?url=https://example.org/",
            "file:///sdcard/a?url=https://example.org/",
            "blob:https://example.org/x?url=https://example.org/",
            "mailto:a@b.c?url=https://example.org/",
            "tel:123?url=https://example.org/",
        )) {
            assertBlocked(bad)
        }
    }

    @Test
    fun theBlockMessageSaysWhatHappened() {
        val app = UrlPolicy.decide("fb://unknownthing") as UrlDecision.Block
        assertEquals("fb", app.scheme)
        assertTrue(app.userMessage.contains("fb:"))
        assertTrue(app.userMessage.contains("no app was started"))
        val script = UrlPolicy.decide("javascript:alert(1)") as UrlDecision.Block
        assertTrue(script.userMessage.contains("only opens web pages"))
    }

    @Test
    fun aFallbackIsAlwaysSomethingTheBrowserWouldLoad() {
        val url = web("intent://x#Intent;S.browser_fallback_url=https%3A%2F%2Fexample.org%2Fa;end")
        assertEquals(UrlDecision.Load, UrlPolicy.decide(url))
    }
}
