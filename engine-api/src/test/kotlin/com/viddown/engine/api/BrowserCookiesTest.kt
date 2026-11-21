package com.viddown.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCookiesTest {
    @Test
    fun theHeaderBecomesOneNetscapeLinePerCookieForTheSitesOwnDomain() {
        val out = BrowserCookies.toNetscapeFile("sid=abc; theme=dark", "https://www.example.com/a", 1000)
        assertEquals(
            "# Netscape HTTP Cookie File\n" +
                ".example.com\tTRUE\t/\tTRUE\t1000\tsid\tabc\n" +
                ".example.com\tTRUE\t/\tTRUE\t1000\ttheme\tdark\n",
            out,
        )
    }

    @Test
    fun subdomainsOfOneSiteShareTheSitesDomainAndYouTubeAddressesShareYouTube() {
        assertTrue(BrowserCookies.toNetscapeFile("a=1", "https://m.facebook.com/x", 1)!!.contains("\n.facebook.com\tTRUE\t"))
        assertTrue(BrowserCookies.toNetscapeFile("a=1", "https://video.site.com.bd/x", 1)!!.contains("\n.site.com.bd\tTRUE\t"))
        assertTrue(BrowserCookies.toNetscapeFile("a=1", "https://sub.example.co.uk/x", 1)!!.contains("\n.example.co.uk\tTRUE\t"))
        assertTrue(BrowserCookies.toNetscapeFile("a=1", "https://m.youtube.com/watch?v=1", 1)!!.contains("\n.youtube.com\tTRUE\t"))
        assertTrue(BrowserCookies.toNetscapeFile("a=1", "https://youtu.be/abc", 1)!!.contains("\n.youtube.com\tTRUE\t"))
    }

    @Test
    fun aCookieIsNeverWrittenForADomainOtherThanTheAddressOfTheRequest() {
        val out = BrowserCookies.toNetscapeFile("a=1", "https://user@cdn.example.com:8443/x?y=https://evil.test/", 1)!!
        assertTrue(out.contains("\n.example.com\tTRUE\t"))
        assertTrue(!out.contains("evil"))
    }

    @Test
    fun plainHttpIsNotMarkedSecureAndAnAddressWithoutANameStaysHostOnly() {
        assertTrue(BrowserCookies.toNetscapeFile("a=1", "http://example.com/", 1)!!.contains("\n.example.com\tTRUE\t/\tFALSE\t"))
        assertTrue(BrowserCookies.toNetscapeFile("a=1", "http://127.0.0.1:8080/a", 1)!!.contains("\n127.0.0.1\tFALSE\t/\tFALSE\t"))
    }

    @Test
    fun aCookieThatCouldAddALineOrAFieldIsSkipped() {
        val out = BrowserCookies.toNetscapeFile("good=1; bad=a\tb; evil=1\r\nx\ty\tz; =x; novalue; empty=; sp ace=1", "https://example.com/", 1)!!
        assertEquals(2, out.lines().count { it.isNotEmpty() })
        assertTrue(out.contains("\tgood\t1\n"))
    }

    @Test
    fun nothingUsableGivesNull() {
        assertNull(BrowserCookies.toNetscapeFile("", "https://example.com/", 1))
        assertNull(BrowserCookies.toNetscapeFile("novalue", "https://example.com/", 1))
        assertNull(BrowserCookies.toNetscapeFile("a=1", "not a url", 1))
        assertNull(BrowserCookies.toNetscapeFile("a=1", "https://[::1]/x", 1))
        assertNull(BrowserCookies.toNetscapeFile("a=" + "x".repeat(40_000), "https://example.com/", 1))
    }

    @Test
    fun theCookieHeaderIsFoundInAnyLetterCaseAndBlankMeansNone() {
        assertEquals("a=1", BrowserCookies.headerOf(mapOf("Referer" to "r", "cookie" to "a=1")))
        assertNull(BrowserCookies.headerOf(mapOf("Cookie" to "  ")))
        assertNull(BrowserCookies.headerOf(emptyMap()))
    }
}
