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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import de.syntaxfehler.ligpsport.agps.AgpsClient
import de.syntaxfehler.ligpsport.ble.UploadPipeline
import de.syntaxfehler.ligpsport.data.AgpsSeedStore
import de.syntaxfehler.ligpsport.data.AgpsTokenStore
import de.syntaxfehler.ligpsport.data.MarkerHitboxPreferences
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import de.syntaxfehler.ligpsport.ble.BleSessionManager
import de.syntaxfehler.ligpsport.ble.DeviceStore
import de.syntaxfehler.ligpsport.data.RouterPreferences
import de.syntaxfehler.ligpsport.route.RouteProvider
import de.syntaxfehler.ligpsport.route.RouterRegistry
import de.syntaxfehler.ligpsport.strava.StravaStore
import de.syntaxfehler.ligpsport.strava.StravaUploader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenRoutes: (mac: String) -> Unit = {},
    onOpenActivities: (mac: String) -> Unit = {},
    onOpenPreviousRides: () -> Unit = {},
    onOpenMenu: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val screenScope = rememberCoroutineScope()
    val routerPrefs = remember { RouterPreferences(ctx) }
    var selected by remember { mutableStateOf(routerPrefs.get()) }

    val deviceStore = remember { DeviceStore(ctx) }
    var paired by remember { mutableStateOf(deviceStore.list()) }
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
                paired = deviceStore.list()
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
                    if (onOpenMenu != null) {
                        IconButton(onClick = onOpenMenu, modifier = Modifier.testTag("open_menu")) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
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
            // --- Paired devices -------------------------------------
            item {
                SectionLabel("Paired devices (${paired.size} / ${DeviceStore.MAX_DEVICES})")
            }
            if (paired.isEmpty()) {
                item {
                    EmptyPairingCard(onPair = onOpenPairing)
                }
            } else {
                items(items = paired, key = { it.mac }) { device ->
                    PairedDeviceCard(
                        device = device,
                        onOpenRoutes = { onOpenRoutes(device.mac) },
                        onOpenActivities = { onOpenActivities(device.mac) },
                        onForget = {
                            deviceStore.remove(device.mac)
                            paired = deviceStore.list()
                            // Drop the warm connection too, otherwise the
                            // session manager keeps the radio open to a
                            // device the user just told us to forget.
                            screenScope.launch { BleSessionManager.forget(device.mac) }
                        },
                        onRename = { newName ->
                            deviceStore.setNickname(device.mac, newName)
                            paired = deviceStore.list()
                        },
                    )
                }
                item {
                    AddDeviceButton(
                        enabled = paired.size < DeviceStore.MAX_DEVICES,
                        onClick = onOpenPairing,
                    )
                }
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

            // --- Previous rides -------------------------------------
            item { SectionLabel("Previous rides") }
            item { PreviousRidesRow(onClick = onOpenPreviousRides) }

            // --- Strava ---------------------------------------------
            item { StravaSection() }

            // --- Map markers ----------------------------------------
            item { MarkerHitboxSection() }

            // --- AGPS token (kept at the bottom — advanced) ---------
            item { AgpsTokenSection() }
        }
    }
}

/**
 * AGPS-token section. Lets users supply their own u-blox AssistNow
 * token so the BSC200 hot-starts its GNSS chip from app-side
 * assistance data instead of doing a 30–90 s cold-start search.
 *
 * UI surfaces a single binary state — *Custom token set* vs *No
 * custom token* — and never shows the value itself. When no custom
 * token is configured, the app still seeds AGPS using a default
 * token resolved at runtime, so the feature works out of the box.
 *
 * Tap → dialog with a masked text field plus Test / Save / Cancel
 * (and Remove when a custom token is currently set). Test fires a
 * real request against AssistNow Online and reports bytes received
 * or the error inline.
 */
