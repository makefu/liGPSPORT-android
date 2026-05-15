package de.syntaxfehler.ligpsport.data

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed override for the u-blox AssistNow token.
 *
 * Token resolution order at upload time (see
 * `UploadPipeline.uploadAgpsBestEffort`):
 *   1. [AgpsTokenStore.get] — runtime override set from Settings.
 *   2. `BuildConfig.AGPS_TOKEN` — build-time injection via
 *      `app/agps.properties` / `LIGPSPORT_AGPS_TOKEN`.
 *   3. iGPSport's prod config endpoint — same fallback the official
 *      app uses; no manual provisioning required.
 *
 * The stored value is never surfaced to the UI — the Settings entry
 * only reports "set" vs "unset". A `clear()` reverts to the build-
 * time / backend fallback. Settings file is process-local; no
 * encrypted store (the iGPSPORT app keeps theirs in plain
 * SharedPreferences too) — if the threat model requires more,
 * EncryptedSharedPreferences from `androidx.security:security-crypto`
 * is a drop-in upgrade.
 */
class AgpsTokenStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun isSet(): Boolean = !get().isNullOrBlank()

    fun set(token: String) {
        prefs.edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    companion object {
        private const val PREFS_NAME = "ligpsport.agps_token"
        private const val KEY_TOKEN = "token"
    }
}
