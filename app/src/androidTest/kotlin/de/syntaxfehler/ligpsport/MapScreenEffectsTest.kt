package de.syntaxfehler.ligpsport

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import de.syntaxfehler.ligpsport.route.Point
import de.syntaxfehler.ligpsport.ui.map.AutoPlanEffect
import de.syntaxfehler.ligpsport.ui.map.Destination
import de.syntaxfehler.ligpsport.ui.map.RouteEditUploadReset
import de.syntaxfehler.ligpsport.ui.map.UploadButtonState
import de.syntaxfehler.ligpsport.ui.map.Waypoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for two MapScreen bugs:
 *
 *  1. **Plan ↔ Upload loop on GPS drift.** The location overlay
 *     polls every 2 s and pushes a fresh [Point] each time, even
 *     when the device hasn't moved. The auto-plan effect used to
 *     key off `currentLocation` directly, so every drift cancelled
 *     the in-flight plan and started a new one — the status pill
 *     flickered between "Planning…" and "Route ready" forever and
 *     the user could never tap Upload. Fix: key off a derived
 *     `hasInitialFix` boolean (null → non-null), not the lat/lon.
 *
 *  2. **Upload button stays on "Uploaded ✓" after route edit.**
 *     Dragging an intermediate / start marker after a successful
 *     upload changed the planned GPX but the button still read
 *     "Uploaded ✓" — the user couldn't tell the new route hadn't
 *     been sent. Fix: a dedicated reset effect that fires on any
 *     route-input change and forces Success/Failed → Idle (without
 *     clobbering an in-flight Uploading state).
 */
@RunWith(AndroidJUnit4::class)
class MapScreenEffectsTest {

    @get:Rule val composeTestRule = createComposeRule()

    // ------- AutoPlanEffect ----------------------------------------

    @Test
    fun auto_plan_fires_exactly_once_for_a_burst_of_gps_drift() {
        val destination = mutableStateOf<Destination?>(Destination("Test", 48.8339, 9.2293))
        val currentLocation = mutableStateOf<Point?>(null)
        var planCalls = 0

        composeTestRule.setContent {
            AutoPlanEffect(
                destination = destination.value,
                intermediates = emptyList(),
                startOverride = null,
                currentLocation = currentLocation.value,
                onPlan = { _, _, _ -> planCalls++ },
            )
        }

        // No GPS yet → no plan.
        composeTestRule.waitForIdle()
        assertThat(planCalls).isEqualTo(0)

        // First fix triggers exactly one plan.
        composeTestRule.runOnIdle { currentLocation.value = Point(48.7700, 9.1800) }
        composeTestRule.waitForIdle()
        assertThat(planCalls).isEqualTo(1)

        // A burst of tiny drift updates ("stationary" GPS jitter) must
        // NOT re-fire the planner. This is the regression.
        composeTestRule.runOnIdle { currentLocation.value = Point(48.770001, 9.180001) }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { currentLocation.value = Point(48.770002, 9.179999) }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { currentLocation.value = Point(48.770003, 9.180004) }
        composeTestRule.waitForIdle()

        assertThat(planCalls).isEqualTo(1)
    }

    @Test
    fun auto_plan_fires_when_destination_changes_with_a_fix_present() {
        val destination = mutableStateOf<Destination?>(null)
        var planCalls = 0

        composeTestRule.setContent {
            AutoPlanEffect(
                destination = destination.value,
                intermediates = emptyList(),
                startOverride = null,
                currentLocation = Point(48.77, 9.18),
                onPlan = { _, _, _ -> planCalls++ },
            )
        }

        composeTestRule.waitForIdle()
        assertThat(planCalls).isEqualTo(0)

        composeTestRule.runOnIdle { destination.value = Destination("A", 48.83, 9.22) }
        composeTestRule.waitForIdle()
        assertThat(planCalls).isEqualTo(1)

        composeTestRule.runOnIdle { destination.value = Destination("B", 48.84, 9.23) }
        composeTestRule.waitForIdle()
        assertThat(planCalls).isEqualTo(2)
    }

