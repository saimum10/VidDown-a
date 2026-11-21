package com.viddown.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.viddown.app.ui.UiTags
import com.viddown.core.model.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Phase 4 on a device: the link dialog refuses what is not a link, and the download switches really change the settings. */
class Phase4ScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val container get() = (rule.activity.application as VidDownApplication).container

    @Before
    fun cleanSlate() = runBlocking {
        container.downloads.downloads.first().forEach { container.downloads.deleteRecord(it.id) }
        container.settings.update { AppSettings() }
        Unit
    }

    @After
    fun cleanUp() = runBlocking {
        container.downloads.downloads.first().forEach { container.downloads.deleteRecord(it.id) }
        container.settings.update { AppSettings() }
        Unit
    }

    private fun waitForTag(tag: String) = rule.waitUntil(timeoutMillis = 5_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun theLinkDialogRefusesTextThatIsNotALinkAndQueuesNothing() {
        rule.onNodeWithTag(UiTags.HOME_LINK_BUTTON).performClick()
        waitForTag(UiTags.LINK_FIELD)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput("funny cats")
        rule.onNodeWithTag(UiTags.LINK_CHECK).performClick()
        waitForTag(UiTags.LINK_ERROR)
        rule.onNodeWithTag(UiTags.LINK_ERROR).assertIsDisplayed()
        assertTrue(runBlocking { container.downloads.downloads.first() }.isEmpty())
    }

    @Test
    fun theLinkDialogRefusesUnsafeSchemes() {
        rule.onNodeWithTag(UiTags.HOME_LINK_BUTTON).performClick()
        waitForTag(UiTags.LINK_FIELD)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput("file:///etc/passwd")
        rule.onNodeWithTag(UiTags.LINK_CHECK).performClick()
        waitForTag(UiTags.LINK_ERROR)
        assertTrue(runBlocking { container.downloads.downloads.first() }.isEmpty())
    }

    @Test
    fun downloadSwitchesAreSavedInTheSettings() {
        rule.onNodeWithTag("action_settings").performClick()
        waitForTag(UiTags.SETTINGS_WIFI_ONLY)
        assertFalse(runBlocking { container.settings.settings.first().wifiOnly })
        rule.onNodeWithTag(UiTags.SETTINGS_WIFI_ONLY).performClick()
        rule.waitUntil(timeoutMillis = 5_000) { runBlocking { container.settings.settings.first().wifiOnly } }

        assertTrue(runBlocking { container.settings.settings.first().askBeforeDownloading })
        rule.onNodeWithTag(UiTags.SETTINGS_ASK_BEFORE).performClick()
        rule.waitUntil(timeoutMillis = 5_000) { !runBlocking { container.settings.settings.first().askBeforeDownloading } }
    }

    @Test
    fun theLimitOfSimultaneousDownloadsIsSaved() {
        rule.onNodeWithTag("action_settings").performClick()
        waitForTag(UiTags.SETTINGS_MAX_DOWNLOADS_SLIDER)
        rule.onNodeWithTag(UiTags.SETTINGS_MAX_DOWNLOADS_SLIDER).performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
        rule.waitUntil(timeoutMillis = 5_000) { runBlocking { container.settings.settings.first().maxSimultaneousDownloads } == 8 }
        assertEquals(8, runBlocking { container.settings.settings.first().maxSimultaneousDownloads })
    }
}
