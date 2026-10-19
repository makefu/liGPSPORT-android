package de.syntaxfehler.ligpsport

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import de.syntaxfehler.ligpsport.route.Point
import de.syntaxfehler.ligpsport.ui.map.BottomEndFabs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI test for the bottom-right FAB stack on MapScreen.
 *
 * The menu FAB — the map's only way into the navigation drawer, since
 * there is no app bar to hang a hamburger off — is always visible; the
 * my-location FAB only renders when a current-location fix is known.
 * Click on the location FAB surfaces the current Point; the production
 * code feeds that into `mapView.controller.animateTo`, which we don't
 * try to test here.
 */
@RunWith(AndroidJUnit4::class)
class BottomEndFabsTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun my_location_fab_is_hidden_until_a_fix_arrives() {
        composeTestRule.setContent {
            BottomEndFabs(
                currentLocation = null,
                onMyLocation = { error("must not fire without a fix") },
                onOpenMenu = { /* not asserted in this test */ },
            )
        }
        composeTestRule.onNodeWithTag("menu_fab").assertIsDisplayed()
        composeTestRule.onNodeWithTag("my_location_fab").assertDoesNotExist()
    }

    @Test
    fun my_location_fab_renders_when_a_fix_is_present() {
        composeTestRule.setContent {
            BottomEndFabs(
                currentLocation = Point(52.52, 13.405), // Berlin
                onMyLocation = {},
                onOpenMenu = {},
            )
        }
        composeTestRule.onNodeWithTag("my_location_fab").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_fab").assertIsDisplayed()
    }

    @Test
    fun clicking_my_location_fab_forwards_current_fix() {
        val captured = mutableListOf<Point>()
        val fix = Point(48.7700, 9.1800) // Stuttgart
        composeTestRule.setContent {
            BottomEndFabs(
                currentLocation = fix,
                onMyLocation = { captured += it },
                onOpenMenu = {},
            )
        }
        composeTestRule.onNodeWithTag("my_location_fab").performClick()
        assertThat(captured).containsExactly(fix)
    }

    @Test
    fun clicking_menu_fab_invokes_menu_callback() {
        var openedMenu = 0
        composeTestRule.setContent {
            BottomEndFabs(
                currentLocation = null,
                onMyLocation = {},
                onOpenMenu = { openedMenu++ },
            )
        }
        composeTestRule.onNodeWithTag("menu_fab").performClick()
        assertThat(openedMenu).isEqualTo(1)
    }
}
