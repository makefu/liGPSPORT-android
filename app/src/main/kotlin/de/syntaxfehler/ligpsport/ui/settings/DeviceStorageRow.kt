package de.syntaxfehler.ligpsport.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.syntaxfehler.ligpsport.ble.DeviceMemory
import de.syntaxfehler.ligpsport.ble.UploadPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Flash usage for [mac], or null until the device answers.
 *
 * Call this from the screen, not from inside a LazyColumn item: item
 * state is dropped when the row scrolls out of view, and each return
 * would mean another BLE round-trip.
 *
 * Keyed on [refreshKey] as well as [mac] so a delete-all re-reads and
 * the freed space is visible immediately — that number moving is the
 * confirmation the user actually wanted.
 */
@Composable
fun rememberDeviceMemory(mac: String?, refreshKey: Any? = null): DeviceMemory.Info? {
    val ctx = LocalContext.current
    var info by remember(mac) { mutableStateOf<DeviceMemory.Info?>(null) }
    LaunchedEffect(mac, refreshKey) {
        if (mac == null) return@LaunchedEffect
        info = withContext(Dispatchers.IO) { UploadPipeline.deviceMemory(ctx, targetMac = mac) }
    }
    return info
}

/**
 * Flash usage, shown at the foot of the routes and activities lists.
 * [info] comes from [rememberDeviceMemory].
 *
 * It belongs next to the lists rather than on a screen of its own:
 * "should I delete these?" is asked while looking at them, and
 * "Delete all" is otherwise a decision made blind.
 *
 * Renders nothing at all until the device answers. The read is a
 * separate BLE round-trip on a device that may not implement the
 * operation ([DeviceMemory] is unverified against real hardware), so a
 * silent absence is the only acceptable failure mode — an error row
 * here would be noise attached to someone else's screen.
 *
 * testTag: `device_storage`.
 */
@Composable
fun DeviceStorageRow(info: DeviceMemory.Info?) {
    val shown = info ?: return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag("device_storage"),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Device storage", style = MaterialTheme.typography.labelLarge)
                Text(
                    formatStorage(shown),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            shown.fractionUsed?.let { fraction ->
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
        }
    }
}

/**
 * "412 MB free of 1.9 GB".
 *
 * Free-first because that is the question being asked. A device that
 * reports a zero total — schema-legal, and what an unimplemented
 * operation might return — degrades to just the free figure rather
 * than printing "of 0 B".
 */
internal fun formatStorage(info: DeviceMemory.Info): String =
    if (info.total <= 0L) {
        "${formatBytes(info.free)} free"
    } else {
        "${formatBytes(info.free)} free of ${formatBytes(info.total)}"
    }

/**
 * Decimal units (MB, not MiB) to match how storage is advertised and
 * how Android's own settings report it. One decimal below 10 of a
 * unit, none above — 1.9 GB and 412 MB, never 1.94 GB or 412.3 MB.
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1_000L) return "$bytes B"
    val units = listOf("kB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1_000.0
    var unit = 0
    while (value >= 1_000.0 && unit < units.size - 1) {
        value /= 1_000.0
        unit++
    }
    return if (value < 10.0) {
        String.format(Locale.US, "%.1f %s", value, units[unit])
    } else {
        String.format(Locale.US, "%.0f %s", value, units[unit])
    }
}