@Composable
private fun AgpsTokenSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AgpsTokenStore(ctx) }
    var userSet by remember { mutableStateOf(store.isSet()) }
    var dialogOpen by remember { mutableStateOf(false) }

    val sourceLabel = if (userSet) "Custom token set" else "No custom token"
    val description =
        if (userSet) {
            "Using your u-blox AssistNow token. Tap to change or remove."
        } else {
            "AGPS speeds up GPS fix on your bike computer. A default " +
                "token is used automatically — tap to supply your own " +
                "from u-blox AssistNow."
        }

    Column {
        SectionLabel("AGPS token")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clickable { dialogOpen = true }
                .testTag("agps_token_card"),
        ) {
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Key, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(
                        sourceLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (dialogOpen) {
        AgpsTokenDialog(
            currentlySet = userSet,
            onDismiss = { dialogOpen = false },
            onSave = { token ->
                store.set(token)
                userSet = true
                dialogOpen = false
            },
            onClear = {
                store.clear()
                userSet = false
                dialogOpen = false
            },
            onTest = { tokenToTest ->
                // Fire a real GetOnlineData.ashx request and return a
                // human-readable result for the caller to surface.
                val client = AgpsClient()
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        client.fetchOnline(tokenToTest.takeIf { it.isNotBlank() })
                    }
                    TestResult.Ok(bytes.size)
                } catch (e: Exception) {
                    TestResult.Fail(e.message ?: e.javaClass.simpleName)
                } finally {
                    client.runCatching { close() }
                }
            },
            scope = scope,
        )
    }
}

private sealed interface TestResult {
    data class Ok(val bytes: Int) : TestResult
    data class Fail(val reason: String) : TestResult
}

@Composable
private fun AgpsTokenDialog(
    currentlySet: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onTest: suspend (String) -> TestResult,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var input by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var lastTest by remember { mutableStateOf<TestResult?>(null) }

    AlertDialog(
        onDismissRequest = { if (!testing) onDismiss() },
        title = { Text(if (currentlySet) "Change AGPS token" else "Set AGPS token") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (currentlySet) {
                        "Enter a new token to replace the one you saved, or " +
                            "tap Remove to fall back to the default."
                    } else {
                        "Paste your u-blox AssistNow token below. " +
                            "Tap Test to check it works."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Token") },
                    placeholder = { Text("paste token here") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("agps_token_input"),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        enabled = !testing,
                        onClick = {
                            testing = true
                            lastTest = null
                            scope.launch {
                                val r = onTest(input)
                                lastTest = r
                                testing = false
                            }
                        },
                        modifier = Modifier.testTag("agps_token_test"),
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("  Testing…")
                        } else {
                            Text("Test")
                        }
                    }
                    val result = lastTest
                    if (result != null) {
                        when (result) {
                            is TestResult.Ok -> Text(
                                "OK (${result.bytes} B)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            is TestResult.Fail -> Text(
                                "Failed: ${result.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !testing && input.isNotBlank(),
                onClick = { onSave(input) },
                modifier = Modifier.testTag("agps_token_save"),
            ) { Text("Save") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (currentlySet) {
                    TextButton(
                        enabled = !testing,
                        onClick = onClear,
                        modifier = Modifier.testTag("agps_token_remove"),
                    ) { Text("Remove") }
                }
                TextButton(enabled = !testing, onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
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
    device: DeviceStore.Paired,
    onOpenRoutes: () -> Unit,
    onOpenActivities: () -> Unit,
    onForget: () -> Unit,
    onRename: (newName: String?) -> Unit,
) {
    var renameOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag("paired_device_card_${device.mac}"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        device.name ?: "(unnamed)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        device.mac,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { renameOpen = true },
                    modifier = Modifier.testTag("rename_${device.mac}"),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename device")
                }
                IconButton(
                    onClick = onForget,
                    modifier = Modifier.testTag("forget_${device.mac}"),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Forget device")
                }
            }
            DeviceSubScreenRow(
                title = "Routes on device",
                subtitle = "List, delete or wipe uploaded routes.",
                onClick = onOpenRoutes,
                testTag = "open_device_routes_${device.mac}",
            )
            DeviceSubScreenRow(
                title = "Activities on device",
                subtitle = "Download (FIT) or delete recorded rides.",
                onClick = onOpenActivities,
                testTag = "open_device_activities_${device.mac}",
            )
            AgpsSeedRow(mac = device.mac)
        }
    }
    if (renameOpen) {
        RenameDeviceDialog(
            currentName = device.name,
            macForLabel = device.mac,
            onDismiss = { renameOpen = false },
            onConfirm = { newName ->
                onRename(newName)
                renameOpen = false
            },
        )
    }
}

/**
 * Single-field text dialog for setting a user-defined nickname on a
 * paired BSC200. Saving a blank value clears the nickname and falls
 * the displayed label back to the BLE-advertised name.
 */
@Composable
private fun RenameDeviceDialog(
    currentName: String?,
    macForLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (newName: String?) -> Unit,
) {
    var text by remember { mutableStateOf(currentName.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    macForLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Nickname") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_field_$macForLabel"),
                )
                Text(
                    "Leave empty to restore the device's advertised name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim().ifBlank { null }) },
                modifier = Modifier.testTag("rename_save_$macForLabel"),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Per-device AGPS seed state plus the manual override.
 *
 * Uploads only re-seed when the stored assistance data has expired
 * ([AgpsSeedStore]), which makes an otherwise invisible decision worth
 * showing: without it, "why did this upload take 20 s and the next one
 * 3 s?" has no answer in the UI. "Seed now" forces a fetch and push
 * regardless of freshness — the escape hatch for a device that still
 * refuses to get a fix.
 */
@Composable
private fun AgpsSeedRow(mac: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AgpsSeedStore(ctx) }
    var seed by remember(mac) { mutableStateOf(store.get(mac)) }
    var busy by remember(mac) { mutableStateOf(false) }
    var status by remember(mac) { mutableStateOf<String?>(null) }

    // Re-tick so "12 minutes ago" doesn't freeze while the screen sits
    // open; the labels are otherwise only recomposed on state changes.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(mac) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agps_seed_row_$mac"),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "GPS assistance (AGPS)",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Last seeded: ${AgpsSeedLabels.lastSeeded(seed?.seededAt, now)} · " +
                        AgpsSeedLabels.validity(seed?.seededAt, now, store.ttlMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("agps_seed_state_$mac"),
                )
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                OutlinedButton(
                    onClick = {
                        busy = true
                        status = null
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                UploadPipeline.seedAgps(ctx, targetMac = mac)
                            }
                            status = when (res) {
                                is UploadPipeline.Result.Success ->
                                    "Seeded ${res.agpsBytes ?: 0} bytes."
                                is UploadPipeline.Result.Failure -> res.reason
                            }
                            seed = store.get(mac)
                            now = System.currentTimeMillis()
                            busy = false
                        }
                    },
                    modifier = Modifier.testTag("agps_seed_now_$mac"),
                ) { Text("Seed now") }
            }
        }
    }
}

