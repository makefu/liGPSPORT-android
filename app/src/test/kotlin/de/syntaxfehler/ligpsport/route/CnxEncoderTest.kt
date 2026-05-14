package de.syntaxfehler.ligpsport.route

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CnxEncoderTest {
    @Test
    fun encode_minimal_route() {
        val route = RouteData(
            name = "test",
            points = listOf(
                Point(48.7561529, 9.2263629, 300.0),
                Point(48.7562000, 9.2264000, 305.0),
            ),
        )
        val bytes = CnxEncoder.encode(route, routeId = 42)
        val xml = String(bytes, Charsets.UTF_8)
        // Structural checks against PROTOCOL.md §7.1.2 layout
        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Route>")
        assertThat(xml).contains("<Id>42</Id>")
        assertThat(xml).contains("<Encode>2</Encode>")
        assertThat(xml).contains("<Lang>0</Lang>")
        assertThat(xml).contains("<TracksCount>2</TracksCount>")
        assertThat(xml).contains("<Navs/>")
        assertThat(xml).contains("<Points/>")
        assertThat(xml).contains("<PointsCount>0</PointsCount>")
        assertThat(xml).endsWith("</Route>")
    }

    @Test
    fun encode_route_emits_first_point_then_diff() {
        val route = RouteData(
            name = "test",
            points = listOf(
                Point(48.7561529, 9.2263629, 300.0),
                Point(48.7562000, 9.2264000, 305.0),
            ),
        )
        val xml = String(CnxEncoder.encode(route), Charsets.UTF_8)
        // First record is the absolute coordinate, scaled-elevation as integer
        assertThat(xml).contains("<Tracks>48.7561529,9.2263629,30000;")
        // Tracks field always terminates with a semicolon
        assertThat(xml).contains(";</Tracks>")
    }

    @Test
    fun empty_route_rejected() {
        try {
            CnxEncoder.encode(RouteData("empty", emptyList()))
            error("expected exception")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun waypoints_emit_points_block() {
        val route = RouteData(
            name = "wp",
            points = listOf(
                Point(48.7561529, 9.2263629),
                Point(48.7562000, 9.2264000),
            ),
        )
        val xml = String(
            CnxEncoder.encode(
                route,
                waypoints = listOf(Waypoint(48.7561, 9.2263, "Start")),
            ),
            Charsets.UTF_8,
        )
        assertThat(xml).contains("<Points><Point>")
        assertThat(xml).contains("<Descr>Start</Descr>")
        assertThat(xml).contains("<PointsCount>1</PointsCount>")
    }
}
