package de.syntaxfehler.ligpsport.strava

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Thin wrapper over the parts of the Strava v3 API this app needs:
 * the token-refresh endpoint and the (asynchronous) activity-upload
 * endpoint.
 *
 * Wire reference: https://developers.strava.com/docs/uploads/ and
 * https://developers.strava.com/docs/authentication/
 *
 * Uploads are not synchronous — POSTing a FIT returns a *ticket*, and
 * the activity only exists once [pollUpload] reports a non-null
 * `activityId`. Callers must poll; see [StravaUploader].
 */
class StravaClient(
    private val clientId: String,
    private val clientSecret: String,
    private val client: HttpClient = HttpClient(Android),
    private val baseUrl: String = "https://www.strava.com",
) {
    constructor(
        credentials: StravaStore.Credentials,
    ) : this(credentials.clientId, credentials.clientSecret)

    class StravaException(message: String) : Exception(message)

    /**
     * Trade a refresh token for a fresh access token. Strava may rotate
     * the refresh token here, so the caller must persist whatever comes
     * back rather than keeping the old one.
     */
    suspend fun refresh(refreshToken: String): StravaStore.Tokens {
        val response = client.submitForm(
            url = "$baseUrl/oauth/token",
            formParameters = Parameters.build {
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("refresh_token", refreshToken)
                append("grant_type", "refresh_token")
            },
        )
        return parseTokens(response, "token refresh")
    }

    /**
     * POST a FIT file. Returns the upload ticket — processing happens
     * server-side afterwards.
     *
     * `externalId` is Strava's own duplicate guard: re-uploading the
     * same external id returns an error instead of creating a second
     * activity, which is what stops a re-download from double-posting a
     * ride.
     */
    suspend fun uploadFit(
        accessToken: String,
        file: File,
        name: String?,
        externalId: String,
    ): UploadStatus {
        val response = client.post("$baseUrl/api/v3/uploads") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("data_type", "fit")
                        append("external_id", externalId)
                        if (!name.isNullOrBlank()) append("name", name)
                        append(
                            "file",
                            file.readBytes(),
                            Headers.build {
                                append(HttpHeaders.ContentType, "application/octet-stream")
                                append(
                                    HttpHeaders.ContentDisposition,
                                    "filename=\"${file.name}\"",
                                )
                            },
                        )
                    },
                ),
            )
        }
        return parseUploadStatus(response, "upload")
    }

    /**
     * Mute an activity from followers' feeds (`hide_from_home`).
     *
     * This is *not* the same as private. Strava's three-tier visibility
     * (Everyone / Followers / Only You) is not exposed through the API
     * at all — the old `private` upload flag is defunct and ignored.
     * Muting only keeps the ride out of the feed, so it raises no
     * notifications; the activity is still reachable at its URL and in
     * the athlete's profile, per their account-default visibility.
     * True privacy has to be set once on the Strava account itself.
     */
    suspend fun muteActivity(accessToken: String, activityId: Long) {
        val response = client.put("$baseUrl/api/v3/activities/$activityId") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            setBody(FormDataContent(Parameters.build { append("hide_from_home", "true") }))
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw StravaException(
                "muting activity failed (HTTP ${response.status.value}): ${body.take(300)}",
            )
        }
    }

    /**
     * The signed-in athlete's display name, or null if it can't be had.
     *
     * Best-effort on purpose: this needs the `read` scope, and a token
     * minted with `activity:write` alone — which is all an upload
     * actually requires — gets a 401 here. A missing name is cosmetic,
     * so it must never turn a working configuration into a reported
     * failure.
     */
    suspend fun athleteName(accessToken: String): String? = runCatching {
        val response = client.get("$baseUrl/api/v3/athlete") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        if (!response.status.isSuccess()) return null
        json.decodeFromString<Athlete>(response.bodyAsText()).displayName()
    }.getOrNull()

    /** Poll one upload ticket. See [StravaUploader.upload] for the loop. */
    suspend fun pollUpload(accessToken: String, uploadId: Long): UploadStatus {
        val response = client.get("$baseUrl/api/v3/uploads/$uploadId") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        return parseUploadStatus(response, "upload status")
    }

    fun close() {
        client.close()
    }

    private suspend fun parseTokens(response: HttpResponse, what: String): StravaStore.Tokens {
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw StravaException("$what failed (HTTP ${response.status.value}): ${body.take(300)}")
        }
        val parsed = json.decodeFromString<TokenResponse>(body)
        return StravaStore.Tokens(
            accessToken = parsed.accessToken,
            refreshToken = parsed.refreshToken,
            expiresAtEpochSeconds = parsed.expiresAt,
            athleteName = parsed.athlete?.displayName(),
        )
    }

    private suspend fun parseUploadStatus(response: HttpResponse, what: String): UploadStatus {
        val body = response.bodyAsText()
        // Strava reports upload rejections (duplicate, corrupt FIT) as a
        // 4xx with a JSON body whose `error` explains why — more useful
        // to the user than the status code, so parse before giving up.
        if (!response.status.isSuccess()) {
            val parsed = runCatching { json.decodeFromString<UploadStatus>(body) }.getOrNull()
            val detail = parsed?.error ?: body.take(300)
            throw StravaException("$what failed (HTTP ${response.status.value}): $detail")
        }
        return json.decodeFromString(body)
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_at") val expiresAt: Long,
        val athlete: Athlete? = null,
    )

    @Serializable
    private data class Athlete(
        val firstname: String? = null,
        val lastname: String? = null,
        val username: String? = null,
    ) {
        fun displayName(): String? =
            listOfNotNull(firstname, lastname)
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
                ?: username
    }

    /** https://developers.strava.com/docs/uploads/ — Upload status object. */
    @Serializable
    data class UploadStatus(
        val id: Long = 0L,
        @SerialName("external_id") val externalId: String? = null,
        val error: String? = null,
        val status: String? = null,
        @SerialName("activity_id") val activityId: Long? = null,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
