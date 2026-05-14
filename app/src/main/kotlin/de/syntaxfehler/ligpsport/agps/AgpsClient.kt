package de.syntaxfehler.ligpsport.agps

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fetches u-blox **AssistNow Online** GNSS assistance data.
 *
 * AssistNow Online returns a freshly-generated payload of stitched
 * UBX-MGA messages (ephemeris + reference time/position) valid for
 * ~2–4 hours; pushing it to the BSC200 lets the cycling computer skip
 * the cold-start TTFF wait (~30–90 s) and drop into hot-start
 * (~5–10 s) when the next ride begins.
 *
 * **Reverse-engineering provenance:** the official iGPSPORT app calls
 * `http://online-live1.services.u-blox.com/GetOnlineData.ashx?token=…`
 * (see `DeviceBleManagerHandler.sendAGPS` in the v7.45.03 APK,
 * `classes5.dex` line ~10213). The token itself isn't hardcoded in
 * the APK — the app fetches it (plus the trailing query string the
 * device expects) from
 * `https://prod.en.igpsport.com/service/mobile/api/Config/GetDefaultConfig?type=0`,
 * which returns
 * `{"code":0,"message":"","data":"<token>&gnss=gps&datatype=eph"}`.
 *
 * Resolution order in [fetchOnline]:
 * 1. An explicit token passed by the caller — typically
 *    `BuildConfig.AGPS_TOKEN` injected via `LIGPSPORT_AGPS_TOKEN`.
 *    Use this when you've registered for your own u-blox AssistNow
 *    developer access.
 * 2. Fall back to the iGPSport backend — the same source the official
 *    app uses. Means "AGPS just works" without any per-build token
 *    provisioning; the trade-off is a runtime dependency on
 *    `prod.en.igpsport.com` being reachable.
 */
class AgpsClient(
    private val client: HttpClient = HttpClient(Android) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    },
) {
    /**
     * Fetch AssistNow Online assistance data. If [token] is null or
     * blank, the iGPSport backend is queried for one first.
     *
     * Throws on HTTP failure so the caller can decide whether to
     * surface the error or proceed without AGPS.
     */
    suspend fun fetchOnline(token: String? = null): ByteArray {
        // u-blox expects the entire `?token=<...>` suffix as a single
        // string — the iGPSport backend returns
        // `"<token>&gnss=gps&datatype=eph"` so the suffix already
        // includes the query parameters the device expects. When the
        // caller supplies a raw token we still append the same default
        // query so the device receives the format it knows.
        val tokenSuffix = if (token.isNullOrBlank()) {
            fetchTokenSuffixFromIgpsport()
        } else {
            "$token&gnss=gps&datatype=eph"
        }
        val url = "$UBLOX_ONLINE_URL?token=$tokenSuffix"
        val response = client.get(url)
        return response.body()
    }

    /**
     * Hit iGPSport's prod config endpoint exactly the way the official
     * app does (`type=0` = AGPS) and return the literal `data` field.
     * Visible for tests.
     */
    internal suspend fun fetchTokenSuffixFromIgpsport(): String {
        val response = client.get("$IGPSPORT_CONFIG_BASE_URL/service/mobile/api/Config/GetDefaultConfig") {
            url.parameters.append("type", "0")
        }
        val body = response.bodyAsText()
        val root = Json.parseToJsonElement(body).jsonObject
        val code = root["code"]?.jsonPrimitive?.content
        val data = root["data"]?.jsonPrimitive?.content
        if (code != "0" || data.isNullOrBlank()) {
            error("iGPSport config endpoint returned unexpected body: $body")
        }
        return data
    }

    fun close() {
        client.close()
    }

    companion object {
        const val UBLOX_ONLINE_URL = "http://online-live1.services.u-blox.com/GetOnlineData.ashx"

        // Mirrors `Constants.URL_PROD_EN` (smali-c4
        // Constants.smali:426) — the global build of the official
        // app talks to this host. `prod.zh.igpsport.com` is the
        // mainland-China sibling and serves the same payload.
        const val IGPSPORT_CONFIG_BASE_URL = "https://prod.en.igpsport.com"
    }
}
