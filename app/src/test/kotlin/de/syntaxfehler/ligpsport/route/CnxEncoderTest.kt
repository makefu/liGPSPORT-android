package de.syntaxfehler.ligpsport.route

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

class CnxEncoderTest {

    // Save / restore the JVM default locale around each test so the
    // de_DE regression test below doesn't leak into the rest of the
    // suite. The encoder must always emit period-decimal coordinates
    // regardless of what locale the surrounding JVM is in.
    private var savedLocale: Locale = Locale.getDefault()

    @Before
    fun captureLocale() { savedLocale = Locale.getDefault() }

    @After
    fun restoreLocale() { Locale.setDefault(savedLocale) }

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

    @Test
    fun coordinates_use_period_decimal_under_de_de_locale() {
        // Regression for the "693 km off" bug: on a de_DE phone the
        // old encoder used the JVM default locale for the first
        // record's absolute lat/lon, producing `48,7561529` (comma
        // decimal). The CNX <Tracks> field uses commas as field
        // separators, so the parser on the BSC200 read each record as
        // 4-5 values instead of 3 and the on-device goal distance
        // ended up hundreds of kilometres wrong.
        Locale.setDefault(Locale.GERMANY)
        val route = RouteData(
            name = "test",
            points = listOf(
                Point(48.7561529, 9.2263629, 300.0),
                Point(48.7562000, 9.2264000, 305.0),
            ),
        )
        val xml = String(CnxEncoder.encode(route, routeId = 1), Charsets.UTF_8)

        // The first record must contain period decimals.
        assertThat(xml).contains("<Tracks>48.7561529,9.2263629,30000;")
        // Distance comes from BigDecimal.toPlainString() which is
        // locale-independent — guard against any future refactor that
        // pipes the value through a default-locale formatter.
        val distance = Regex("<Distance>([^<]+)</Distance>").find(xml)!!.groupValues[1]
        assertThat(distance).doesNotContain(",")
        assertThat(distance).contains(".")
    }
}
