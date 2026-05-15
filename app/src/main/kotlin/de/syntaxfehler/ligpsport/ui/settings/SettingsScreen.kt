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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import de.syntaxfehler.ligpsport.agps.AgpsClient
import de.syntaxfehler.ligpsport.ble.FileTransfer
import de.syntaxfehler.ligpsport.ble.UploadPipeline
import de.syntaxfehler.ligpsport.data.AgpsTokenStore
import de.syntaxfehler.ligpsport.data.MarkerHitboxPreferences
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

            // --- Routes on device -----------------------------------
            item { DeviceRoutesSection(paired = pairedMac != null) }

            // --- Map markers ----------------------------------------
            item { MarkerHitboxSection() }

            // --- AGPS token (kept at the bottom — advanced) ---------
            item { AgpsTokenSection() }
        }
    }
}

@Composable
private fun DeviceRoutesSection(paired: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var routes by remember { mutableStateOf<List<FileTransfer.RouteEntry>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<FileTransfer.RouteEntry?>(null) }
    var pendingDeleting by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var deletingAll by remember { mutableStateOf(false) }

    fun refresh() {
        if (!paired) return
        loading = true; error = null
        scope.launch {
            val res = withContext(Dispatchers.IO) { UploadPipeline.listRoutes(ctx) }
            when (res) {
                is UploadPipeline.Result.Success -> routes = res.routes
                is UploadPipeline.Result.Failure -> error = res.reason
            }
            loading = false
        }
    }

    LaunchedEffect(paired) { if (paired) refresh() }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Routes on device",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = ::refresh,
                enabled = paired && !loading,
                modifier = Modifier.testTag("refresh_routes"),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }
        if (!paired) {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(
                    "Pair a device to see its uploaded routes.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }
        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Loading routes…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }
        error?.let {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Text(
                    it,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            return@Column
        }
        if (routes.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(
                    "No routes uploaded.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in routes) {
                    RouteRow(
                        entry = r,
                        onDelete = { pendingDelete = r },
                    )
                }
                OutlinedButton(
                    onClick = { confirmDeleteAll = true },
                    enabled = !deletingAll && !loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .testTag("delete_all_routes"),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text("  Delete all routes")
                }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { if (!deletingAll) confirmDeleteAll = false },
            title = { Text("Delete all routes?") },
            text = {
                Column {
                    Text(
                        "This removes every inactive route from the BSC200.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (routes.any { it.status == FileTransfer.ROUTE_PLAN_FILE_STATUS_USED }) {
                        Text(
                            "The active navigation route is firmware-protected and will " +
                                "stay on the device — stop navigation on the BSC200 first " +
                                "if you want to remove it too.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deletingAll,
                    onClick = {
                        deletingAll = true
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                UploadPipeline.deleteAllRoutes(ctx)
                            }
                            deletingAll = false
                            confirmDeleteAll = false
                            when (res) {
                                is UploadPipeline.Result.Success -> refresh()
                                is UploadPipeline.Result.Failure -> error = res.reason
                            }
                        }
                    },
                    modifier = Modifier.testTag("confirm_delete_all"),
                ) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(
                    enabled = !deletingAll,
                    onClick = { confirmDeleteAll = false },
                ) { Text("Cancel") }
            },
        )
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { if (!pendingDeleting) pendingDelete = null },
            title = { Text("Delete route?") },
            text = {
                Column {
                    Text(
                        target.name.ifEmpty { "id=${target.id}" },
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (target.status == FileTransfer.ROUTE_PLAN_FILE_STATUS_USED) {
                        Text(
                            "The active navigation route is firmware-protected — " +
                                "the device may report success but keep the route. " +
                                "Stop navigation on the device first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !pendingDeleting,
                    onClick = {
                        pendingDeleting = true
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                UploadPipeline.deleteRouteById(
                                    ctx,
                                    fileId = target.id,
                                    name = target.name.ifEmpty { target.id.toString() },
                                    fileExtension = when (target.fileType) {
                                        2 -> "gpx"
                                        3 -> "fit"
                                        else -> "cnx"
                                    },
                                )
                            }
                            pendingDeleting = false
                            pendingDelete = null
                            when (res) {
                                is UploadPipeline.Result.Success -> refresh()
                                is UploadPipeline.Result.Failure -> error = res.reason
                            }
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    enabled = !pendingDeleting,
                    onClick = { pendingDelete = null },
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RouteRow(
    entry: FileTransfer.RouteEntry,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag("route_${entry.id}"),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.status == FileTransfer.ROUTE_PLAN_FILE_STATUS_USED) {
                Icon(
                    Icons.Filled.Navigation,
                    contentDescription = "Active route",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name.ifEmpty { "(unnamed)" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "id=${entry.id}" +
                        (if (entry.totalDistanceM > 0) " • ${entry.totalDistanceM} m" else "") +
                        (if (entry.status == FileTransfer.ROUTE_PLAN_FILE_STATUS_USED) " • active" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_route_${entry.id}"),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete route")
            }
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