@Composable
private fun DeviceSubScreenRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyPairingCard(onPair: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag("paired_device_card_empty"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.BluetoothDisabled,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "No devices paired",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Scan for up to ${DeviceStore.MAX_DEVICES} iGPSPORT cycling computers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FilledTonalButton(
                onClick = onPair,
                modifier = Modifier.testTag("pair_button"),
            ) { Text("Pair a device") }
        }
    }
}

@Composable
private fun PreviousRidesRow(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable(onClick = onClick)
            .testTag("previous_rides_row"),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Route,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Previous rides",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun AddDeviceButton(enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag("pair_button"),
    ) {
        Text(if (enabled) "Add another device" else "Device limit reached")
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

/**
 * Strava credentials and the switch that reveals the upload button on
 * each recorded activity.
 *
 * Always rendered. A build with credentials in `app/strava.properties`
 * arrives here pre-filled and already switched on
 * ([StravaStore.seedFromBuildConfig]); everyone else opts in by pasting
 * their own from https://www.strava.com/settings/api.
 *
 * All three values are required because Strava's access tokens die
 * after six hours and only the `refresh_token` grant — which needs the
 * client id and secret — can mint a new one. See docs/STRAVA.md.
 *
 * testTags: `strava_card`, `strava_enable`, `strava_client_id`,
 * `strava_client_secret`, `strava_refresh_token`, `strava_save`,
 * `strava_test`, `strava_forget`.
 */
@Composable
private fun StravaSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { StravaStore(ctx) }

    var enabled by remember { mutableStateOf(store.enabledFlag()) }
    var clientId by remember { mutableStateOf(store.clientId()) }
    var clientSecret by remember { mutableStateOf(store.clientSecret()) }
    var refreshToken by remember { mutableStateOf(store.refreshToken()) }
    var reveal by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    // What's on screen vs. what an upload would actually use. Editing a
    // field without saving is the easy way to "test" stale credentials
    // and be baffled by the result, so the buttons key off this.
    val dirty = clientId != store.clientId() ||
        clientSecret != store.clientSecret() ||
        refreshToken != store.refreshToken()
    val complete = listOf(clientId, clientSecret, refreshToken).none { it.isBlank() }

    fun save() {
        store.saveCredentials(clientId, clientSecret, refreshToken)
        // Mirror the store's trimming back into the fields, otherwise a
        // pasted value with a stray space leaves `dirty` stuck true and
        // Save looking like it did nothing.
        clientId = store.clientId()
        clientSecret = store.clientSecret()
        refreshToken = store.refreshToken()
        // The cached access token belonged to the previous credentials,
        // so any "connected as …" claim is stale from here on.
        status = null
    }

    Column {
        SectionLabel("Strava")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .testTag("strava_card"),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Send activities to Strava",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Adds an upload button to each row under " +
                                "Activities on device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            store.setEnabled(it)
                        },
                        modifier = Modifier.testTag("strava_enable"),
                    )
                }

                if (enabled) {
                    // Only nag once the switch is on — an untouched
                    // section shouldn't shout about missing fields.
                    if (!complete) {
                        Text(
                            "Fill in all three values from " +
                                "strava.com/settings/api. The upload button " +
                                "stays hidden until then.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it },
                        singleLine = true,
                        label = { Text("Client ID") },
                        // Strava client ids are plain integers.
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            autoCorrectEnabled = false,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("strava_client_id"),
                    )
                    SecretField(
                        value = clientSecret,
                        onValueChange = { clientSecret = it },
                        label = "Client secret",
                        reveal = reveal,
                        testTag = "strava_client_secret",
                    )
                    SecretField(
                        value = refreshToken,
                        onValueChange = { refreshToken = it },
                        label = "Refresh token",
                        reveal = reveal,
                        testTag = "strava_refresh_token",
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { reveal = !reveal }) {
                            Text(if (reveal) "Hide" else "Show")
                        }
                        FilledTonalButton(
                            onClick = ::save,
                            enabled = dirty,
                            modifier = Modifier.testTag("strava_save"),
                        ) { Text("Save") }
                        OutlinedButton(
                            onClick = {
                                if (dirty) save()
                                testing = true
                                status = null
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        StravaUploader.testConnection(ctx)
                                    }
                                    testing = false
                                    // A successful test rotated the refresh
                                    // token in the store. Leaving the pasted
                                    // one in the field would mark the form
                                    // dirty, and the next Save would write
                                    // the superseded token back.
                                    refreshToken = store.refreshToken()
                                    status = when (res) {
                                        is StravaUploader.TestResult.Ok ->
                                            (
                                                res.athleteName?.let { "Connected as $it" }
                                                    ?: "Credentials accepted"
                                            ) to true
                                        is StravaUploader.TestResult.Failed ->
                                            "Failed: ${res.reason}" to false
                                    }
                                }
                            },
                            enabled = complete && !testing,
                            modifier = Modifier.testTag("strava_test"),
                        ) {
                            if (testing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("Test connection")
                            }
                        }
                    }
                    status?.let { (message, ok) ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    Text(
                        "Uploads are muted from followers' feeds, which is not " +
                            "the same as private — set your default activity " +
                            "visibility on Strava. Credentials are stored " +
                            "unencrypted in app-private storage; revoke access " +
                            "at strava.com/settings/apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            store.clear()
                            clientId = ""
                            clientSecret = ""
                            refreshToken = ""
                            enabled = false
                            status = null
                        },
                        modifier = Modifier.testTag("strava_forget"),
                    ) { Text("Forget credentials") }
                }
            }
        }
    }
}

