package de.syntaxfehler.ligpsport.ble

import java.util.UUID

/**
 * GATT service / characteristic UUIDs for the iGPSPORT BLE protocol —
 * Kotlin port of ligpsport/gatt.py. The trailing nibble of each UUID
 * identifies the channel: `8e` Primary (Control), `9e` Data,
 * `7e` Third, `6e` Fourth. The BSC200 routes route-plan uploads on
 * the Fourth channel (`6e`); see PROTOCOL.md §7.1.2.
 */
object GattUuids {
    val PRIMARY_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca8e")
    val PRIMARY_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca8e")
    val PRIMARY_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca8e")

    val DATA_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val DATA_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val DATA_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    val THIRD_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca7e")
    val THIRD_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca7e")
    val THIRD_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca7e")

    val FOURTH_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca6e")
    val FOURTH_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca6e")
    val FOURTH_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca6e")

    val NAME_PREFIXES: List<String> = listOf("BSC", "iGS", "iGPSPORT")
}

enum class Channel { CONTROL, DATA, THIRD, FOURTH }
