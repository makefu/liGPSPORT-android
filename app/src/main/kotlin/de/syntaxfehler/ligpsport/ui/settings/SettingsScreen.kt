package de.syntaxfehler.ligpsport.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import de.syntaxfehler.ligpsport.ble.DeviceStore
import de.syntaxfehler.ligpsport.data.RouterPreferences
import de.syntaxfehler.ligpsport.route.RouteProvider
import de.syntaxfehler.ligpsport.route.RouterRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPairing: () -> Unit,
) {
    val ctx = LocalContext.current
    val routerPrefs = remember { RouterPreferences(ctx) }
    var selected by remember { mutableStateOf(routerPrefs.get()) }

    val deviceStore = remember { DeviceStore(ctx) }
    var pairedName by remember { mutableStateOf(deviceStore.name()) }
    var pairedMac by remember { mutableStateOf(deviceStore.address()) }
    // Re-read on each resume so changes from PairingScreen show up
    // immediately after popping back to this screen.
    // `LocalLifecycleOwner` moved to lifecycle-runtime-compose; the
    // compose-ui shim still works and avoids pulling in another
    // dependency just for one observer.
    @Suppress("DEPRECATION")
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pairedName = deviceStore.name()
                pairedMac = deviceStore.address()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize().testTag("settings_list"),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // --- Paired device --------------------------------------
            item {
                SectionLabel("Paired device")
            }
            item {
                PairedDeviceCard(
                    name = pairedName,
                    mac = pairedMac,
                    onPair = onOpenPairing,
                    onForget = {
                        deviceStore.clear()
                        pairedName = null
                        pairedMac = null
                    },
                )
            }

            // --- Routing method -------------------------------------
            item { SectionLabel("Routing method") }
            items(RouterRegistry.all, key = { it.id }) { p ->
                RouterRow(
                    provider = p,
                    selected = p.id == selected,
                    onSelect = {
                        selected = p.id
                        routerPrefs.set(p.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PairedDeviceCard(
    name: String?,
    mac: String?,
    onPair: () -> Unit,
    onForget: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag("paired_device_card"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (mac == null) Icons.Default.BluetoothDisabled else Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = if (mac == null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        name ?: if (mac != null) "(unnamed)" else "Not paired",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (mac != null) {
                        Text(
                            mac,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "Scan for an iGPSPORT cycling computer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onPair,
                    modifier = Modifier.testTag("pair_button"),
                ) {
                    Text(if (mac == null) "Pair a device" else "Re-pair")
                }
                if (mac != null) {
                    OutlinedButton(
                        onClick = onForget,
                        modifier = Modifier.testTag("forget_button"),
                    ) {
                        Text("Forget")
                    }
                }
            }
        }
    }
}

@Composable
private fun RouterRow(
    provider: RouteProvider,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable(onClick = onSelect)
            .testTag("router_${provider.id}"),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        provider.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Chip(text = if (provider.isOffline) "offline" else "online")
                }
                Text(
                    provider.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}
