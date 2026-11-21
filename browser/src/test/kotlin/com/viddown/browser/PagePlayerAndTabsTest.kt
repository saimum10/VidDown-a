package com.viddown.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PagePlayerTest {
    private fun raw(vararg rows: String) = "\"" + (listOf("v1") + rows).joinToString("\\n") + "\""

    @Test
    fun parsesElementsAndTreatsEveryFieldAsUntrusted() {
        val tab = "\\t"
        val report = PagePlayer.parse(
            raw(
                "v${tab}https%3A%2F%2Fx.test%2Fa.mp4${tab}https%3A%2F%2Fx.test%2Fp.jpg${tab}61${tab}1280${tab}720${tab}0",
                "a${tab}blob%3Ahttps%3A%2F%2Fx.test%2Fuuid${tab}${tab}${tab}${tab}${tab}1",
                "v${tab}javascript%3Aalert(1)${tab}${tab}99999999999${tab}0${tab}${tab}0",
            ),
        )
        assertNotNull(report)
        val e = report!!.elements
        assertEquals(3, e.size)
        assertEquals("https://x.test/a.mp4", e[0].httpSource)
        assertEquals(61L, e[0].durationSeconds)
        assertEquals(1280, e[0].width)
        assertTrue(e[1].isBlob)
        assertNull(e[1].httpSource)
        assertTrue(e[1].usesDrm)
        assertTrue(e[1].isAudio)
        assertNull(e[2].httpSource)
        assertNull(e[2].durationSeconds)
        assertNull(e[2].width)
    }

    @Test
    fun rejectsAnythingThatIsNotTheExpectedFormat() {
        assertNull(PagePlayer.parse(null))
        assertNull(PagePlayer.parse("null"))
        assertNull(PagePlayer.parse("\"v2\\nx\""))
        assertNull(PagePlayer.parse("{\"a\":1}"))
        assertEquals(0, PagePlayer.parse("\"v1\"")!!.elements.size)
        assertEquals(0, PagePlayer.parse(raw("v\\tonly\\tthree"))!!.elements.size)
    }

    @Test
    fun capsTheNumberOfElements() {
        val rows = (1..40).map { "v\\thttps%3A%2F%2Fx.test%2F$it.mp4\\t\\t\\t\\t\\t0" }.toTypedArray()
        assertEquals(PagePlayer.MAX_ELEMENTS, PagePlayer.parse(raw(*rows))!!.elements.size)
    }

    @Test
    fun jsonStringUnquoting() {
        assertEquals("a\nb\t\"c\"\\", PagePlayer.unquoteJsonString("\"a\\nb\\t\\\"c\\\"\\\\\""))
        assertEquals("<", PagePlayer.unquoteJsonString("\"\\u003C\""))
        assertNull(PagePlayer.unquoteJsonString("\"bad\\x\""))
        assertNull(PagePlayer.unquoteJsonString("\"dangling\\\""))
        assertNull(PagePlayer.unquoteJsonString("plain"))
    }

    @Test
    fun theScriptHasNoBridgeAndNoNetwork() {
        val s = PagePlayer.SCRIPT
        assertTrue(s.contains("querySelectorAll('video,audio')"))
        for (forbidden in listOf("fetch(", "XMLHttpRequest", "postMessage", "window.open", "location", "eval(")) assertFalse(forbidden, s.contains(forbidden))
    }
}

class TabManagerTest {
    private var n = 0
    private fun manager(max: Int = 3) = TabManager(max) { "t${++n}" }

    @Test
    fun ensureTabCreatesOneBlankTabOnce() {
        val m = manager()
        val id = m.ensureTab()
        assertEquals(id, m.ensureTab())
        assertEquals(1, m.state.value.tabs.size)
        assertNull(m.state.value.active!!.url)
    }

    @Test
    fun openActivatesAndInsertsAfterTheActiveTab() {
        val m = manager(5)
        val a = m.ensureTab()
        val b = (m.open("https://b.test") as OpenTabResult.Opened).id
        m.switchTo(a)
        val c = (m.open(null) as OpenTabResult.Opened).id
        assertEquals(listOf(a, c, b), m.state.value.tabs.map { it.id })
        assertEquals(c, m.state.value.activeId)
        val bg = (m.open("https://bg.test", activate = false) as OpenTabResult.Opened).id
        assertEquals(c, m.state.value.activeId)
        assertEquals(bg, m.state.value.tabs[2].id)
    }

    @Test
    fun theTabLimitIsEnforced() {
        val m = manager(2)
        m.ensureTab()
        assertTrue(m.open("https://a.test") is OpenTabResult.Opened)
        assertEquals(OpenTabResult.LimitReached(2), m.open("https://b.test"))
        assertEquals(2, m.state.value.tabs.size)
    }

    @Test
    fun closingSelectsTheRightNeighbourThenTheLeftAndNeverLeavesZeroTabs() {
        val m = manager(5)
        val a = m.ensureTab()
        val b = (m.open("https://b.test") as OpenTabResult.Opened).id
        val c = (m.open("https://c.test") as OpenTabResult.Opened).id
        m.switchTo(b)
        assertEquals(c, m.close(b))
        assertEquals(b.let { c }, m.state.value.activeId)
        assertEquals(a, m.close(c))
        val last = m.close(a)
        assertNotNull(last)
        assertEquals(1, m.state.value.tabs.size)
        assertNull(m.state.value.tabs.single().url)
        assertEquals(last, m.state.value.activeId)
    }

