package de.syntaxfehler.ligpsport.route.providers

import de.syntaxfehler.ligpsport.route.Point
import de.syntaxfehler.ligpsport.route.RouteProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OSRM public router at router.project-osrm.org. Returns GeoJSON; we
 * convert it to GPX in-code because the public API doesn't expose a
 * native `format=gpx` option.
 *
 * Profile mapping: BRouter's `trekking` ≈ OSRM's `bike` (closest
 * cycling-style profile the public instance ships). `foot` / `driving`
 * passed through verbatim.
 */
class OsrmProvider(
    private val baseUrl: String = "https://router.project-osrm.org",
    private val client: HttpClient = HttpClient(Android) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    },
) : RouteProvider {
    override val id: String = "osrm"
    override val displayName: String = "OSRM"
    override val description: String = "Alternative OSM router via project-osrm.org"
    override val isOffline: Boolean = false

    override suspend fun planGpx(
        start: Point,
        end: Point,
        intermediates: List<Point>,
        profile: String,
    ): ByteArray {
        val osrmProfile = profileMap[profile] ?: "bike"
        // OSRM accepts an N-coord polyline via `lon,lat;lon,lat;…`.
        val pts = buildList {
            add(start); addAll(intermediates); add(end)
        }
        val coords = pts.joinToString(";") { "${it.longitude},${it.latitude}" }
        val response = client.get("$baseUrl/route/v1/$osrmProfile/$coords") {
            parameter("geometries", "geojson")
            parameter("overview", "full")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("OSRM request failed: ${response.status}")
        }
        val body = response.bodyAsText()
        val coordinates = parseOsrmCoordinates(body)
        if (coordinates.isEmpty()) {
            throw IllegalStateException("OSRM returned no route geometry")
        }
        return geojsonToGpx(coordinates).toByteArray(Charsets.UTF_8)
    }

    companion object {
        private val profileMap = mapOf(
            "trekking" to "bike",
            "bike" to "bike",
            "cycle" to "bike",
            "foot" to "foot",
            "walking" to "foot",
            "driving" to "driving",
            "car" to "driving",
        )

        /**
         * Pull `routes[0].geometry.coordinates` out of an OSRM response.
         * Returns `List<Pair<lon, lat>>` in OSRM order.
         */
        internal fun parseOsrmCoordinates(body: String): List<Pair<Double, Double>> {
            val root = Json.parseToJsonElement(body).jsonObject
            val routes = root["routes"]?.jsonArray ?: return emptyList()
            val first = routes.firstOrNull()?.jsonObject ?: return emptyList()
            val geometry = first["geometry"]?.jsonObject ?: return emptyList()
            val coords = geometry["coordinates"]?.jsonArray ?: return emptyList()
            return coords.mapNotNull { pt ->
                val arr = pt.jsonArray
                if (arr.size < 2) return@mapNotNull null
                val lon = arr[0].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
                val lat = arr[1].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
                lon to lat
            }
        }

        /**
         * Build a minimal GPX 1.1 trkpt list. No elevation —
         * [CnxEncoder] tolerates missing `<ele>`.
         */
        internal fun geojsonToGpx(coordinates: List<Pair<Double, Double>>): String {
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<gpx version=\"1.1\" creator=\"ligpsport-osrm\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            sb.append("  <trk><name>osrm</name><trkseg>\n")
            for ((lon, lat) in coordinates) {
                sb.append("    <trkpt lat=\"")
                sb.append(lat)
                sb.append("\" lon=\"")
                sb.append(lon)
                sb.append("\"/>\n")
            }
            sb.append("  </trkseg></trk>\n")
            sb.append("</gpx>\n")
            return sb.toString()
        }
    }
}
