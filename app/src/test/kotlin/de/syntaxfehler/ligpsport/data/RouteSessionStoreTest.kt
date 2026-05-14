package de.syntaxfehler.ligpsport.data

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class RouteSessionStoreTest {

    @Before fun setUp() { RouteSessionStore.clear() }
    @After fun tearDown() { RouteSessionStore.clear() }

    @Test
    fun starts_empty() {
        assertThat(RouteSessionStore.get()).isNull()
    }

    @Test
    fun set_then_get_returns_same_session() {
        val s = RouteSessionStore.Session("Mitte", 52.52, 13.4, plannedGpx = null)
        RouteSessionStore.set(s)
        assertThat(RouteSessionStore.get()).isEqualTo(s)
    }

    @Test
    fun clear_removes_session() {
        RouteSessionStore.set(RouteSessionStore.Session("x", 0.0, 0.0))
        RouteSessionStore.clear()
        assertThat(RouteSessionStore.get()).isNull()
    }

    @Test
    fun setPlannedGpx_updates_only_gpx_field() {
        val s = RouteSessionStore.Session("Mitte", 52.52, 13.4, plannedGpx = null)
        RouteSessionStore.set(s)
        val gpx = byteArrayOf(1, 2, 3, 4)
        RouteSessionStore.setPlannedGpx(gpx)
        val out = RouteSessionStore.get()!!
        assertThat(out.destinationName).isEqualTo("Mitte")
        assertThat(out.destinationLat).isEqualTo(52.52)
        assertThat(out.destinationLon).isEqualTo(13.4)
        assertThat(out.plannedGpx).isEqualTo(gpx)
    }

    @Test
    fun setPlannedGpx_no_session_is_noop() {
        // Should not throw and should not create a partial session.
        RouteSessionStore.setPlannedGpx(byteArrayOf(1, 2))
        assertThat(RouteSessionStore.get()).isNull()
    }

    @Test
    fun setPlannedGpx_can_clear_gpx_keeping_destination() {
        RouteSessionStore.set(RouteSessionStore.Session("x", 1.0, 2.0, byteArrayOf(7)))
        RouteSessionStore.setPlannedGpx(null)
        val out = RouteSessionStore.get()!!
        assertThat(out.plannedGpx).isNull()
        assertThat(out.destinationName).isEqualTo("x")
    }
}
