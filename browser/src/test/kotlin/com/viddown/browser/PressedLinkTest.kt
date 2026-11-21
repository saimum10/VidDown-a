package com.viddown.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PressedLinkTest {
    private fun answer(a: String = "", m: String = "", f: String = ""): String {
        fun e(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val raw = "p1\t${e(a)}\t${e(m)}\t${e(f)}"
        return "\"" + raw.replace("\t", "\\t") + "\""
    }

    @Test
    fun theLinkWinsOverTheVideoSourceWhichWinsOverTheEmbeddedPlayer() {
        val all = PressedLink.parse(answer("https://site.test/watch?v=1", "https://cdn.test/a.mp4", "https://site.test/embed/1"))
        assertEquals("https://site.test/watch?v=1", PressedLink.choose(all))
        assertEquals("https://cdn.test/a.mp4", PressedLink.choose(PressedLink.parse(answer(m = "https://cdn.test/a.mp4", f = "https://site.test/embed/1"))))
        assertEquals("https://site.test/embed/1", PressedLink.choose(PressedLink.parse(answer(f = "https://site.test/embed/1"))))
    }

    @Test
    fun theAddressOfThePageBeingShownIsNeverInvented() {
        assertNull(PressedLink.choose(PressedLink.parse(answer())))
        assertNull(PressedLink.choose(null))
    }

    @Test
    fun onlyAddressesTheBrowserMayLoadAreOffered() {
        for (bad in listOf("blob:https://site.test/1234", "javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "intent://x#Intent;end", "ftp://x.test/a")) {
            assertNull(bad, PressedLink.choose(PressedLink.parse(answer(a = bad))))
        }
        // a blob: video does not hide the real link behind it, and does not replace it
        assertEquals("https://site.test/v", PressedLink.choose(PressedLink.parse(answer(a = "https://site.test/v", m = "blob:https://site.test/1"))))
        // an unusable link falls through to the next candidate
        assertEquals("https://cdn.test/a.mp4", PressedLink.choose(PressedLink.parse(answer(a = "javascript:void(0)", m = "https://cdn.test/a.mp4"))))
    }

    @Test
    fun theAndroidHitTestAddressIsTheLastResort() {
        assertEquals("https://site.test/x", PressedLink.choose(null, "https://site.test/x"))
        assertEquals("https://site.test/a", PressedLink.choose(PressedLink.parse(answer(a = "https://site.test/a")), "https://site.test/x"))
        assertNull(PressedLink.choose(null, "javascript:x"))
    }

    @Test
    fun answersThatAreNotTheExpectedFormatAreIgnored() {
        assertNull(PressedLink.parse(null))
        assertNull(PressedLink.parse("null"))
        assertNull(PressedLink.parse("\"p2\\ta\\tb\\tc\""))
        assertNull(PressedLink.parse("\"p1\\ta\\tb\""))
        assertNull(PressedLink.parse("\"p1\\ta\\tb\\tc\\td\""))
        val tooLong = "https://site.test/" + "a".repeat(PressedLink.MAX_FIELD)
        assertNull(PressedLink.choose(PressedLink.parse(answer(a = tooLong))))
    }

    @Test
    fun theScriptIsFixedAndOnlyCarriesTwoNumbers() {
        val s = PressedLink.script(12.345, 600.0)
        assertTrue(s.contains("12.35") || s.contains("12.34"))
        assertTrue(s.contains("600.00"))
        // a hostile or broken number can never put text into the script
        val odd = PressedLink.script(Double.NaN, Double.POSITIVE_INFINITY)
        assertTrue(odd.contains("var cx=(vv?vv.offsetLeft:0)+0.00/s;"))
        assertTrue(!odd.contains("NaN") && !odd.contains("Infinity"))
    }
}
