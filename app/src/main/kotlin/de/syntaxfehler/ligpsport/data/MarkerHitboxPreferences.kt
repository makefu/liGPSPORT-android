package de.syntaxfehler.ligpsport.data

import android.content.Context

/**
 * SharedPreferences-backed user override for the map-marker touch
 * hitbox size, in dp. Larger values make Start / Stop / Destination
 * markers easier to grab and drag; smaller values cede more of the
 * tap area back to the underlying map (handy if the user routinely
 * taps map tiles near their markers).
 *
 * Sensible bounds: 48 dp is the Material-recommended minimum touch
 * target; 120 dp is roughly half a phone width — wider than that
 * would start blocking the underlying map for any meaningful gesture.
 */
class MarkerHitboxPreferences(context: Context) {
    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): Int = prefs.getInt(KEY, DEFAULT_DP).coerceIn(MIN_DP, MAX_DP)

    fun set(sizeDp: Int) {
        prefs.edit().putInt(KEY, sizeDp.coerceIn(MIN_DP, MAX_DP)).apply()
    }

    companion object {
        const val MIN_DP = 48
        const val MAX_DP = 120
        const val DEFAULT_DP = 80
        private const val PREFS_NAME = "ligpsport.marker_hitbox"
        private const val KEY = "size_dp"
    }
}
