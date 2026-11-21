package com.viddown.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    @Test
    fun onlyHttpAndHttpsPagesAreLoaded() {
        assertEquals(UrlDecision.Load, UrlPolicy.decide("https://example.com/a?b=c"))
        assertEquals(UrlDecision.Load, UrlPolicy.decide("http://example.com"))
        assertEquals(UrlDecision.Load, UrlPolicy.decide("about:blank"))
        for (bad in listOf("javascript:alert(1)", "data:text/html,hi", "file:///sdcard/a", "content://x/y", "intent://x#Intent;end", "market://details?id=a", "mailto:a@b.c", "tel:123", "blob:https://x/y", "viddown://x")) {
            assertTrue("$bad must be blocked", UrlPolicy.decide(bad) is UrlDecision.Block)
        }
        assertTrue(UrlPolicy.decide("") is UrlDecision.Block)
        assertTrue(UrlPolicy.decide("example.com") is UrlDecision.Block)
        assertTrue(UrlPolicy.decide("https://") is UrlDecision.Block)
    }

    @Test
    fun blockedSchemeIsNamedInTheMessage() {
        val d = UrlPolicy.decide("intent://scan#Intent;end") as UrlDecision.Block
        assertEquals("intent", d.scheme)
        assertTrue(d.userMessage.contains("intent:"))
    }

    @Test
    fun hostAndDisplayHost() {
        assertEquals("www.example.com", UrlPolicy.hostOf("https://WWW.Example.com:8443/x"))
        assertEquals("example.com", UrlPolicy.displayHost("https://www.example.com/x"))
        assertNull(UrlPolicy.hostOf("mailto:a@b.c"))
        assertEquals("example.com", UrlPolicy.hostOf("https://example.com/a b"))
    }

    @Test
    fun pathExtensionIgnoresQueryAndFragment() {
        assertEquals("mp4", UrlPolicy.pathExtension("https://x.test/a/clip.MP4?token=1.webm#t=3"))
        assertNull(UrlPolicy.pathExtension("https://x.test/watch?v=abc.mp4"))
        assertNull(UrlPolicy.pathExtension("https://x.test/video"))
        assertNull(UrlPolicy.pathExtension("https://x.test/"))
        assertEquals("/a/b", UrlPolicy.pathOf("https://x.test/a/b?q#f"))
        assertFalse(UrlPolicy.isHttp("ftp://x"))
        assertEquals("https://x.test/a", UrlPolicy.withoutFragment("https://x.test/a#top"))
    }
}
