package com.viddown.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.viddown.app.ui.UiTags
import com.viddown.core.model.AppSettings
import com.viddown.engine.api.EngineId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Phase 5 on a device: tabs, the real WebView loading a page from a local server, media detection from that page, the quality
 * sheet, bookmarks and history. The pages and the media come from 127.0.0.1 (debug builds allow cleartext to it only).
 */
class Phase5BrowserTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val container get() = (rule.activity.application as VidDownApplication).container
    private var server: TestServer? = null

    @Before
    fun cleanSlate() = runBlocking {
        container.downloads.downloads.first().forEach { container.downloads.deleteRecord(it.id) }
        container.history.clear()
        container.bookmarks.clear()
        container.settings.update { AppSettings() }
        container.browser.closeAllTabs()
        Unit
    }

    @After
    fun cleanUp() = runBlocking {
        server?.close()
        container.downloads.downloads.first().forEach { container.downloads.deleteRecord(it.id) }
        container.history.clear()
        container.bookmarks.clear()
        container.settings.update { AppSettings() }
        container.browser.closeAllTabs()
        Unit
    }

    private fun waitForTag(tag: String, timeoutMillis: Long = 5_000) = rule.waitUntil(timeoutMillis) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun openBrowser() {
        rule.onNodeWithTag("nav_browser").performClick()
        waitForTag("screen_browser")
    }

    /** A page with a <video> and a link, plus a 200 KB MP4-typed file. Only the requests matter, not whether it plays. */
    private fun startServer(): String {
        val s = TestServer(
            mapOf(
                "/page.html" to ("text/html; charset=utf-8" to "<html><head><title>Local test page</title></head><body><video src=\"/clip.mp4\" preload=\"auto\" controls></video></body></html>".toByteArray()),
                "/clip.mp4" to ("video/mp4" to ByteArray(200_000) { (it % 251).toByte() }),
                "/plain.html" to ("text/html" to "<html><title>Plain</title><body>No media here</body></html>".toByteArray()),
            ),
        )
        server = s
        return "http://127.0.0.1:${s.port}"
    }

    @Test
    fun aNewBrowserShowsABlankTabWithGuidanceAndNoWebView() {
        openBrowser()
        waitForTag(UiTags.BROWSER_BLANK)
        rule.onNodeWithTag(UiTags.BROWSER_BLANK).assertIsDisplayed()
        rule.onAllNodesWithTag(UiTags.BROWSER_WEBVIEW).assertCountEquals(0)
        assertEquals(1, container.browser.tabs.state.value.tabs.size)
    }

    @Test
    fun tabsCanBeOpenedSwitchedAndClosedAndTheLastTabLeavesABlankOne() {
        openBrowser()
        rule.onNodeWithTag(UiTags.BROWSER_MENU).performClick()
        rule.onNodeWithTag(UiTags.BROWSER_MENU_NEW_TAB).performClick()
        rule.waitUntil(5_000) { container.browser.tabs.state.value.tabs.size == 2 }
        rule.onNodeWithTag(UiTags.BROWSER_TABS).performClick()
        waitForTag(UiTags.TABS_SWITCHER)
        val ids = container.browser.tabs.state.value.tabs.map { it.id }
        ids.forEach { rule.onNodeWithTag(UiTags.tabCard(it)).assertIsDisplayed() }
        rule.onNodeWithTag(UiTags.tabClose(ids[0])).performClick()
        rule.waitUntil(5_000) { container.browser.tabs.state.value.tabs.size == 1 }
        rule.onNodeWithTag(UiTags.tabClose(ids[1])).performClick()
        rule.waitUntil(5_000) { container.browser.tabs.state.value.tabs.single().id != ids[1] }
        assertNotNull(container.browser.tabs.state.value.active)
        assertEquals(null, container.browser.tabs.state.value.active!!.url)
    }

    @Test
    fun theTabLimitIsEnforcedAndExplained() {
        openBrowser()
        repeat(container.browser.tabs.maxTabs) { container.browser.tabs.open(null) }
        assertEquals(container.browser.tabs.maxTabs, container.browser.tabs.state.value.tabs.size)
        rule.onNodeWithTag(UiTags.BROWSER_MENU).performClick()
        rule.onNodeWithTag(UiTags.BROWSER_MENU_NEW_TAB).performClick()
        rule.waitForIdle()
        assertEquals(container.browser.tabs.maxTabs, container.browser.tabs.state.value.tabs.size)
    }

    @Test
    fun aPageLoadsInARealWebViewIsRecordedInHistoryAndCanBeBookmarked() {
        val base = startServer()
        openBrowser()
        rule.runOnUiThread { container.browser.openInActiveTab("$base/plain.html") }
        waitForTag(UiTags.BROWSER_WEBVIEW, 15_000)
        rule.waitUntil(20_000) { runBlocking { container.history.recent(5).first() }.any { it.url.startsWith(base) } }
        rule.waitUntil(10_000) { container.browser.tabs.state.value.active?.title == "Plain" }
        rule.onNodeWithTag(UiTags.BROWSER_MENU).performClick()
        rule.onNodeWithTag(UiTags.BROWSER_BOOKMARK).performClick()
        rule.waitUntil(5_000) { runBlocking { container.bookmarks.bookmarks.first() }.isNotEmpty() }
        assertTrue(runBlocking { container.bookmarks.bookmarks.first() }.single().url.startsWith(base))
        rule.onNodeWithTag(UiTags.BROWSER_MENU).performClick()
        rule.onNodeWithTag(UiTags.BROWSER_MENU_PAGES).performClick()
        waitForTag(UiTags.bookmarkItem(0))
        rule.onNodeWithTag(UiTags.bookmarkItem(0)).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.bookmarkRemove(0)).performClick()
        rule.waitUntil(5_000) { runBlocking { container.bookmarks.bookmarks.first() }.isEmpty() }
    }

    @Test
    fun mediaOnAPageIsDetectedFromRealRequestsAndDownloadedThroughTheSheet() {
        val base = startServer()
        openBrowser()
        rule.runOnUiThread { container.browser.openInActiveTab("$base/page.html") }
        waitForTag(UiTags.BROWSER_MEDIA_BADGE, 30_000)
        val tabId = container.browser.tabs.state.value.activeId!!
        val found = container.browser.detections.current(tabId).items
        assertEquals(1, found.size)
        assertEquals("$base/clip.mp4", found.single().url)
        assertTrue("size comes from the server, not a guess", found.single().sizeBytes == 200_000L)
        rule.onNodeWithTag(UiTags.BROWSER_MEDIA).performClick()
        waitForTag(UiTags.QUALITY_SHEET)
        waitForTag(UiTags.QUALITY_CONFIRM, 15_000)
        rule.onNodeWithTag(UiTags.QUALITY_CONFIRM).performClick()
        waitForTag(UiTags.QUALITY_QUEUED, 10_000)
        val tasks = runBlocking { container.downloads.downloads.first() }
        assertEquals(1, tasks.size)
        assertEquals(EngineId.NEWPIPE, tasks.single().engineId)
    }

    @Test
    fun aPageWithoutMediaShowsNoDownloadButton() {
        val base = startServer()
        openBrowser()
        rule.runOnUiThread { container.browser.openInActiveTab("$base/plain.html") }
        waitForTag(UiTags.BROWSER_WEBVIEW, 15_000)
        rule.waitForIdle()
        rule.onAllNodesWithTag(UiTags.BROWSER_MEDIA_BADGE).assertCountEquals(0)
        rule.onAllNodesWithTag(UiTags.BROWSER_MEDIA).assertCountEquals(0)
    }

    @Test
    fun javascriptAndSearchEngineControlsReallyChangeTheSettings() {
        rule.onNodeWithTag("action_settings").performClick()
        waitForTag(UiTags.SETTINGS_JAVASCRIPT)
        rule.onNodeWithTag(UiTags.SETTINGS_JAVASCRIPT).performScrollTo().performClick()
        rule.waitUntil(5_000) { !runBlocking { container.settings.settings.first() }.javascriptEnabled }
        rule.onNodeWithTag(UiTags.searchEngine("google")).performScrollTo().performClick()
        rule.waitUntil(5_000) { runBlocking { container.settings.settings.first() }.searchEngine.storageKey == "google" }
    }
}
