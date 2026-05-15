package de.syntaxfehler.ligpsport

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.syntaxfehler.ligpsport.ui.settings.DeviceActivitiesScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI smoke for the activities sub-screen. With no paired device on
 * the emulator the screen short-circuits to the "Pair a device first"
 * card — but the chrome (top app bar, refresh button, lazy-list
 * surface) still renders, which is what these tests pin.
 */
@RunWith(AndroidJUnit4::class)
class DeviceActivitiesScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun renders_top_bar_refresh_button_and_lazy_list_root() {
        composeTestRule.setContent {
            DeviceActivitiesScreen(onBack = {})
        }
        composeTestRule.onNodeWithText("Activities on device").assertIsDisplayed()
        composeTestRule.onNodeWithTag("refresh_activities").assertIsDisplayed()
        composeTestRule.onNodeWithTag("activities_list").assertIsDisplayed()
    }

    @Test
    fun back_callback_fires_on_navigation_icon_click() {
        var backs = 0
        composeTestRule.setContent {
            DeviceActivitiesScreen(onBack = { backs++ })
        }
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(backs == 1) { "expected exactly one back invocation, got $backs" }
    }
}
