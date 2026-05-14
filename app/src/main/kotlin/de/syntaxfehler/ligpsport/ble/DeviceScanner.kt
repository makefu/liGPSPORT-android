package de.syntaxfehler.ligpsport.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Coroutine wrapper around [BluetoothLeScanner]. Emits devices whose
 * advertising name matches one of the [GattUuids.NAME_PREFIXES]; the
 * caller must hold the BLUETOOTH_SCAN runtime permission.
 */
class DeviceScanner(private val adapter: BluetoothAdapter?) {
    @RequiresPermission(allOf = ["android.permission.BLUETOOTH_SCAN"])
    fun scan(): Flow<BluetoothDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
            ?: run { close(); return@callbackFlow }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val dev = result?.device ?: return
                val name = dev.name ?: return
                if (GattUuids.NAME_PREFIXES.any { name.startsWith(it) }) {
                    trySend(dev)
                }
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }
}
