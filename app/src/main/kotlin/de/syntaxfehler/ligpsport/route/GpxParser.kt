package de.syntaxfehler.ligpsport.route

import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * GPX parser using `javax.xml.parsers` (SAX), available identically on
 * the Android runtime *and* plain JVM (so unit tests work). Reads
 * `<trkpt lat="" lon=""><ele>…</ele></trkpt>` inside `<trkseg>` blocks
 * as well as `<rtept>` route points. Returns a flat [RouteData] with
 * all points concatenated — sufficient for both BRouter responses
 * (single trkseg) and OsmAnd exports.
 */
object GpxParser {
    fun parse(input: InputStream, defaultName: String = "Route"): RouteData {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newSAXParser()
        val handler = GpxHandler(defaultName)
        try {
            parser.parse(input, handler)
        } catch (e: SAXException) {
            throw RouteParseError("invalid GPX XML: ${e.message}", e)
        }
        if (handler.points.isEmpty()) {
            throw RouteParseError("GPX contains no <trkpt> or <rtept> elements")
        }
        return RouteData(name = handler.routeName ?: defaultName, points = handler.points.toList())
    }

    fun parse(bytes: ByteArray, defaultName: String = "Route"): RouteData =
        parse(bytes.inputStream(), defaultName)

    private class GpxHandler(private val defaultName: String) : DefaultHandler() {
        val points = mutableListOf<Point>()
        var routeName: String? = null

        private var currentLat: Double? = null
        private var currentLon: Double? = null
        private var currentEle: Double? = null
        private var insidePoint = false
        private var insideEle = false
        private var insideName = false
        private var depth = 0
        private val textBuf = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes?) {
            depth++
            when (qName) {
                "trkpt", "rtept" -> {
                    insidePoint = true
                    currentLat = attrs?.getValue("lat")?.toDoubleOrNull()
                    currentLon = attrs?.getValue("lon")?.toDoubleOrNull()
                    currentEle = null
                }
                "ele" -> if (insidePoint) {
                    insideEle = true
                    textBuf.setLength(0)
                }
                "name" -> if (depth <= 3 && routeName == null) {
                    insideName = true
                    textBuf.setLength(0)
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (insideEle || insideName) {
                textBuf.append(ch, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "trkpt", "rtept" -> {
                    val lat = currentLat
                    val lon = currentLon
                    if (lat != null && lon != null) {
                        points.add(Point(lat, lon, currentEle))
                    }
                    insidePoint = false
                    currentEle = null
                }
                "ele" -> {
                    if (insideEle) {
                        currentEle = textBuf.toString().trim().toDoubleOrNull()
                    }
                    insideEle = false
                }
                "name" -> {
                    if (insideName) {
                        val n = textBuf.toString().trim()
                        if (n.isNotEmpty()) routeName = n
                    }
                    insideName = false
                }
            }
            depth--
        }
    }
}
