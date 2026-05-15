package de.syntaxfehler.ligpsport

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.syntaxfehler.ligpsport.ui.settings.DeviceRoutesScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mirror of [DeviceActivitiesScreenTest] for the routes sub-screen.
 * Same chrome assertions; the data-bearing case is covered by the
 * existing route round-trip + adb e2e suite.
 */
@RunWith(AndroidJUnit4::class)
class DeviceRoutesScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun renders_top_bar_refresh_button_and_lazy_list_root() {
        composeTestRule.setContent {
            DeviceRoutesScreen(onBack = {})
        }
        composeTestRule.onNodeWithText("Routes on device").assertIsDisplayed()
        composeTestRule.onNodeWithTag("refresh_routes").assertIsDisplayed()
        composeTestRule.onNodeWithTag("routes_list").assertIsDisplayed()
    }

    @Test
    fun back_callback_fires_on_navigation_icon_click() {
        var backs = 0
        composeTestRule.setContent {
            DeviceRoutesScreen(onBack = { backs++ })
        }
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(backs == 1) { "expected exactly one back invocation, got $backs" }
    }
}