    @Test
    fun closingAnInactiveTabKeepsTheActiveOne() {
        val m = manager(5)
        val a = m.ensureTab()
        val b = (m.open("https://b.test") as OpenTabResult.Opened).id
        assertEquals(b, m.close(a))
        assertEquals(b, m.state.value.activeId)
        assertEquals(b, m.close("unknown"))
    }

    @Test
    fun pageEventsUpdateOnlyTheirTab() {
        val m = manager(5)
        val a = m.ensureTab()
        val b = (m.open("https://b.test") as OpenTabResult.Opened).id
        m.onPageStarted(a, "https://a.test")
        m.onProgress(a, 40)
        assertTrue(m.state.value.tabs.first { it.id == a }.isLoading)
        m.onPageFinished(a, "https://a.test/", "  A page ")
        val ta = m.state.value.tabs.first { it.id == a }
        assertEquals("A page", ta.title)
        assertFalse(ta.isLoading)
        assertEquals("b.test", m.state.value.tabs.first { it.id == b }.displayTitle)
        m.onTitle(a, "   ")
        assertNull(m.state.value.tabs.first { it.id == a }.title)
        assertEquals("a.test", m.state.value.tabs.first { it.id == a }.displayTitle)
    }

    @Test
    fun eventsForAClosedTabAreIgnored() {
        val m = manager(5)
        val a = m.ensureTab()
        m.open("https://b.test")
        m.close(a)
        val before = m.state.value
        m.onProgress(a, 50)
        m.onTitle(a, "late")
        assertEquals(before, m.state.value)
    }

    @Test
    fun snapshotRoundTripsAndDropsLoadingState() {
        val m = manager(5)
        val a = m.ensureTab()
        m.navigate(a, "https://a.test/x?y=1&z=a b")
        m.onPageFinished(a, "https://a.test/x?y=1&z=a b", "Tab\twith\nodd chars ä")
        val b = (m.open("https://b.test") as OpenTabResult.Opened).id
        val text = TabsSnapshotCodec.encode(m.snapshot())
        val back = TabsSnapshotCodec.decode(text)!!
        assertEquals(m.snapshot(), back)
        val fresh = manager(5)
        fresh.restore(back)
        assertEquals(b, fresh.state.value.activeId)
        assertFalse(fresh.state.value.tabs.any { it.isLoading })
        assertEquals("Tab\twith\nodd chars ä", fresh.state.value.tabs.first { it.id == a }.title)
    }

    @Test
    fun decodeIgnoresForeignOrDamagedFilesAndUnsafeAddresses() {
        assertNull(TabsSnapshotCodec.decode(null))
        assertNull(TabsSnapshotCodec.decode("hello"))
        val text = "viddown-tabs-v1\nactive\tx\ntab\tx\tjavascript%3Aalert(1)\t\ntab\t\t\t\ngarbage\ntab\ty\t%ZZ\t\ntab\tz\thttps%3A%2F%2Fok.test\tOK\n"
        val s = TabsSnapshotCodec.decode(text)!!
        assertEquals(listOf("x", "z"), s.tabs.map { it.id })
        assertNull(s.tabs.first().url)
        assertEquals("https://ok.test", s.tabs.last().url)
    }

    @Test
    fun restoreRespectsTheLimitAndIgnoresEmptyOrUnknownActive() {
        val m = manager(2)
        m.restore(TabsSnapshot(emptyList(), null))
        assertTrue(m.state.value.tabs.isEmpty())
        m.restore(TabsSnapshot(listOf(SavedTab("a", null, null), SavedTab("b", null, null), SavedTab("c", null, null), SavedTab("a", null, null)), "zzz"))
        assertEquals(listOf("a", "b"), m.state.value.tabs.map { it.id })
        assertEquals("a", m.state.value.activeId)
    }
}

class ExternalUrlTest {
    @Test
    fun findsTheFirstWebAddressInSharedText() {
        assertEquals("https://example.com/v?id=1", ExternalUrl.fromSharedText("Look at this https://example.com/v?id=1, it is great"))
        assertEquals("http://example.com/a", ExternalUrl.fromSharedText("(http://example.com/a)"))
        assertNull(ExternalUrl.fromSharedText("no link here"))
        assertNull(ExternalUrl.fromSharedText(null))
        assertNull(ExternalUrl.fromSharedText("javascript:alert(1)"))
    }

    @Test
    fun viewDataMustBeExactlyOneWebAddress() {
        assertEquals("https://example.com/a", ExternalUrl.fromViewData(" https://example.com/a "))
        assertNull(ExternalUrl.fromViewData("https://example.com/a b"))
        assertNull(ExternalUrl.fromViewData("file:///sdcard/a.mp4"))
        assertNull(ExternalUrl.fromViewData("content://x/1"))
        assertNull(ExternalUrl.fromViewData(null))
    }
}
