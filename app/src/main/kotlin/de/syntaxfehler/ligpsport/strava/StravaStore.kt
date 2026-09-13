package de.syntaxfehler.ligpsport.strava

import android.content.Context
import de.syntaxfehler.ligpsport.BuildConfig

/**
 * Persisted Strava credentials and token cache. Mirrors
 * [de.syntaxfehler.ligpsport.ble.DeviceStore] — plain
 * `SharedPreferences` in app-private storage, no encryption.
 *
 * That's a deliberate match to what this app already does with the
 * paired MAC, and the practical exposure is the same: app-private
 * prefs are readable only by this uid (or by anyone with root / an
 * unlocked bootloader). The stored refresh token grants
 * `activity:write` on the user's Strava account until revoked at
 * https://www.strava.com/settings/apps, so treat a rooted or shared
 * phone accordingly.
 *
 * Credentials are editable in Settings → Strava. A build that carries
 * them in `app/strava.properties` seeds those fields once on first run
 * and turns the feature on ([seedFromBuildConfig]); a build without
 * them starts switched off, and the user opts in by pasting their own.
 */
class StravaStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * What the user types into Settings → Strava, from
     * https://www.strava.com/settings/api.
     *
     * All three are needed because Strava's access tokens expire after
     * six hours and the only way to mint a new one is the
     * `refresh_token` grant, which requires the client id and secret.
     * A pasted access token on its own would stop working the same day.
     */
    data class Credentials(
        val clientId: String,
        val clientSecret: String,
        val refreshToken: String,
    )

    /**
     * The cached access token. [expiresAtEpochSeconds] is Strava's own
     * absolute expiry, not a duration we computed, so clock skew on our
     * side can't silently shorten it.
     *
     * [refreshToken] is here because Strava may rotate it on every
     * refresh — see [saveTokens].
     */
    data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtEpochSeconds: Long,
        val athleteName: String? = null,
    )

    // ---- Credentials --------------------------------------------------

    /** Null unless all three fields are filled in — partial input can only fail. */
    fun credentials(): Credentials? {
        val id = prefs.getString(KEY_CLIENT_ID, null)?.trim().orEmpty()
        val secret = prefs.getString(KEY_CLIENT_SECRET, null)?.trim().orEmpty()
        val refresh = prefs.getString(KEY_REFRESH, null)?.trim().orEmpty()
        if (id.isBlank() || secret.isBlank() || refresh.isBlank()) return null
        return Credentials(id, secret, refresh)
    }

    /**
     * Replace the credentials. The cached access token is dropped: it
     * belongs to whatever was configured before, and keeping it would
     * make the next upload succeed against the *old* account.
     */
    fun saveCredentials(clientId: String, clientSecret: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_CLIENT_ID, clientId.trim())
            .putString(KEY_CLIENT_SECRET, clientSecret.trim())
            .putString(KEY_REFRESH, refreshToken.trim())
            .remove(KEY_ACCESS)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_ATHLETE)
            .apply()
    }

    /** Raw field values for the Settings form — blank rather than null. */
    fun clientId(): String = prefs.getString(KEY_CLIENT_ID, null).orEmpty()

    fun clientSecret(): String = prefs.getString(KEY_CLIENT_SECRET, null).orEmpty()

    fun refreshToken(): String = prefs.getString(KEY_REFRESH, null).orEmpty()

    /**
     * Forget the credentials, the cached token and the enable flag.
     *
     * The [KEY_SEEDED] latch deliberately survives: without it a build
     * carrying credentials would put them straight back on the next
     * launch, which reads as the app ignoring the user.
     */
    fun clear() {
        prefs.edit()
            .clear()
            .putBoolean(KEY_SEEDED, true)
            .apply()
    }

    // ---- Enable flag --------------------------------------------------

    /**
     * Whether the Strava upload button should appear at all. Separate
     * from having credentials so the button can be turned off without
     * throwing the credentials away — and so that half-entered
     * credentials never surface a button that can only fail.
     */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false) && credentials() != null

    /** The raw flag, without the credentials check — for the Settings switch itself. */
    fun enabledFlag(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // ---- Access-token cache -------------------------------------------

    fun cachedTokens(): Tokens? {
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        return Tokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochSeconds = prefs.getLong(KEY_EXPIRES_AT, 0L),
            athleteName = prefs.getString(KEY_ATHLETE, null),
        )
    }

    /**
     * Persist a token response. This writes [KEY_REFRESH] too: Strava
     * rotates the refresh token on the refresh grant, so the value the
     * user originally pasted goes stale and the response's replacement
     * is the only one that will work next time.
     */
    fun saveTokens(tokens: Tokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putLong(KEY_EXPIRES_AT, tokens.expiresAtEpochSeconds)
            .apply {
                if (tokens.athleteName != null) putString(KEY_ATHLETE, tokens.athleteName)
            }
            .apply()
    }

    fun athleteName(): String? = prefs.getString(KEY_ATHLETE, null)

    /** When the cached access token expires, or 0 if there is none. */
    fun accessTokenExpiry(): Long = prefs.getLong(KEY_EXPIRES_AT, 0L)

    // ---- Build-time seeding -------------------------------------------

    /**
     * Copy any credentials baked into the build into the editable
     * settings, once, and switch the feature on if the build carried a
     * client id and secret.
     *
     * One-shot by design: without the [KEY_SEEDED] latch, clearing a
     * field in Settings would silently come back on the next launch,
     * and turning the feature off would not stick. Blank build values
     * are skipped rather than written, so a partially-configured build
     * still leaves the user's own entries alone.
     *
     * Called from `App.onCreate`.
     */
    fun seedFromBuildConfig() {
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        val id = BuildConfig.STRAVA_CLIENT_ID.trim()
        val secret = BuildConfig.STRAVA_CLIENT_SECRET.trim()
        val refresh = BuildConfig.STRAVA_REFRESH_TOKEN.trim()
        prefs.edit()
            .putBoolean(KEY_SEEDED, true)
            .apply {
                if (id.isNotBlank()) putString(KEY_CLIENT_ID, id)
                if (secret.isNotBlank()) putString(KEY_CLIENT_SECRET, secret)
                if (refresh.isNotBlank()) putString(KEY_REFRESH, refresh)
                // The refresh token can legitimately be absent here —
                // it is per-athlete, not per-application. Enabling on
                // the strength of the app credentials alone is still
                // right: `isEnabled` stays false until the token is
                // pasted, so nothing appears prematurely, and the user
                // does not have to find a switch afterwards.
                if (id.isNotBlank() && secret.isNotBlank()) putBoolean(KEY_ENABLED, true)
            }
            .apply()
    }

    private companion object {
        const val PREFS = "ligpsport.strava"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_CLIENT_SECRET = "client_secret"
        const val KEY_ENABLED = "enabled"
        const val KEY_SEEDED = "seeded_from_build"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_ATHLETE = "athlete_name"
    }
}
