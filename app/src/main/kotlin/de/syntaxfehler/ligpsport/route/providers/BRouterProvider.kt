package de.syntaxfehler.ligpsport.route.providers

import de.syntaxfehler.ligpsport.route.Point
import de.syntaxfehler.ligpsport.route.RouteProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * BRouter web — cycling-optimised OSM router at brouter.de.
 *
 * The endpoint is `/brouter?…` on the public instance — earlier code
 * appended `/getroute` which 404s. See PROTOCOL.md §… and the BRouter
 * upstream docs.
 */
class BRouterProvider(
    private val baseUrl: String = "https://brouter.de/brouter",
    private val client: HttpClient = HttpClient(Android) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    },
) : RouteProvider {
    override val id: String = "brouter"
    override val displayName: String = "BRouter"
    override val description: String = "Cycling-optimised OSM router via brouter.de"
    override val isOffline: Boolean = false

    override suspend fun planGpx(start: Point, end: Point, profile: String): ByteArray {
        val response: HttpResponse = client.get(baseUrl) {
            parameter(
                "lonlats",
                "${start.longitude},${start.latitude}|${end.longitude},${end.latitude}",
            )
            parameter("profile", profile)
            parameter("alternativeidx", "0")
            parameter("format", "gpx")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("BRouter request failed: ${response.status}")
        }
        return response.bodyAsText().toByteArray(Charsets.UTF_8)
    }
}
