package de.syntaxfehler.ligpsport.search

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Type-ahead geocoder backed by Photon (komoot.de), an OpenStreetMap
 * search engine specifically designed for autocomplete use cases.
 *
 * Photon supports a `lat`/`lon` bias point that sorts results by
 * relevance with distance baked in — exactly the "organic" experience
 * users expect (closer matches float to the top, but a clearly-typed
 * faraway city still shows up). Free public instance at
 * https://photon.komoot.io, no API key required.
 */
class PhotonClient(
    private val baseUrl: String = "https://photon.komoot.io",
    private val client: HttpClient = HttpClient(Android),
) {
    suspend fun autocomplete(
        query: String,
        biasLat: Double? = null,
        biasLon: Double? = null,
        limit: Int = 8,
        languageTag: String = "en",
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val response = client.get("$baseUrl/api") {
            parameter("q", query)
            parameter("limit", limit)
            parameter("lang", languageTag)
            if (biasLat != null && biasLon != null) {
                parameter("lat", biasLat)
                parameter("lon", biasLon)
            }
        }
        val body = response.bodyAsText()
        val features = Json.parseToJsonElement(body).jsonObject["features"]?.jsonArray
            ?: return emptyList()

        val results = features.mapNotNull { f -> parseFeature(f.jsonObject, biasLat, biasLon) }
        // Photon already factors in distance via the bias, but stabilise
        // the ordering so the closest match is always first.
        return if (biasLat != null && biasLon != null) {
            results.sortedBy { it.distanceM ?: Double.MAX_VALUE }
        } else {
            results
        }
    }

    /**
     * Reverse-geocode a (lat, lon) to the nearest named feature —
     * usually a street with house number, or a POI. Returns null if
     * Photon has no feature near that point or the call fails.
     */
    suspend fun reverse(
        lat: Double,
        lon: Double,
        languageTag: String = "en",
    ): SearchResult? {
        val response = client.get("$baseUrl/reverse") {
            parameter("lat", lat)
            parameter("lon", lon)
            parameter("lang", languageTag)
        }
        val body = response.bodyAsText()
        val features = Json.parseToJsonElement(body).jsonObject["features"]?.jsonArray
            ?: return null
        return features.firstNotNullOfOrNull { f ->
            parseFeature(f.jsonObject, biasLat = lat, biasLon = lon)
        }
    }

    fun close() {
        client.close()
    }

    private fun parseFeature(
        obj: kotlinx.serialization.json.JsonObject,
        biasLat: Double?,
        biasLon: Double?,
    ): SearchResult? {
        val coords = obj["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return null
        val lon = coords.getOrNull(0)?.jsonPrimitive?.contentOrNullDouble() ?: return null
        val lat = coords.getOrNull(1)?.jsonPrimitive?.contentOrNullDouble() ?: return null
        val props = obj["properties"]?.jsonObject ?: return null
        // For POIs `name` is set; for raw addresses we synthesise one
        // from `street` + `housenumber`. Falls back to whatever single
        // field we can find — better than "Dropped pin".
        val poiName = props["name"]?.jsonPrimitive?.contentOrNull()
        val street = props["street"]?.jsonPrimitive?.contentOrNull()
        val housenum = props["housenumber"]?.jsonPrimitive?.contentOrNull()
        val streetAddr = when {
            street != null && housenum != null -> "$street $housenum"
            street != null -> street
            else -> null
        }
        val name = poiName
            ?: streetAddr
            ?: props["city"]?.jsonPrimitive?.contentOrNull()
            ?: props["country"]?.jsonPrimitive?.contentOrNull()
            ?: return null
        // Description = locality context. Avoid duplicating the name
        // when we built it from street info (so the card doesn't read
        // "Hauptstraße 12 / Hauptstraße 12, Berlin").
        val locality = listOfNotNull(
            if (poiName != null) streetAddr else null,
            props["city"]?.jsonPrimitive?.contentOrNull(),
            props["state"]?.jsonPrimitive?.contentOrNull(),
            props["country"]?.jsonPrimitive?.contentOrNull(),
        ).distinct().joinToString(", ")
        val distanceM = if (biasLat != null && biasLon != null) {
            haversineMeters(biasLat, biasLon, lat, lon)
        } else null
        return SearchResult(
            name = name,
            description = locality,
            latitude = lat,
            longitude = lon,
            distanceM = distanceM,
        )
    }
}

@Serializable
data class SearchResult(
    val name: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    /** Great-circle distance to bias point in metres, if a bias was supplied. */
    val distanceM: Double? = null,
)

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    if (this.isString || this.content.isNotBlank()) this.content else null

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullDouble(): Double? =
    this.content.toDoubleOrNull()
