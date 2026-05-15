package de.syntaxfehler.ligpsport.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.syntaxfehler.ligpsport.ble.DeviceStore
import de.syntaxfehler.ligpsport.ble.FileTransfer
import de.syntaxfehler.ligpsport.ble.UploadPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stand-alone "Routes on device" sub-screen — the same UI that used
 * to be inlined into [SettingsScreen]'s lazy list, lifted out so the
 * main Settings page stays compact. Same testTags
 * (`refresh_routes`, `delete_all_routes`, `route_<id>`,
 * `delete_route_<id>`, `confirm_delete_all`) so the existing
 * instrumented + adb tests keep working.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceRoutesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val paired = remember { DeviceStore(ctx).address() != null }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Routes on device") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = ::refresh,
                        enabled = paired && !loading,
                        modifier = Modifier.testTag("refresh_routes"),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize().testTag("routes_list"),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!paired) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text(
                            "Pair a device to see its uploaded routes.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                return@LazyColumn
            }
            if (loading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Loading routes…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                return@LazyColumn
            }
            error?.let { msg ->
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text(
                            msg,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                return@LazyColumn
            }
            if (routes.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text(
                            "No routes uploaded.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                items(routes.size) { idx ->
                    RouteRow(entry = routes[idx], onDelete = { pendingDelete = routes[idx] })
                }
                item {
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

