package de.syntaxfehler.ligpsport.ui.settings

import android.content.Context
import android.content.Intent
import android.util.Log
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
import androidx.compose.material.icons.filled.CloudUpload
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
import de.syntaxfehler.ligpsport.route.FitFile
import de.syntaxfehler.ligpsport.strava.StravaStore
import de.syntaxfehler.ligpsport.strava.StravaUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * "Activities on device" sub-screen — list / download (FIT) / share /
 * delete recorded activities from the BSC200. Mirrors [DeviceRoutesScreen].
 *
 * testTags (stable, used by instrumented tests + adb harness):
 *   - `refresh_activities`
 *   - `activity_<timestamp>`
 *   - `download_activity_<timestamp>` / `share_activity_<timestamp>` /
 *     `delete_activity_<timestamp>`
 *   - `delete_all_activities`
 *   - `confirm_delete_all_activities`
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceActivitiesScreen(onBack: () -> Unit, targetMac: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolvedMac = remember(targetMac) {
        targetMac ?: DeviceStore(ctx).address()
    }
    val deviceLabel = remember(resolvedMac) {
        val list = DeviceStore(ctx).list()
        list.firstOrNull { it.mac.equals(resolvedMac, ignoreCase = true) }
            ?.let { it.name ?: it.mac }
    }
    val paired = resolvedMac != null
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<FileTransfer.ActivityListEntry>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<FileTransfer.ActivityListEntry?>(null) }
    var pendingDeleting by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var deletingAll by remember { mutableStateOf(false) }
    var downloadingTs by remember { mutableStateOf<Long?>(null) }
    var sharingTs by remember { mutableStateOf<Long?>(null) }
    var stravaTs by remember { mutableStateOf<Long?>(null) }
    // Switched on in Settings *and* holding a full set of credentials —
    // otherwise the button could only fail.
    val stravaReady = remember { StravaStore(ctx).isEnabled() }
    val snackbar = remember { SnackbarHostState() }

    fun refresh() {
        if (!paired) return
        loading = true; error = null
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                UploadPipeline.listActivities(ctx, targetMac = resolvedMac)
            }
            when (res) {
                is UploadPipeline.Result.Success -> entries = res.activities
                is UploadPipeline.Result.Failure -> error = res.reason
            }
            loading = false
        }
    }

    /**
     * Share the FIT for [entry]. Reuses the already-downloaded file when
     * there is one, otherwise pulls it off the device first — so the
     * share button works as a one-tap "get this ride out of the app"
     * without forcing a separate download tap.
     */
    fun share(entry: FileTransfer.ActivityListEntry) {
        sharingTs = entry.timestamp
        scope.launch {
            val cached = UploadPipeline.activityFitFile(ctx, entry.timestamp)
            val haveIt = withContext(Dispatchers.IO) { cached.isFile && cached.length() > 0L }
            // Fire-and-forget: showSnackbar() suspends until the snackbar is
            // dismissed, so awaiting it would stall the fetch it announces.
            // Worth saying out loud — the BLE pull takes tens of seconds and
            // otherwise reads as a hung button.
            if (!haveIt) scope.launch { snackbar.showSnackbar("Fetching FIT from the device…") }

            val outcome = withContext(Dispatchers.IO) {
                if (haveIt) {
                    ShareOutcome.Ready(cached)
                } else {
                    when (val res = UploadPipeline.downloadActivity(ctx, entry.timestamp)) {
                        is UploadPipeline.Result.Success ->
                            ShareOutcome.Ready(res.activitySavedPath?.let(::File) ?: cached)
                        is UploadPipeline.Result.Failure -> ShareOutcome.Error(res.reason)
                    }
                }
            }
            sharingTs = null
            when (outcome) {
                is ShareOutcome.Ready -> {
                    // Two distinct failures, easily collapsed into one
                    // misleading message: building the content URI (missing
                    // file / FileProvider root mismatch) vs. nothing on the
                    // phone accepting the intent.
                    val intent = runCatching { shareFitIntent(ctx, outcome.file) }.getOrElse { e ->
                        Log.w(TAG, "share: cannot build content URI for ${outcome.file}", e)
                        snackbar.showSnackbar("Can't share ${outcome.file.name}: ${e.message}")
                        return@launch
                    }
                    runCatching { ctx.startActivity(intent) }.onFailure { e ->
                        Log.w(TAG, "share: no activity accepted the FIT share intent", e)
                        snackbar.showSnackbar("No app on this phone accepts a FIT file")
                    }
                }
                is ShareOutcome.Error ->
                    snackbar.showSnackbar("Couldn't fetch the FIT: ${outcome.reason}")
            }
        }
    }

    /**
     * Push [entry] to Strava. Same cache-first fetch as [share], then
     * an upload that Strava processes asynchronously — hence the wait
     * for an activity id rather than treating the POST as done.
     */
    fun sendToStrava(entry: FileTransfer.ActivityListEntry) {
        stravaTs = entry.timestamp
        scope.launch {
            val cached = UploadPipeline.activityFitFile(ctx, entry.timestamp)
            val haveIt = withContext(Dispatchers.IO) { cached.isFile && cached.length() > 0L }
            scope.launch {
                snackbar.showSnackbar(
                    if (haveIt) "Uploading to Strava…" else "Fetching FIT from the device…",
                )
            }
            val res = withContext(Dispatchers.IO) {
                StravaUploader.upload(
                    ctx,
                    entry.timestamp,
                    name = null, // let Strava name it from start time + location
                )
            }
            stravaTs = null
            when (res) {
                is StravaUploader.Result.Success ->
                    snackbar.showSnackbar(
                        when {
                            res.activityId == null ->
                                "Strava accepted the upload — still processing"
                            res.mutedFromFeed -> "Uploaded to Strava (hidden from feed)"
                            // Worth calling out: the ride is live in
                            // followers' feeds, which is what muting was
                            // meant to prevent.
                            else -> "Uploaded to Strava, but it is visible in the feed"
                        },
                    )
                is StravaUploader.Result.Failure ->
                    snackbar.showSnackbar("Strava upload failed: ${res.reason}")
            }
        }
    }

    LaunchedEffect(paired) { if (paired) refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Activities on device")
                        deviceLabel?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
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
                        stravaEnabled = stravaReady,
                        stravaBusy = stravaTs == e.timestamp,
                        onShare = { share(e) },
                        onSendToStrava = { sendToStrava(e) },
                        onDownload = {
                            downloadingTs = e.timestamp
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    UploadPipeline.downloadActivity(
                                        ctx,
                                        e.timestamp,
                                        targetMac = resolvedMac,
                                    )
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
                                UploadPipeline.deleteAllActivities(ctx, targetMac = resolvedMac)
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
                                UploadPipeline.deleteActivity(
                                    ctx,
                                    target.timestamp,
                                    targetMac = resolvedMac,
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

private const val TAG = "DeviceActivities"

private sealed interface ShareOutcome {
    data class Ready(val file: File) : ShareOutcome
    data class Error(val reason: String) : ShareOutcome
}

/**
 * ACTION_SEND chooser for a downloaded FIT.
 *
 * MIME is `application/octet-stream` rather than the registered
 * `application/vnd.ant.fit` on purpose: almost nothing declares an
 * intent-filter for the FIT type, so the correct MIME yields an empty
 * chooser. octet-stream is what the receiving apps (Drive, Gmail,
 * Nearby Share) actually match on — the same reason this app's own
 * ShareImportActivity accepts it for inbound GPX.
 */
private fun shareFitIntent(ctx: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, "Share activity")
}

@Composable
private fun ActivityRow(
    entry: FileTransfer.ActivityListEntry,
    downloading: Boolean,
    sharing: Boolean,
    stravaEnabled: Boolean,
    stravaBusy: Boolean,
    onShare: () -> Unit,
    onSendToStrava: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val busy = downloading || sharing || stravaBusy
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
                onClick = onDownload,
                enabled = !busy,
                modifier = Modifier.testTag("download_activity_${entry.timestamp}"),
            ) {
                if (downloading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Download, contentDescription = "Download FIT")
                }
            }
            if (stravaEnabled) {
                IconButton(
                    onClick = onSendToStrava,
                    enabled = !busy,
                    modifier = Modifier.testTag("strava_activity_${entry.timestamp}"),
                ) {
                    if (stravaBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Filled.CloudUpload, contentDescription = "Send to Strava")
                    }
                }
            }
            IconButton(
                onClick = onShare,
                enabled = !busy,
                modifier = Modifier.testTag("share_activity_${entry.timestamp}"),
            ) {
                if (sharing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Share, contentDescription = "Share FIT")
                }
            }
            IconButton(
                onClick = onDelete,
                enabled = !busy,
                modifier = Modifier.testTag("delete_activity_${entry.timestamp}"),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete activity")
            }
        }
    }
}

/**
 * Device timestamps are FIT-epoch seconds, and read as the rider's
 * local wall-clock rather than UTC — so convert the epoch, then format
 * in UTC to reproduce the reading the head unit itself shows. Applying
 * the phone's timezone here would shift it a second time.
 */
private fun formatActivityTimestamp(deviceTimestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(FitFile.garminToUnixSeconds(deviceTimestamp) * 1000L))

private fun formatKiB(bytes: Long): String =
    if (bytes <= 0) "0 KiB"
    else "%.1f KiB".format(Locale.US, bytes / 1024.0)