    @Test
    fun auto_plan_fires_when_intermediates_change() {
        val intermediates = mutableStateOf<List<Waypoint>>(emptyList())
        var planCalls = 0
        var lastViaCount = -1

        composeTestRule.setContent {
            AutoPlanEffect(
                destination = Destination("D", 48.83, 9.22),
                intermediates = intermediates.value,
                startOverride = null,
                currentLocation = Point(48.77, 9.18),
                onPlan = { _, _, vias ->
                    planCalls++
                    lastViaCount = vias.size
                },
            )
        }

        composeTestRule.waitForIdle()
        // Initial composition fires once (fix + destination both present).
        assertThat(planCalls).isEqualTo(1)
        assertThat(lastViaCount).isEqualTo(0)

        composeTestRule.runOnIdle {
            intermediates.value = listOf(Waypoint(1L, 48.80, 9.20))
        }
        composeTestRule.waitForIdle()
        assertThat(planCalls).isEqualTo(2)
        assertThat(lastViaCount).isEqualTo(1)
    }

    @Test
    fun auto_plan_fires_when_start_override_changes() {
        val startOverride = mutableStateOf<Point?>(null)
        var planCalls = 0
        var lastStart: Point? = null

        composeTestRule.setContent {
            AutoPlanEffect(
                destination = Destination("D", 48.83, 9.22),
                intermediates = emptyList(),
                startOverride = startOverride.value,
                currentLocation = Point(48.77, 9.18),
                onPlan = { start, _, _ ->
                    planCalls++
                    lastStart = start
                },
            )
        }

        composeTestRule.waitForIdle()
        assertThat(planCalls).isEqualTo(1)
        assertThat(lastStart).isEqualTo(Point(48.77, 9.18))

        // Drag-start gesture promotes the override.
        composeTestRule.runOnIdle { startOverride.value = Point(48.78, 9.19) }
        composeTestRule.waitForIdle()
        assertThat(planCalls).isEqualTo(2)
        assertThat(lastStart).isEqualTo(Point(48.78, 9.19))
    }

    @Test
    fun auto_plan_calls_on_no_fix_when_destination_set_without_gps() {
        val destination = mutableStateOf<Destination?>(null)
        var planCalls = 0
        var noFixCalls = 0

        composeTestRule.setContent {
            AutoPlanEffect(
                destination = destination.value,
                intermediates = emptyList(),
                startOverride = null,
                currentLocation = null,
                onPlan = { _, _, _ -> planCalls++ },
                onNoFix = { noFixCalls++ },
            )
        }

        composeTestRule.waitForIdle()
        assertThat(planCalls).isEqualTo(0)
        assertThat(noFixCalls).isEqualTo(0)

        composeTestRule.runOnIdle { destination.value = Destination("D", 48.83, 9.22) }
        composeTestRule.waitForIdle()
        assertThat(planCalls).isEqualTo(0)
        assertThat(noFixCalls).isEqualTo(1)
    }

