package com.viddown.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.viddown.core.model.ThemeMode
import com.viddown.core.ui.VidDownIcons
import com.viddown.core.ui.components.EmptyState
import com.viddown.core.ui.components.ErrorState
import com.viddown.core.ui.components.LoadingState
import com.viddown.core.ui.theme.VidDownTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The shared empty / loading / error components, in both themes. */
class StateComponentsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun emptyStateShowsItsTitleAndMessage() {
        rule.setContent { VidDownTheme(ThemeMode.LIGHT) { EmptyState(VidDownIcons.Downloads, "Nothing here", "Do something first") } }
        rule.onNodeWithText("Nothing here").assertIsDisplayed()
        rule.onNodeWithText("Do something first").assertIsDisplayed()
    }

    @Test
    fun errorStateShowsDetailAndItsActionWorks() {
        var clicks = 0
        rule.setContent {
            VidDownTheme(ThemeMode.DARK) {
                ErrorState("Could not load", "Please retry", technicalDetail = "database is locked", actionLabel = "Retry", onAction = { clicks++ })
            }
        }
        rule.onNodeWithText("Could not load").assertIsDisplayed()
        rule.onNodeWithText("database is locked").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun loadingStateShowsItsLabel() {
        rule.setContent { VidDownTheme(ThemeMode.SYSTEM) { LoadingState(label = "Loading things") } }
        rule.onNodeWithText("Loading things").assertIsDisplayed()
    }
}
