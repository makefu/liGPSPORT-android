package de.syntaxfehler.ligpsport.route

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GpxParserTest {
    @Test
    fun parses_trkpt_with_ele() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test">
              <trk><name>Sample</name><trkseg>
                <trkpt lat="52.5200" lon="13.4050"><ele>34.5</ele></trkpt>
                <trkpt lat="52.5300" lon="13.4150"><ele>35.5</ele></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val route = GpxParser.parse(gpx)
        assertThat(route.name).isEqualTo("Sample")
        assertThat(route.points).hasSize(2)
        assertThat(route.points[0].latitude).isEqualTo(52.5200)
        assertThat(route.points[0].longitude).isEqualTo(13.4050)
        assertThat(route.points[0].elevation).isEqualTo(34.5)
    }

    @Test
    fun parses_rtept() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx><rte>
              <rtept lat="52.0" lon="13.0"/>
              <rtept lat="52.1" lon="13.1"/>
            </rte></gpx>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val route = GpxParser.parse(gpx)
        assertThat(route.points).hasSize(2)
        assertThat(route.points[0].elevation).isNull()
    }

    @Test
    fun missing_points_throws() {
        val gpx = "<?xml version=\"1.0\"?><gpx><trk><trkseg/></trk></gpx>".toByteArray(Charsets.UTF_8)
        try {
            GpxParser.parse(gpx)
            error("expected RouteParseError")
        } catch (_: RouteParseError) {
            // expected
        }
    }
}