/**
 * Masked credential input with a shared reveal toggle. Masking is
 * shoulder-surfing hygiene, not storage security — these land in plain
 * `SharedPreferences` either way.
 */
@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    reveal: Boolean,
    testTag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        visualTransformation = if (reveal) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        // Opaque tokens: IME suggestions and autocorrect can only
        // mangle them, and a password keyboard keeps them out of the
        // IME's learned words.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth().testTag(testTag),
    )
}

/**
 * Configurable size of the touch hit-area around each draggable map
 * marker (Start / Stop / Destination). Slider with discrete steps in
 * the safe range. Persisted via [MarkerHitboxPreferences]; MapScreen
 * re-reads on resume and re-renders all markers.
 */
@Composable
private fun MarkerHitboxSection() {
    val ctx = LocalContext.current
    val prefs = remember { MarkerHitboxPreferences(ctx) }
    var size by remember { mutableStateOf(prefs.get()) }
    Column {
        SectionLabel("Map markers")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .testTag("marker_hitbox_card"),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Touch area: $size dp",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Larger values make Start / Stop / Destination pins easier " +
                        "to grab and drag; smaller values leave more of the map " +
                        "underneath responsive to taps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = size.toFloat(),
                    onValueChange = { size = it.toInt() },
                    onValueChangeFinished = { prefs.set(size) },
                    valueRange = MarkerHitboxPreferences.MIN_DP.toFloat()..MarkerHitboxPreferences.MAX_DP.toFloat(),
                    steps = (MarkerHitboxPreferences.MAX_DP - MarkerHitboxPreferences.MIN_DP) / 4 - 1,
                    modifier = Modifier.testTag("marker_hitbox_slider"),
                )
            }
        }
    }
}
