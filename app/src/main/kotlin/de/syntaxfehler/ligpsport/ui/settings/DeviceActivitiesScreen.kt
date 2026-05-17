package de.syntaxfehler.ligpsport.ui.settings

import android.content.Intent
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.core.content.FileProvider
import de.syntaxfehler.ligpsport.ble.DeviceStore
import de.syntaxfehler.ligpsport.ble.FileTransfer
import de.syntaxfehler.ligpsport.ble.UploadPipeline
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * "Activities on device" sub-screen — list / download (FIT) / delete
 * recorded activities from the BSC200. Mirrors [DeviceRoutesScreen].
 *
 * testTags (stable, used by instrumented tests + adb harness):
 *   - `refresh_activities`
 *   - `activity_<timestamp>`
 *   - `download_activity_<timestamp>` / `delete_activity_<timestamp>`
 *   - `delete_all_activities`
 *   - `confirm_delete_all_activities`
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceActivitiesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val paired = remember { DeviceStore(ctx).address() != null }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<FileTransfer.ActivityListEntry>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<FileTransfer.ActivityListEntry?>(null) }
    var pendingDeleting by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var deletingAll by remember { mutableStateOf(false) }
    var downloadingTs by remember { mutableStateOf<Long?>(null) }
    var sharingTs by remember { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }

    fun refresh() {
        if (!paired) return
        loading = true; error = null
        scope.launch {
            val res = withContext(Dispatchers.IO) { UploadPipeline.listActivities(ctx) }
            when (res) {
                is UploadPipeline.Result.Success -> entries = res.activities
                is UploadPipeline.Result.Failure -> error = res.reason
            }
            loading = false
        }
    }

    LaunchedEffect(paired) { if (paired) refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activities on device") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = ::refresh,
                        enabled = paired && !loading,
                        modifier = Modifier.testTag("refresh_activities"),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize().testTag("activities_list"),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!paired) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text(
                            "Pair a device to see recorded activities.",
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
                        Text("Loading activities…", style = MaterialTheme.typography.bodyMedium)
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
            if (entries.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text(
                            "No recorded activities.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                items(entries.size) { idx ->
                    val e = entries[idx]
                    ActivityRow(
                        entry = e,
                        downloading = downloadingTs == e.timestamp,
                        sharing = sharingTs == e.timestamp,
                        onDownload = {
                            downloadingTs = e.timestamp
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    UploadPipeline.downloadActivity(ctx, e.timestamp)
                                }
                                downloadingTs = null
                                when (res) {
                                    is UploadPipeline.Result.Success ->
                                        snackbar.showSnackbar(
                                            "Saved to ${res.activitySavedPath ?: "?"}",
                                        )
                                    is UploadPipeline.Result.Failure ->
                                        snackbar.showSnackbar("Download failed: ${res.reason}")
                                }
                            }
                        },
                        onShare = {
                            sharingTs = e.timestamp
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    UploadPipeline.downloadActivity(ctx, e.timestamp)
                                }
                                sharingTs = null
                                when (res) {
                                    is UploadPipeline.Result.Success -> {
                                        val path = res.activitySavedPath
                                        if (path != null) {
                                            val file = File(path)
                                            val uri = FileProvider.getUriForFile(
                                                ctx,
                                                "${ctx.packageName}.fileprovider",
                                                file,
                                            )
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/octet-stream"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            ctx.startActivity(
                                                Intent.createChooser(shareIntent, "Share FIT file"),
                                            )
                                        }
                                    }
                                    is UploadPipeline.Result.Failure ->
                                        snackbar.showSnackbar("Download failed: ${res.reason}")
                                }
                            }
                        },
                        onDelete = { pendingDelete = e },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { confirmDeleteAll = true },
                        enabled = !deletingAll && !loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .testTag("delete_all_activities"),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Text("  Delete all activities")
                    }
                }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { if (!deletingAll) confirmDeleteAll = false },
            title = { Text("Delete every recorded activity?") },
            text = {
                Text(
                    "This wipes every FIT file recorded on the BSC200. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deletingAll,
                    onClick = {
                        deletingAll = true
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                UploadPipeline.deleteAllActivities(ctx)
                            }
                            deletingAll = false
                            confirmDeleteAll = false
                            when (res) {
                                is UploadPipeline.Result.Success -> refresh()
                                is UploadPipeline.Result.Failure -> error = res.reason
                            }
                        }
                    },
                    modifier = Modifier.testTag("confirm_delete_all_activities"),
                ) { Text("Delete") }
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
            title = { Text("Delete activity?") },
            text = {
                Text(
                    formatActivityTimestamp(target.timestamp),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !pendingDeleting,
                    onClick = {
                        pendingDeleting = true
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                UploadPipeline.deleteActivity(ctx, target.timestamp)
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
private fun ActivityRow(
    entry: FileTransfer.ActivityListEntry,
    downloading: Boolean,
    sharing: Boolean,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag("activity_${entry.timestamp}"),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatActivityTimestamp(entry.timestamp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${formatKiB(entry.fileSize)} • ts=${entry.timestamp}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onShare,
                enabled = !sharing && !downloading,
                modifier = Modifier.testTag("share_activity_${entry.timestamp}"),
            ) {
                if (sharing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Share, contentDescription = "Share FIT")
                }
            }
            IconButton(
                onClick = onDownload,
                enabled = !downloading && !sharing,
                modifier = Modifier.testTag("download_activity_${entry.timestamp}"),
            ) {
                if (downloading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Download, contentDescription = "Download FIT")
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_activity_${entry.timestamp}"),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete activity")
            }
        }
    }
}

private fun formatActivityTimestamp(epochSeconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        .format(Date(epochSeconds * 1000L))

private fun formatKiB(bytes: Long): String =
    if (bytes <= 0) "0 KiB"
    else "%.1f KiB".format(Locale.US, bytes / 1024.0)
