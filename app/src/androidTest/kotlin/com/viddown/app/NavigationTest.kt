package com.viddown.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.viddown.app.ui.UiTags
import com.viddown.core.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** UI tests of the Phase 2 navigation foundation. They need no native runtime. */
class NavigationTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val settings get() = (rule.activity.application as VidDownApplication).container.settings

    @After
    fun resetTheme() = runBlocking {
        settings.update { it.copy(themeMode = ThemeMode.SYSTEM) }
    }

    @Test
    fun startsOnHome() {
        rule.onNodeWithTag("screen_home").assertIsDisplayed()
    }

    @Test
    fun bottomNavigationSwitchesBetweenTheFourScreens() {
        for (route in listOf("browser", "downloads", "library", "home")) {
            rule.onNodeWithTag("nav_$route").performClick()
            rule.onNodeWithTag("screen_$route").assertIsDisplayed()
        }
    }

    @Test
    fun browserHomeButtonReturnsToTheHomeScreen() {
        rule.onNodeWithTag("nav_browser").performClick()
        rule.onNodeWithTag("screen_browser").assertIsDisplayed()
        rule.onNodeWithTag("browser_home").performClick()
        rule.onNodeWithTag("screen_home").assertIsDisplayed()
    }

    @Test
    fun bottomHomeTabLeavesTheBrowser() {
        rule.onNodeWithTag("nav_browser").performClick()
        rule.onNodeWithTag("screen_browser").assertIsDisplayed()
        rule.onNodeWithTag("nav_home").performClick()
        rule.onNodeWithTag("screen_home").assertIsDisplayed()
        rule.onNodeWithTag("screen_browser").assertDoesNotExist()
    }

    @Test
    fun addressBarShowsAClearButtonWhileEditingAndItEmptiesTheField() {
        rule.onNodeWithTag("nav_browser").performClick()
        rule.onNodeWithTag(UiTags.BROWSER_ADDRESS_CLEAR).assertDoesNotExist() // nothing typed yet
        rule.onNodeWithTag(UiTags.BROWSER_ADDRESS).performTextInput("example.com")
        rule.onNodeWithTag(UiTags.BROWSER_ADDRESS_CLEAR).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.BROWSER_ADDRESS_CLEAR).performClick()
        rule.onNodeWithTag(UiTags.BROWSER_ADDRESS_CLEAR).assertDoesNotExist() // the field is empty again
    }

    @Test
    fun settingsOpensFromTheTopBarAndBackReturnsHome() {
        rule.onNodeWithTag("action_settings").performClick()
        rule.onNodeWithTag("screen_settings").assertIsDisplayed()
        rule.onNodeWithTag("nav_home").assertDoesNotExist() // the bottom bar is hidden inside Settings
        rule.onNodeWithTag("action_back").performClick()
        rule.onNodeWithTag("screen_home").assertIsDisplayed()
    }

    @Test
    fun themeChoiceIsAppliedAndPersisted() {
        rule.onNodeWithTag("action_settings").performClick()
        rule.onNodeWithTag("theme_dark").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { runBlocking { settings.settings.first().themeMode } == ThemeMode.DARK }
        assertEquals(ThemeMode.DARK, runBlocking { settings.settings.first().themeMode })
    }
}
