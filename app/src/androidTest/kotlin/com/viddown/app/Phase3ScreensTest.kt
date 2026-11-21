package com.viddown.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.viddown.app.ui.UiTags
import com.viddown.core.model.AppSettings
import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineMode
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.StreamKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Phase 3 screens on a device: honest empty states, the address field, the engine list, history, About. No native runtime needed. */
class Phase3ScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val container get() = (rule.activity.application as VidDownApplication).container

    @Before
    fun cleanSlate() = runBlocking {
        container.downloads.downloads.first().forEach { container.downloads.deleteRecord(it.id) }
        container.history.clear()
        container.settings.update { AppSettings() }
        container.browser.closeAllTabs()
        Unit
    }

    @After
    fun cleanUp() = runBlocking {
        container.downloads.downloads.first().forEach { container.downloads.deleteRecord(it.id) }
        container.history.clear()
        container.settings.update { AppSettings() }
        Unit
    }

    private fun waitForTag(tag: String) = rule.waitUntil(timeoutMillis = 5_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForNoTag(tag: String) = rule.waitUntil(timeoutMillis = 5_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    }

    private fun task(id: String, state: DownloadState, title: String) = DownloadTask(
        id = id, engineId = EngineId.NEWPIPE, request = MediaRequest("https://x.test/$id.mp4"),
        format = FormatOption("f", StreamKind.VIDEO_AND_AUDIO, container = "mp4", height = 720),
        title = title, durationSeconds = 125, state = state, bytesDownloaded = 2_000_000, totalBytes = 2_000_000,
        createdAtMillis = 1_000,
    )

    @Test
    fun homeShowsHonestEmptyStates() {
        waitForTag(UiTags.HOME_RECENT_PAGES_EMPTY)
        rule.onNodeWithTag(UiTags.HOME_ACTIVE_NONE).assertIsDisplayed()
    }

    @Test
    fun addressFieldRefusesUnsafeSchemesAndStaysOnHome() {
        rule.onNodeWithTag(UiTags.HOME_URL_FIELD).performTextInput("javascript:alert(1)")
        rule.onNodeWithTag(UiTags.HOME_GO).performClick()
        waitForTag(UiTags.HOME_ERROR)
        rule.onNodeWithTag(UiTags.HOME_ERROR).assertIsDisplayed()
        rule.onNodeWithTag("screen_home").assertIsDisplayed()
    }

    @Test
    fun goOpensTheBrowserContainerWithTheNormalisedAddress() {
        rule.onNodeWithTag(UiTags.HOME_URL_FIELD).performTextInput("example.com")
        rule.onNodeWithTag(UiTags.HOME_GO).performClick()
        waitForTag("screen_browser")
        rule.onNodeWithTag(UiTags.BROWSER_ADDRESS).assertTextContains("https://example.com")
        // The address is accepted and the browser starts loading it in a real tab (no placeholder notice any more).
        rule.onNodeWithTag(UiTags.BROWSER_WEBVIEW).assertIsDisplayed()
    }

    @Test
    fun downloadsListShowsARecordWithItsEngineAndDeletesOnlyTheRecord() {
        rule.onNodeWithTag("nav_downloads").performClick()
        waitForTag(UiTags.DOWNLOADS_EMPTY)
        runBlocking { container.downloads.save(task("d1", DownloadState.FAILED, "Broken clip")) }
        waitForTag(UiTags.download("d1"))
        rule.onNodeWithTag(UiTags.download("d1")).assertIsDisplayed()
        rule.onAllNodesWithText("Failed · NewPipe · 720p · MP4").assertCountEquals(1)
        rule.onNodeWithTag(UiTags.downloadDelete("d1")).performClick()
        waitForNoTag(UiTags.download("d1"))
        assertNull(runBlocking { container.downloads.get("d1") })
    }

    @Test
    fun libraryShowsCompletedDownloadsSearchesAndRemovesAfterConfirmation() {
        runBlocking {
            container.downloads.save(task("l1", DownloadState.COMPLETED, "Alpha clip"))
            container.downloads.save(task("l2", DownloadState.COMPLETED, "Beta clip").copy(createdAtMillis = 2_000))
            container.downloads.save(task("l3", DownloadState.FAILED, "Not in library"))
        }
        rule.onNodeWithTag("nav_library").performClick()
        waitForTag(UiTags.libraryItem("l1"))
        rule.onNodeWithTag(UiTags.libraryItem("l2")).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag(UiTags.libraryItem("l3")).fetchSemanticsNodes().isEmpty())

        rule.onNodeWithTag(UiTags.LIBRARY_SEARCH).performTextInput("alpha")
        waitForNoTag(UiTags.libraryItem("l2"))
        rule.onNodeWithTag(UiTags.libraryItem("l1")).assertIsDisplayed()

        rule.onNodeWithTag(UiTags.libraryRemove("l1")).performClick()
        rule.onNodeWithTag(UiTags.LIBRARY_CONFIRM_REMOVE).performClick()
        waitForTag(UiTags.LIBRARY_NO_MATCH)
        assertNull(runBlocking { container.downloads.get("l1") })
        assertEquals("l2", runBlocking { container.downloads.get("l2") }!!.id)
    }

    @Test
    fun engineDialogListsTheEnginesAndOnlyOffersYtDlpWhenThisBuildPackagesItsRuntime() {
        rule.onNodeWithTag("action_settings").performClick()
        waitForTag(UiTags.SETTINGS_ENGINE_ROW)
        rule.onNodeWithTag(UiTags.SETTINGS_ENGINE_ROW).performClick()
        waitForTag(UiTags.engineOption(EngineMode.NEWPIPE.storageKey))
        for (mode in EngineMode.entries) rule.onNodeWithTag(UiTags.engineOption(mode.storageKey)).assertIsDisplayed()
        // Auto (YouTube -> NewPipe, other links -> yt-dlp) is the default and can always be chosen while NewPipe is integrated
        rule.onNodeWithTag(UiTags.engineOption(EngineMode.AUTO.storageKey)).assertIsEnabled()
        // NewPipe is integrated
        rule.onNodeWithTag(UiTags.engineOption(EngineMode.NEWPIPE.storageKey)).assertIsEnabled()
        if (BuildConfig.NATIVE_ABIS.isBlank()) {
            // no native runtime in this APK: yt-dlp is said to be unavailable, and it cannot be selected
            rule.onAllNodesWithText("Not available in this build").assertCountEquals(1)
            rule.onNodeWithTag(UiTags.engineOption("ytdlp")).assertIsNotEnabled()
            rule.onNodeWithTag(UiTags.engineOption("ytdlp")).performClick()
            // nothing was switched behind the user's back: Auto stays the saved default
            assertEquals(EngineMode.AUTO, runBlocking { container.settings.settings.first().engineMode })
        } else if (Abi.select() != null) {
            // the runtime for this device is packaged: yt-dlp can be chosen, and nothing else is "not available"
            rule.onAllNodesWithText("Not available in this build").assertCountEquals(0)
            rule.onNodeWithTag(UiTags.engineOption("ytdlp")).assertIsEnabled()
            rule.onAllNodesWithTag(UiTags.ENGINE_BANNER).assertCountEquals(0)
        }
    }

    @Test
    fun theEngineChosenInTheDialogIsSaved() {
        rule.onNodeWithTag("action_settings").performClick()
        waitForTag(UiTags.SETTINGS_ENGINE_ROW)
        rule.onNodeWithTag(UiTags.SETTINGS_ENGINE_ROW).performClick()
        waitForTag(UiTags.engineOption(EngineMode.NEWPIPE.storageKey))
        rule.onNodeWithTag(UiTags.engineOption(EngineMode.NEWPIPE.storageKey)).performClick()
        rule.waitUntil(timeoutMillis = 5_000) { runBlocking { container.settings.settings.first().engineMode } == EngineMode.NEWPIPE }
        waitForNoTag(UiTags.engineOption(EngineMode.NEWPIPE.storageKey)) // the dialog closed
    }

    @Test
    fun clearingBrowsingHistoryWorksAndLeavesDownloadsAlone() {
        runBlocking {
            container.history.record("https://a.test/", "A")
            container.downloads.save(task("h1", DownloadState.COMPLETED, "Keep me"))
        }
        rule.onNodeWithTag("action_settings").performClick()
        rule.onNodeWithTag(UiTags.SETTINGS_CLEAR_HISTORY).performClick()
        rule.onNodeWithTag(UiTags.SETTINGS_CONFIRM_CLEAR).performClick()
        waitForTag(UiTags.SETTINGS_MESSAGE)
        assertTrue(runBlocking { container.history.recent(10).first() }.isEmpty())
        assertEquals("h1", runBlocking { container.downloads.get("h1") }!!.id)
    }

    @Test
    fun aboutShowsTheVersionAndTheLicensesLoad() {
        rule.onNodeWithTag("action_settings").performClick()
        rule.onNodeWithTag(UiTags.SETTINGS_ABOUT).performClick()
        waitForTag(UiTags.ABOUT_VERSION)
        rule.onNodeWithTag(UiTags.ABOUT_VERSION).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.ABOUT_LICENSES).performClick()
        waitForTag(UiTags.LICENSES_TEXT)
        rule.onNodeWithTag(UiTags.LICENSES_TEXT).assertTextContains("Third-party notices", substring = true)
    }
}
