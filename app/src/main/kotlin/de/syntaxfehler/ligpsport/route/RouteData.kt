package de.syntaxfehler.ligpsport.route

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Point(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null,
)

data class RouteData(
    val name: String,
    val points: List<Point>,
) {
    /** Sum of 3D haversine distances between consecutive points, in metres. */
    val distanceM: Double by lazy {
        if (points.size < 2) return@lazy 0.0
        var d = 0.0
        for (i in 1 until points.size) {
            d += haversine3dM(points[i - 1], points[i])
        }
        d
    }
}

private const val EARTH_RADIUS_M = 6_371_000.0

internal fun haversine3dM(a: Point, b: Point): Double {
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dlat = Math.toRadians(b.latitude - a.latitude)
    val dlon = Math.toRadians(b.longitude - a.longitude)
    val sinDLat = sin(dlat / 2.0)
    val sinDLon = sin(dlon / 2.0)
    val h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
    val angle = 2.0 * atan2(sqrt(h), sqrt(1.0 - h))
    val horizontal = EARTH_RADIUS_M * angle
    val eleDiff = (b.elevation ?: 0.0) - (a.elevation ?: 0.0)
    return sqrt(horizontal * horizontal + eleDiff * eleDiff)
}

class RouteParseError(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
