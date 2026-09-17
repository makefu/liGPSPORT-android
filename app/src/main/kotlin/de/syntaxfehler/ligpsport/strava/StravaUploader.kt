package de.syntaxfehler.ligpsport.strava

import android.content.Context
import android.util.Log
import de.syntaxfehler.ligpsport.ble.UploadPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * High-level "get this ride onto Strava" orchestration, in the spirit
 * of [UploadPipeline]: fetch the FIT off the BSC200 if it isn't already
 * cached, make sure the access token is live, POST, then poll until
 * Strava has actually turned the upload into an activity.
 */
object StravaUploader {
    private const val TAG = "StravaUploader"

    /**
     * Refresh well before expiry. The token has to outlast the POST of
     * the file and the whole [POLL_TIMEOUT_MS] poll, and a 401 halfway
     * through reads as a failed upload of a ride Strava already has.
     */
    private const val EXPIRY_MARGIN_SECONDS = 600L

    private const val POLL_INTERVAL_MS = 1_500L
    private const val POLL_TIMEOUT_MS = 90_000L

    /**
     * Serialises every refresh grant. Strava rotates the refresh token
     * on each one and rejects the superseded value, so two concurrent
     * refreshes from the same stored token (two rows uploading, or an
     * upload racing Test connection) can leave the older response
     * saved last and the account unusable until the token is re-pasted.
     */
    private val refreshLock = Mutex()

    sealed interface Result {
        /**
         * [activityId] is null only if Strava accepted the file but we
         * stopped polling. [mutedFromFeed] is false when the upload
         * landed but the follow-up mute call didn't — the ride is on
         * Strava and visible in the feed, which the user should know.
         */
        data class Success(
            val activityId: Long?,
            val fileName: String,
            val mutedFromFeed: Boolean = false,
        ) : Result

        data class Failure(val reason: String) : Result
    }

    /**
     * @param deviceMac the paired device holding the activity. Required,
     *   not defaulted to the first paired one: with two computers the
     *   same timestamp can exist on both, and guessing would upload the
     *   wrong ride.
     * @param timestamp the activity's device-side id (epoch seconds).
     * @param name optional activity title; Strava derives one from the
     *   start time and location when null.
     * @param muteFromFeed keep the upload out of followers' feeds so it
     *   raises no notifications. On by default. Note this is *not*
     *   privacy — see [StravaClient.muteActivity]; the account's default
     *   activity visibility is the only real control and the API cannot
     *   set it.
     */
    suspend fun upload(
        context: Context,
        deviceMac: String,
        timestamp: Long,
        name: String? = null,
        muteFromFeed: Boolean = true,
        clientFactory: (StravaStore.Credentials) -> StravaClient = ::StravaClient,
    ): Result {
        val store = StravaStore(context)
        val credentials = store.credentials()
            ?: return Result.Failure("no Strava credentials — add them in Settings → Strava")

        val fit = ensureFit(context, deviceMac, timestamp)
            ?: return Result.Failure("could not get the FIT off the device")

        val client = clientFactory(credentials)
        return try {
            val tokens = ensureFreshToken(store, client)
            val ticket = client.uploadFit(
                accessToken = tokens.accessToken,
                file = fit,
                name = name,
                // Deterministic per activity, so Strava's own duplicate
                // check rejects a second upload of the same ride rather
                // than creating a twin.
                externalId = fit.name,
            )
            ticket.error?.let { return Result.Failure(it) }
            val processed = awaitProcessing(client, tokens.accessToken, ticket, fit.name)
            // Mute after the fact: the activity id only exists once
            // Strava has finished processing, and there is no way to ask
            // for this at upload time.
            if (processed is Result.Success && processed.activityId != null && muteFromFeed) {
                val muted = runCatching {
                    client.muteActivity(tokens.accessToken, processed.activityId)
                }.isSuccess
                if (!muted) Log.w(TAG, "uploaded ${processed.activityId} but could not mute it")
                processed.copy(mutedFromFeed = muted)
            } else {
                processed
            }
        } catch (e: StravaClient.StravaException) {
            Result.Failure(e.message ?: "Strava error")
        } catch (e: Exception) {
            Log.w(TAG, "upload failed", e)
            Result.Failure("network error: ${e.message}")
        } finally {
            client.runCatching { close() }
        }
    }

    /** Outcome of [testConnection]. [athleteName] is null when Strava wouldn't say. */
    sealed interface TestResult {
        data class Ok(val athleteName: String?) : TestResult
        data class Failed(val reason: String) : TestResult
    }

