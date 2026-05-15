package de.syntaxfehler.ligpsport.route.providers

import de.syntaxfehler.ligpsport.route.Point
import de.syntaxfehler.ligpsport.route.RouteProvider
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline "no-routing" provider — synthesises a polyline by spherical
 * linear interpolation along the great-circle between start and end.
 * The path won't follow roads, but it's deterministic and produces a
 * route file the BSC200 can store and replay. Useful as:
 *   - the network-failure fallback;
 *   - the e2e test's deterministic offline path;
 *   - a sanity check that the BLE upload pipeline is healthy even when
 *     all the routing backends are down.
 */
class StraightLineProvider(
    private val waypoints: Int = 20,
) : RouteProvider {
    override val id: String = "straightline"
    override val displayName: String = "Straight line"
    override val description: String = "Direct line — no road-following. Offline fallback."
    override val isOffline: Boolean = true

    override suspend fun planGpx(
        start: Point,
        end: Point,
        intermediates: List<Point>,
        profile: String,
    ): ByteArray {
        // Stitch a great-circle interpolation through every segment.
        // First segment keeps both endpoints; later segments drop their
        // start point (it's the previous segment's end) so we don't
        // emit duplicates.
        val anchors = buildList {
            add(start); addAll(intermediates); add(end)
        }
        val pts = ArrayList<Point>()
        for ((i, a) in anchors.withIndex()) {
            if (i == anchors.lastIndex) break
            val seg = interpolate(a, anchors[i + 1], waypoints)
            if (i == 0) pts.addAll(seg) else pts.addAll(seg.drop(1))
        }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"ligpsport-straightline\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk><name>straightline</name><trkseg>\n")
        for (p in pts) {
            sb.append("    <trkpt lat=\"")
            sb.append(p.latitude)
            sb.append("\" lon=\"")
            sb.append(p.longitude)
            sb.append("\"/>\n")
        }
        sb.append("  </trkseg></trk>\n")
        sb.append("</gpx>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /**
         * Slerp [start]→[end] in [n] inclusive steps (so the result has
         * exactly [n] points, with the first being [start] and the last
         * being [end]). Uses the standard great-circle interpolation
         * formula via Cartesian unit vectors.
         */
        internal fun interpolate(start: Point, end: Point, n: Int): List<Point> {
            require(n >= 2) { "need at least 2 waypoints" }
            val lat1 = Math.toRadians(start.latitude)
            val lon1 = Math.toRadians(start.longitude)
            val lat2 = Math.toRadians(end.latitude)
            val lon2 = Math.toRadians(end.longitude)
            // Angular distance (haversine)
            val dlat = lat2 - lat1
            val dlon = lon2 - lon1
            val h = sin(dlat / 2).let { it * it } +
                cos(lat1) * cos(lat2) * sin(dlon / 2).let { it * it }
            val d = 2 * atan2(sqrt(h), sqrt(1 - h))
            if (d < 1e-12) {
                // Degenerate (start == end): just return two copies of start.
                return List(n) { start }
            }
            val result = ArrayList<Point>(n)
            for (i in 0 until n) {
                val f = i.toDouble() / (n - 1).toDouble()
                val a = sin((1 - f) * d) / sin(d)
                val b = sin(f * d) / sin(d)
                val x = a * cos(lat1) * cos(lon1) + b * cos(lat2) * cos(lon2)
                val y = a * cos(lat1) * sin(lon1) + b * cos(lat2) * sin(lon2)
                val z = a * sin(lat1) + b * sin(lat2)
                val lat = atan2(z, sqrt(x * x + y * y))
                val lon = atan2(y, x)
                result.add(Point(Math.toDegrees(lat), Math.toDegrees(lon)))
            }
            return result
        }
    }
}
