package de.syntaxfehler.ligpsport.route.providers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OsrmProviderTest {
    @Test
    fun parses_coordinates_from_response() {
        val body = """
            {
              "code": "Ok",
              "routes": [
                {
                  "geometry": {
                    "coordinates": [
                      [9.18, 48.77],
                      [9.20, 48.79],
                      [9.229, 48.834]
                    ]
                  },
                  "distance": 7000,
                  "duration": 1200
                }
              ]
            }
        """.trimIndent()
        val coords = OsrmProvider.parseOsrmCoordinates(body)
        assertThat(coords).hasSize(3)
        assertThat(coords[0]).isEqualTo(9.18 to 48.77)
        assertThat(coords[2]).isEqualTo(9.229 to 48.834)
    }

    @Test
    fun empty_routes_array_returns_empty_list() {
        val body = """{"code":"NoRoute","routes":[]}"""
        assertThat(OsrmProvider.parseOsrmCoordinates(body)).isEmpty()
    }

    @Test
    fun geojson_to_gpx_emits_valid_xml() {
        val gpx = OsrmProvider.geojsonToGpx(listOf(9.18 to 48.77, 9.229 to 48.834))
        assertThat(gpx).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        assertThat(gpx).contains("<gpx ")
        assertThat(gpx).contains("<trkpt lat=\"48.77\" lon=\"9.18\"/>")
        assertThat(gpx).contains("<trkpt lat=\"48.834\" lon=\"9.229\"/>")
        assertThat(gpx).endsWith("</gpx>\n")
    }
}
