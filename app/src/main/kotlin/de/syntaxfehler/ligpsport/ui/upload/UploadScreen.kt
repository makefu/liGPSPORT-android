package de.syntaxfehler.ligpsport.ui.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import de.syntaxfehler.ligpsport.ble.DeviceStore
import de.syntaxfehler.ligpsport.ble.UploadPipeline
import de.syntaxfehler.ligpsport.data.RouteSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Auto-runs the BLE upload as soon as the screen is entered, provided
 * a device is paired. Otherwise prompts the user to open Settings to
 * pair one. There is no manual "Send" button — once the user tapped
 * "Upload route" on the map they've committed to sending, and a
 * follow-up confirmation tap would just be friction.
 *
 * "Back to map" preserves the planned-route state (see
 * [de.syntaxfehler.ligpsport.data.RouteSessionStore]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    gpx: ByteArray?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val store = remember { DeviceStore(ctx) }
    val pairedAddress = remember { store.address() }
    val pairedName = remember { store.name() }

    var uploading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UploadPipeline.Result?>(null) }
    var status by remember {
        mutableStateOf(
            when {
                gpx == null -> "No route to upload."
                pairedAddress == null -> "No paired device — open Settings to pair one."
                else -> "Connecting & uploading…"
            },
        )
    }

    LaunchedEffect(gpx, pairedAddress) {
        if (gpx == null || pairedAddress == null) return@LaunchedEffect
        if (result != null) return@LaunchedEffect // already done on this entry
        uploading = true
        // Use the picked destination name as the file name on the
        // device, so the BSC200's saved-route list shows something
        // meaningful instead of "route". Sanitised for the firmware:
        // ASCII alphanumerics + `_-.`, max 32 chars, never empty.
        val displayName = RouteSessionStore.get()?.destinationName
        val fileName = sanitiseFileName(displayName) ?: "route"
        val res = withContext(Dispatchers.IO) {
            UploadPipeline.uploadGpx(ctx, gpx, fileName = fileName)
        }
        uploading = false
        result = res
        status = when (res) {
            is UploadPipeline.Result.Success ->
                "Upload complete (${res.bytesSent} B, device returned status ${res.status})."
            is UploadPipeline.Result.Failure ->
                "Upload failed: ${res.reason}"
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Upload to iGPSPORT") }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Headline: paired-device line.
            if (pairedAddress != null) {
                Text(
                    "Sending to ${pairedName ?: pairedAddress}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                Text(
                    "Not paired",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Status line / progress / outcome.
            when {
                uploading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(status, modifier = Modifier.testTag("upload_status"))
                    }
                }
                result is UploadPipeline.Result.Success -> {
                    StatusRow(
                        icon = Icons.Default.CheckCircle,
                        tint = MaterialTheme.colorScheme.primary,
                        message = status,
                        secondary = "Check the device's Saved Routes menu.",
                    )
                }
                result is UploadPipeline.Result.Failure -> {
                    StatusRow(
                        icon = Icons.Default.Error,
                        tint = MaterialTheme.colorScheme.error,
                        message = status,
                    )
                }
                else -> {
                    Text(status, modifier = Modifier.testTag("upload_status"))
                }
            }

            if (pairedAddress == null) {
                FilledTonalButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("open_settings"),
                ) { Text("Open settings") }
            }

            OutlinedButton(onClick = onBack, modifier = Modifier.testTag("back_button")) {
                Text("Back to map")
            }
        }
    }
}

/**
 * Reduce a free-form destination name to something the BSC200 firmware
 * is happy with as a file name: ASCII letters/digits/`_-.`, spaces
 * folded to `_`, capped at 32 chars. Returns null for blank/unusable
 * inputs so the caller can fall back to a default.
 *
 * Visible for testing.
 */
internal fun sanitiseFileName(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val collapsed = raw.trim().replace(Regex("\\s+"), "_")
    val ascii = buildString(collapsed.length) {
        for (ch in collapsed) {
            when {
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' -> append(ch)
                ch == '_' || ch == '-' || ch == '.' -> append(ch)
                // Drop everything else (umlauts, punctuation, emoji).
            }
        }
    }
    val trimmed = ascii.trim('_', '-', '.')
    if (trimmed.isEmpty()) return null
    return trimmed.take(32)
}

@Composable
private fun StatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    message: String,
    secondary: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint)
            Text(
                message,
                modifier = Modifier.testTag("upload_status"),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        secondary?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