    /**
     * Check the credentials in Settings by actually minting an access
     * token from them, and cache the result so the first real upload
     * doesn't pay for the round-trip again.
     *
     * A refresh grant is the right probe: it exercises all three
     * values, and it is the exact call every upload depends on. It is
     * also the only one that works regardless of scope — the athlete
     * lookup that follows is best-effort decoration.
     */
    suspend fun testConnection(
        context: Context,
        clientFactory: (StravaStore.Credentials) -> StravaClient = ::StravaClient,
    ): TestResult {
        val store = StravaStore(context)
        val credentials = store.credentials()
            ?: return TestResult.Failed("fill in all three fields first")
        val client = clientFactory(credentials)
        return try {
            val tokens = refreshLock.withLock {
                val refreshed = client.refresh(currentRefreshToken(store))
                // Saved before the athlete lookup: the old refresh token
                // is dead from here on, whatever that call does.
                store.saveTokens(refreshed)
                refreshed
            }
            val name = client.athleteName(tokens.accessToken)
            if (name != null) store.saveTokens(tokens.copy(athleteName = name))
            TestResult.Ok(name)
        } catch (e: StravaClient.StravaException) {
            TestResult.Failed(e.message ?: "Strava rejected the credentials")
        } catch (e: Exception) {
            Log.w(TAG, "connection test failed", e)
            TestResult.Failed("network error: ${e.message}")
        } finally {
            client.runCatching { close() }
        }
    }

    /**
     * Reuse an already-downloaded FIT, otherwise pull it over BLE. Same
     * cache-first rule the share button uses — an activity download runs
     * to tens of seconds, so paying for it twice is worth avoiding.
     */
    private suspend fun ensureFit(context: Context, deviceMac: String, timestamp: Long): File? {
        val cached = UploadPipeline.activityFitFile(context, deviceMac, timestamp)
        if (cached.isFile && cached.length() > 0L) return cached
        return when (val res = UploadPipeline.downloadActivity(context, timestamp, targetMac = deviceMac)) {
            is UploadPipeline.Result.Success ->
                res.activitySavedPath?.let(::File)?.takeIf { it.isFile } ?: cached.takeIf { it.isFile }
            is UploadPipeline.Result.Failure -> null
        }
    }

    /**
     * Reuse the cached access token while it is comfortably alive,
     * otherwise mint one from the refresh token.
     *
     * Strava can rotate the refresh token on this call, so the whole
     * response is persisted rather than just the access token —
     * dropping the rotated value would strand the account after the
     * six-hour access token expired.
     *
     * The cache check and the refresh token are both read under
     * [refreshLock], not taken from the credentials [upload] read at
     * the start: that was before a BLE fetch of tens of seconds, and
     * another upload may have rotated the token meanwhile — in which
     * case its fresh access token is simply reused.
     */
    internal suspend fun ensureFreshToken(
        store: StravaStore,
        client: StravaClient,
        nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    ): StravaStore.Tokens = refreshLock.withLock {
        val now = nowEpochSeconds()
        store.cachedTokens()
            ?.takeIf { it.expiresAtEpochSeconds > now + EXPIRY_MARGIN_SECONDS }
            ?.let { return@withLock it }
        val refreshed = client.refresh(currentRefreshToken(store))
        val merged = refreshed.copy(athleteName = refreshed.athleteName ?: store.athleteName())
        store.saveTokens(merged)
        merged
    }

    private fun currentRefreshToken(store: StravaStore): String =
        store.credentials()?.refreshToken
            ?: throw StravaClient.StravaException("no Strava credentials — add them in Settings → Strava")

    /**
     * Poll [ticket] until Strava reports an activity or a rejection.
     *
     * The upload has already been accepted by the time this runs, so a
     * failed poll (a 429, a dropped connection) is retried until the
     * deadline rather than reported as a failed upload — the user's
     * obvious retry would only trip Strava's duplicate check. The last
     * poll's answer is always inspected, even if it arrived after the
     * deadline.
     */
    internal suspend fun awaitProcessing(
        client: StravaClient,
        accessToken: String,
        ticket: StravaClient.UploadStatus,
        fileName: String,
        timeoutMs: Long = POLL_TIMEOUT_MS,
        intervalMs: Long = POLL_INTERVAL_MS,
    ): Result {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest: StravaClient.UploadStatus? = ticket
        while (true) {
            latest?.error?.let { return Result.Failure(it) }
            latest?.activityId?.let { return Result.Success(it, fileName) }
            if (System.currentTimeMillis() >= deadline) break
            delay(intervalMs)
            latest = try {
                client.pollUpload(accessToken, ticket.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "polling upload ${ticket.id} failed; retrying", e)
                null
            }
        }
        // Strava says mean processing is under 2 s; if we get here the
        // upload is probably fine but slow, so don't call it a failure.
        return Result.Success(activityId = null, fileName = fileName)
    }
}
