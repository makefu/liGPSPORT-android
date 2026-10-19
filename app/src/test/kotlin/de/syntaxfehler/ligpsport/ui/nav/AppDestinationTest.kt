package de.syntaxfehler.ligpsport.ui.nav

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The drawer highlights the current entry, and `AppNav` decides
 * hamburger-vs-back, from [AppDestination.forRoute]. Both go wrong
 * quietly if the optional `?mac=` argument isn't stripped — the screen
 * would render with no leading icon at all.
 */
class AppDestinationTest {

    @Test
    fun plain_routes_resolve() {
        assertThat(AppDestination.forRoute("map")).isEqualTo(AppDestination.Map)
        assertThat(AppDestination.forRoute("settings")).isEqualTo(AppDestination.Settings)
    }

    @Test
    fun the_optional_mac_argument_is_stripped() {
        assertThat(AppDestination.forRoute("activities?mac={mac}"))
            .isEqualTo(AppDestination.Activities)
        assertThat(AppDestination.forRoute("routes?mac=AA:BB:CC:DD:EE:FF"))
            .isEqualTo(AppDestination.Routes)
    }

    @Test
    fun sub_screens_and_nulls_are_not_top_level() {
        assertThat(AppDestination.forRoute("pairing")).isNull()
        assertThat(AppDestination.forRoute("previous_rides")).isNull()
        assertThat(AppDestination.forRoute(null)).isNull()
    }

    /**
     * A prefix match would make `settings/routes/{mac}`-style routes
     * look top-level. Only the exact base counts.
     */
    @Test
    fun a_longer_route_that_merely_starts_the_same_is_not_a_match() {
        assertThat(AppDestination.forRoute("settings/routes/AA:BB")).isNull()
        assertThat(AppDestination.forRoute("mapview")).isNull()
    }

    @Test
    fun every_destination_round_trips_through_its_own_route() {
        AppDestination.entries.forEach { dest ->
            assertThat(AppDestination.forRoute(dest.route)).isEqualTo(dest)
        }
    }
}
