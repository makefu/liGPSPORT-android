package de.syntaxfehler.ligpsport.ui.pairing

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.syntaxfehler.ligpsport.ble.DeviceScanner
import de.syntaxfehler.ligpsport.ble.DeviceStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * BLE device picker: requests the runtime permissions iGPSPORT needs,
 * scans for advertising packets matching the known name prefixes
 * (BSC, iGS, iGPSPORT), and saves the user's choice as the "paired
 * device" for subsequent uploads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(onPaired: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { DeviceStore(ctx) }
    val devices = remember { mutableStateMapOf<String, ScanEntry>() }
    var scanning by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var selectedAddress by remember { mutableStateOf(store.address()) }

    val requiredPermissions: Array<String> = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    var permissionsGranted by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionsGranted = result.values.all { it }
        if (!permissionsGranted) statusMessage = "Bluetooth permission denied."
    }
    LaunchedEffect(permissionsGranted) {
        if (!permissionsGranted) permissionLauncher.launch(requiredPermissions)
    }

    val scope = rememberCoroutineScope()
    val scanner = remember {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        DeviceScanner(bm?.adapter)
    }
    val startScan: () -> Unit = {
        if (!permissionsGranted) {
            statusMessage = "Grant Bluetooth permissions first."
        } else {
            scanning = true
            devices.clear()
            scope.launch {
                try {
                    @Suppress("MissingPermission")
                    scanner.scan().collectLatest { dev ->
                        @Suppress("MissingPermission")
                        val name = try { dev.name } catch (_: SecurityException) { null }
                        devices[dev.address] = ScanEntry(dev, name)
                    }
                } catch (e: Exception) {
                    statusMessage = "Scan failed: ${e.message}"
                } finally {
                    scanning = false
                }
            }
        }
    }
    LaunchedEffect(permissionsGranted) { if (permissionsGranted) startScan() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair your iGPSPORT") },
                actions = {
                    IconButton(onClick = startScan, enabled = !scanning) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (scanning) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Scanning for BSC / iGS / iGPSPORT devices…")
                }
            } else {
                Text(
                    "Make sure your computer is awake and within range.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            statusMessage?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("paired_list"),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(
                    items = devices.values.sortedBy { it.name ?: it.device.address }.toList(),
                    key = { it.device.address },
                ) { entry ->
                    DeviceRow(
                        entry = entry,
                        selected = entry.device.address == selectedAddress,
                        onClick = {
                            store.save(name = entry.name, address = entry.device.address)
                            selectedAddress = entry.device.address
                            // Pop back to wherever the user came from
                            // immediately — no manual confirmation
                            // needed once they've made a pick.
                            onPaired()
                        },
                    )
                }
            }
        }
    }
}

private data class ScanEntry(val device: BluetoothDevice, val name: String?)

@Composable
private fun DeviceRow(entry: ScanEntry, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick)
            .testTag("device_${entry.device.address}"),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Default.Bluetooth,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.fillMaxWidth()) {
                Text(
                    entry.name ?: "(unnamed)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    entry.device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

