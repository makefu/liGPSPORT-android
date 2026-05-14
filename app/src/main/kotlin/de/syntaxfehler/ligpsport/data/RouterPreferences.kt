package de.syntaxfehler.ligpsport.data

import android.content.Context
import de.syntaxfehler.ligpsport.route.RouterRegistry

/**
 * Persisted choice of the active [de.syntaxfehler.ligpsport.route.RouteProvider].
 * Stored as a single string id in a dedicated SharedPreferences file
 * so it doesn't share a namespace with `osmdroid`'s prefs.
 */
class RouterPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(): String {
        val stored = prefs.getString(KEY_ROUTER_ID, null)
        return when {
            stored != null && RouterRegistry.byId(stored) != null -> stored
            else -> RouterRegistry.default.id
        }
    }

    fun set(id: String) {
        prefs.edit().putString(KEY_ROUTER_ID, id).apply()
    }

    companion object {
        private const val PREFS = "ligpsport.routing"
        private const val KEY_ROUTER_ID = "router_id"
    }
}
