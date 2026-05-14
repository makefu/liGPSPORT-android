package de.syntaxfehler.ligpsport.route

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * CNX (iGPSPORT proprietary) route format encoder — Kotlin port of
 * ligpsport/cnx.py. Single-line ASCII XML wrapping a delta-encoded
 * `<Tracks>` field; this is the only route format BSC200 firmware
 * accepts via the FILE_OPERATION ADD upload path. See PROTOCOL.md §7.1.2.
 *
 * BigDecimal is used to match the Python Decimal arithmetic so that
 * the byte-level output stays comparable to the cloud-captured
 * reference file under tests/fixtures/cnx_cloud_capture.cnx in the
 * Python project.
 */

data class Waypoint(
    val latitude: Double,
    val longitude: Double,
    val name: String = "",
    val poiType: Int = 0,
)

object CnxEncoder {
    private val DEC_2DP = BigDecimal("0.01")
    private val LAT_LON_SCALE = BigDecimal(10_000_000)
    private val ELE_SCALE = BigDecimal(100)
    private val EARTH_RADIUS_M = BigDecimal(6_371_000)

    fun encode(
        route: RouteData,
        routeId: Long = 1L,
        waypoints: List<Waypoint> = emptyList(),
        lang: Int = 0,
    ): ByteArray {
        require(route.points.isNotEmpty()) { "can't emit a CNX file for a route with no points" }
        val metrics = calculateMetrics(route.points)
        val tracks = encodeTracks(route.points)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<Route>")
        sb.append("<Id>").append(routeId).append("</Id>")
        sb.append("<Distance>").append(metrics.distance).append("</Distance>")
        sb.append("<Duration></Duration>")
        sb.append("<Ascent>").append(metrics.ascent).append("</Ascent>")
        sb.append("<Descent>").append(metrics.descent).append("</Descent>")
        sb.append("<Encode>2</Encode>")
        sb.append("<Lang>").append(lang).append("</Lang>")
        sb.append("<TracksCount>").append(route.points.size).append("</TracksCount>")
        sb.append("<Tracks>").append(tracks).append("</Tracks>")
        sb.append("<Navs/>")
        if (waypoints.isNotEmpty()) {
            sb.append("<Points>")
            waypoints.forEach { w ->
                sb.append("<Point>")
                sb.append("<Lat>").append(formatCoord(w.latitude)).append("</Lat>")
                sb.append("<Lng>").append(formatCoord(w.longitude)).append("</Lng>")
                sb.append("<Type>").append(w.poiType).append("</Type>")
                sb.append("<Descr>").append(xmlEscape(w.name)).append("</Descr>")
                sb.append("</Point>")
            }
            sb.append("</Points>")
        } else {
            sb.append("<Points/>")
        }
        sb.append("<PointsCount>").append(waypoints.size).append("</PointsCount>")
        sb.append("</Route>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private data class Metrics(val distance: String, val ascent: String, val descent: String)

    private fun calculateMetrics(points: List<Point>): Metrics {
        var distance = BigDecimal.ZERO
        var ascent = BigDecimal.ZERO
        var descent = BigDecimal.ZERO
        var prev: Point? = null
        for (p in points) {
            if (prev != null) {
                distance = distance.add(haversine3dBd(prev, p))
                val eleDiff = ele(p).subtract(ele(prev))
                if (eleDiff.signum() > 0) ascent = ascent.add(eleDiff) else descent = descent.add(eleDiff)
                distance = distance.setScale(2, RoundingMode.HALF_UP)
            }
            prev = p
        }
        return Metrics(
            distance = distance.setScale(2, RoundingMode.HALF_UP).toPlainString(),
            ascent = ascent.setScale(0, RoundingMode.HALF_UP).toPlainString(),
            descent = descent.setScale(0, RoundingMode.HALF_UP).toPlainString(),
        )
    }

    private fun ele(p: Point): BigDecimal =
        if (p.elevation == null) BigDecimal.ZERO else BigDecimal(p.elevation.toString())

    private fun haversine3dBd(a: Point, b: Point): BigDecimal {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dlat = Math.toRadians(b.latitude - a.latitude)
        val dlon = Math.toRadians(b.longitude - a.longitude)
        val sinDLat = Math.sin(dlat / 2.0)
        val sinDLon = Math.sin(dlon / 2.0)
        val h = sinDLat * sinDLat + Math.cos(lat1) * Math.cos(lat2) * sinDLon * sinDLon
        val angle = 2.0 * Math.atan2(Math.sqrt(h), Math.sqrt(1.0 - h))
        val horizontal = EARTH_RADIUS_M.multiply(BigDecimal(angle.toString()))
        val eleDiff = ele(b).subtract(ele(a))
        val sumSq = horizontal.multiply(horizontal).add(eleDiff.multiply(eleDiff))
        // Newton iteration for sqrt on BigDecimal.
        return sumSq.sqrtCompat()
    }

    private fun BigDecimal.sqrtCompat(): BigDecimal {
        // BigDecimal.sqrt(MathContext) exists since JDK 9.
        return this.sqrt(java.math.MathContext.DECIMAL64)
    }

    private fun encodeTracks(points: List<Point>): String {
        val records = mutableListOf<String>()
        val first = points[0]
        val firstEle = ele(first).multiply(ELE_SCALE).setScale(0, RoundingMode.HALF_UP).toPlainString()
        records.add("${formatCoord(first.latitude)},${formatCoord(first.longitude)},$firstEle")

        val firstDiffs = mutableListOf<Triple<BigDecimal, BigDecimal, BigDecimal>>()
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dLat = BigDecimal(b.latitude.toString()).subtract(BigDecimal(a.latitude.toString())).multiply(LAT_LON_SCALE)
            val dLon = BigDecimal(b.longitude.toString()).subtract(BigDecimal(a.longitude.toString())).multiply(LAT_LON_SCALE)
            val dEle = ele(b).multiply(ELE_SCALE).subtract(ele(a).multiply(ELE_SCALE))
            firstDiffs.add(Triple(dLat, dLon, dEle))
        }
        if (firstDiffs.isNotEmpty()) {
            val (dLat, dLon, dEle) = firstDiffs[0]
            records.add("${roundInt(dLat)},${roundInt(dLon)},${roundInt(dEle)}")
        }
        for (i in 1 until firstDiffs.size) {
            val ddLat = firstDiffs[i].first.subtract(firstDiffs[i - 1].first)
            val ddLon = firstDiffs[i].second.subtract(firstDiffs[i - 1].second)
            val dEle = firstDiffs[i].third
            records.add("${roundInt(ddLat)},${roundInt(ddLon)},${roundInt(dEle)}")
        }
        return records.joinToString(";") + ";"
    }

    private fun roundInt(v: BigDecimal): String =
        v.setScale(0, RoundingMode.HALF_UP).toPlainString()

    private fun formatCoord(v: Double): String {
        // Match Python f"{v:.7f}".rstrip("0").rstrip(".")
        val s = "%.7f".format(v).trimEnd('0').trimEnd('.')
        return if (s.isEmpty()) "0" else s
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
}
