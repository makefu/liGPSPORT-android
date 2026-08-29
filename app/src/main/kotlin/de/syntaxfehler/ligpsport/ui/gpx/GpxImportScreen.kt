package de.syntaxfehler.ligpsport.ui.gpx

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import de.syntaxfehler.ligpsport.ble.UploadPipeline
import de.syntaxfehler.ligpsport.route.GpxParser
import de.syntaxfehler.ligpsport.route.RouteData
import de.syntaxfehler.ligpsport.route.formatKm
import de.syntaxfehler.ligpsport.ui.upload.sanitiseFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Upload GPX" — pick a `.gpx` off the phone and push it to every
 * paired device.
 *
 * The counterpart to [de.syntaxfehler.ligpsport.ui.share.ShareImportActivity],
 * which handles the same job from the other direction: that one is
 * entered when another app shares a track *to* us, and is therefore
 * unreachable from inside the app. This screen is how you start from
 * a file you already have.
 *
 * Fans out to every paired device, like a route upload from the map —
 * a GPX picked here is the same kind of thing as a planned route, and
 * having one path reach all devices while the other silently picked
 * the first would be a trap.
 *
 * testTags: `gpx_pick`, `gpx_upload`, `gpx_summary`, `gpx_status`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpxImportScreen(onBack: () -> Unit, onOpenMenu: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices = remember { DeviceStore(ctx).list() }

    var picked by remember { mutableStateOf<Picked?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    // OpenDocument rather than GetContent: it returns a persistable,
    // re-readable URI and shows the system file browser, so files on an
    // SD card or in Drive are reachable, not just what a gallery-style
    // picker chooses to surface.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        error = null
        status = null
        picked = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { loadGpx(ctx, uri) }
            loading = false
            result.fold(
                onSuccess = { picked = it },
                onFailure = { error = it.message ?: "could not read that file" },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Upload GPX") },
                navigationIcon = {
                    if (onOpenMenu != null) {
                        IconButton(onClick = onOpenMenu, modifier = Modifier.testTag("open_menu")) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize().testTag("gpx_import"),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Send a GPX track to your cycling computer",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (devices.isEmpty()) {
                                "Pair a device first — there is nothing to upload to."
                            } else {
                                "Goes to all ${devices.size} paired " +
                                    "device${if (devices.size == 1) "" else "s"}, " +
                                    "the same as an upload from the map."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = { picker.launch(GPX_MIME_TYPES) },
                                enabled = !loading && !uploading,
                                modifier = Modifier.testTag("gpx_pick"),
                            ) {
                                Text(if (picked == null) "Choose file" else "Choose another")
                            }
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                }
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
            }

            picked?.let { file ->
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .testTag("gpx_summary"),
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                file.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${file.route.points.size} points • " +
                                    formatKm(file.route.distanceM) + " • " +
                                    "uploads as \"${file.uploadName}\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FilledTonalButton(
                                onClick = {
                                    uploading = true
                                    status = null
                                    scope.launch {
                                        val results = withContext(Dispatchers.IO) {
                                            UploadPipeline.uploadGpxAll(
                                                ctx,
                                                file.bytes,
                                                fileName = file.uploadName,
                                            )
                                        }
                                        uploading = false
                                        status = summarise(results)
                                    }
                                },
                                enabled = !uploading && devices.isNotEmpty(),
                                modifier = Modifier.testTag("gpx_upload"),
                            ) {
                                if (uploading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text("Upload")
                                }
                            }
                            status?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("gpx_status"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A GPX the user picked, already parsed so the screen can preview it. */
private data class Picked(
    val displayName: String,
    val uploadName: String,
    val bytes: ByteArray,
    val route: RouteData,
) {
    // ByteArray in a data class: identity equality is wrong and the
    // generated equals/hashCode would compare by reference anyway. This
    // is only ever held in Compose state, so make the intent explicit
    // rather than leaving a landmine for a future `==`.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Read and parse the picked document.
 *
 * The device wants a route with a name it can show, and a GPX's own
 * `<name>` is frequently absent or unhelpful ("Track"), so the file
 * name is the better default; [sanitiseFileName] then strips it down
 * to what the CNX format accepts.
 */
private fun loadGpx(
    ctx: Context,
    uri: Uri,
): Result<Picked> = runCatching {
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("could not open that file")
    if (bytes.isEmpty()) error("that file is empty")
    val route = GpxParser.parse(bytes)
    if (route.points.isEmpty()) error("no track points in that GPX")
    val fileName = displayNameOf(ctx, uri)
    Picked(
        displayName = fileName ?: route.name.ifBlank { "Picked GPX" },
        uploadName = uploadNameFor(fileName, route.name),
        bytes = bytes,
        route = route,
    )
}

/**
 * The name the route lands on the device under.
 *
 * File name first: a GPX's own `<name>` is often absent or a generic
 * "Track", whereas the user named the file. The extension goes because
 * "morning_ride.gpx" on a device that only holds routes is noise.
 * [sanitiseFileName] can reject either candidate outright — a name of
 * pure non-ASCII sanitises to nothing — hence the constant floor.
 */
internal fun uploadNameFor(fileName: String?, routeName: String?): String =
    sanitiseFileName(fileName?.substringBeforeLast('.'))
        ?: sanitiseFileName(routeName)
        ?: "imported_route"

private fun displayNameOf(ctx: Context, uri: Uri): String? =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

/**
 * Same rule the map's upload button uses: one device acking is a
 * success, because the usual reason another didn't is that it is
 * switched off — which is not an error worth shouting about.
 */
internal fun summarise(results: Map<String, UploadPipeline.Result>): String {
    if (results.isEmpty()) return "No paired device."
    val successes = results.count { it.value is UploadPipeline.Result.Success }
    if (successes == 0) {
        val reason = results.values
            .filterIsInstance<UploadPipeline.Result.Failure>()
            .firstOrNull()?.reason
        return "Upload failed${reason?.let { ": $it" } ?: "."}"
    }
    return if (successes == results.size) {
        "Uploaded to ${results.size} device${if (results.size == 1) "" else "s"}."
    } else {
        "Uploaded to $successes of ${results.size} devices."
    }
}

/**
 * `.gpx` is routinely served as octet-stream or plain XML depending on
 * where it came from, so filtering to `application/gpx+xml` alone
 * hides the user's own file in the picker. These are the same types
 * the share-sheet intent filter accepts.
 */
private val GPX_MIME_TYPES = arrayOf(
    "application/gpx+xml",
    "application/xml",
    "text/xml",
    "application/octet-stream",
)
