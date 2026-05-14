package de.syntaxfehler.ligpsport.ble

import android.content.Context

/**
 * Persistent record of the iGPSPORT device the user paired with. We
 * deliberately don't lean on Android's system pairing — the BSC200
 * advertises bondable but the protocol works fine without bonding;
 * remembering the MAC and reconnecting by address gives us the same
 * UX without the bond-handshake quirks reported on Pixel 7 / Android 14.
 */
class DeviceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(name: String?, address: String) {
        prefs.edit().putString(KEY_ADDRESS, address).putString(KEY_NAME, name).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
    }

    fun address(): String? = prefs.getString(KEY_ADDRESS, null)
    fun name(): String? = prefs.getString(KEY_NAME, null)

    companion object {
        private const val PREFS = "ligpsport.paired_device"
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
    }
}