    @Test
    fun auto_plan_does_not_fire_without_destination_even_on_drift() {
        // With no destination there's nothing to plan, so drift must
        // stay inert.
        val currentLocation = mutableStateOf<Point?>(Point(48.77, 9.18))
        var planCalls = 0

        composeTestRule.setContent {
            AutoPlanEffect(
                destination = null,
                intermediates = emptyList(),
                startOverride = null,
                currentLocation = currentLocation.value,
                onPlan = { _, _, _ -> planCalls++ },
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { currentLocation.value = Point(48.77001, 9.18001) }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { currentLocation.value = Point(48.77002, 9.18002) }
        composeTestRule.waitForIdle()

        assertThat(planCalls).isEqualTo(0)
    }

    // ------- RouteEditUploadReset ----------------------------------

    @Test
    fun route_edit_resets_success_state_on_intermediates_change() {
        val intermediates = mutableStateOf<List<Waypoint>>(emptyList())
        val uploadState = mutableStateOf<UploadButtonState>(UploadButtonState.Success)
        var resets = 0

        composeTestRule.setContent {
            RouteEditUploadReset(
                destination = Destination("D", 48.83, 9.22),
                intermediates = intermediates.value,
                startOverride = null,
                uploadState = uploadState.value,
                onReset = {
                    resets++
                    uploadState.value = UploadButtonState.Idle
                },
            )
        }

        composeTestRule.waitForIdle()
        // First composition with Success fires the reset once
        // (defensive — restored sessions land here too).
        assertThat(resets).isEqualTo(1)
        assertThat(uploadState.value).isEqualTo(UploadButtonState.Idle)

        // Put the button back into Success (after a re-upload), then
        // edit the route — the reset must fire again.
        composeTestRule.runOnIdle { uploadState.value = UploadButtonState.Success }
        composeTestRule.runOnIdle {
            intermediates.value = listOf(Waypoint(1L, 48.80, 9.20))
        }
        composeTestRule.waitForIdle()
        assertThat(resets).isAtLeast(2)
        assertThat(uploadState.value).isEqualTo(UploadButtonState.Idle)
    }

    @Test
    fun route_edit_resets_failed_state_on_start_override_change() {
        val startOverride = mutableStateOf<Point?>(null)
        val uploadState = mutableStateOf<UploadButtonState>(UploadButtonState.Failed("nope"))

        composeTestRule.setContent {
            RouteEditUploadReset(
                destination = Destination("D", 48.83, 9.22),
                intermediates = emptyList(),
                startOverride = startOverride.value,
                uploadState = uploadState.value,
                onReset = { uploadState.value = UploadButtonState.Idle },
            )
        }

        composeTestRule.waitForIdle()
        // Failed at first composition is reset — the user reaching
        // the screen with a stale Failed state shouldn't see a
        // "Retry — …" pill lingering.
        assertThat(uploadState.value).isEqualTo(UploadButtonState.Idle)

        composeTestRule.runOnIdle { uploadState.value = UploadButtonState.Failed("again") }
        composeTestRule.runOnIdle { startOverride.value = Point(48.78, 9.19) }
        composeTestRule.waitForIdle()
        assertThat(uploadState.value).isEqualTo(UploadButtonState.Idle)
    }

    @Test
    fun route_edit_does_not_reset_while_upload_is_in_flight() {
        val intermediates = mutableStateOf<List<Waypoint>>(emptyList())
        val uploadState = mutableStateOf<UploadButtonState>(UploadButtonState.Uploading)
        var resets = 0

        composeTestRule.setContent {
            RouteEditUploadReset(
                destination = Destination("D", 48.83, 9.22),
                intermediates = intermediates.value,
                startOverride = null,
                uploadState = uploadState.value,
                onReset = {
                    resets++
                    uploadState.value = UploadButtonState.Idle
                },
            )
        }

        composeTestRule.waitForIdle()
        assertThat(resets).isEqualTo(0)
        assertThat(uploadState.value).isEqualTo(UploadButtonState.Uploading)

        composeTestRule.runOnIdle {
            intermediates.value = listOf(Waypoint(7L, 48.80, 9.20))
        }
        composeTestRule.waitForIdle()
        // Still in flight — the reset must not clobber an Uploading
        // state. The upload's own completion handler is responsible
        // for surfacing Idle when its captured snapshot diverges.
        assertThat(resets).isEqualTo(0)
        assertThat(uploadState.value).isEqualTo(UploadButtonState.Uploading)
    }

    @Test
    fun route_edit_resets_on_destination_change() {
        val destination = mutableStateOf<Destination?>(Destination("A", 48.83, 9.22))
        val uploadState = mutableStateOf<UploadButtonState>(UploadButtonState.Success)

        composeTestRule.setContent {
            RouteEditUploadReset(
                destination = destination.value,
                intermediates = emptyList(),
                startOverride = null,
                uploadState = uploadState.value,
                onReset = { uploadState.value = UploadButtonState.Idle },
            )
        }

        composeTestRule.waitForIdle()
        assertThat(uploadState.value).isEqualTo(UploadButtonState.Idle)

        composeTestRule.runOnIdle { uploadState.value = UploadButtonState.Success }
        composeTestRule.runOnIdle {
            destination.value = Destination("B", 48.84, 9.23)
        }
        composeTestRule.waitForIdle()
        assertThat(uploadState.value).isEqualTo(UploadButtonState.Idle)
    }
}
