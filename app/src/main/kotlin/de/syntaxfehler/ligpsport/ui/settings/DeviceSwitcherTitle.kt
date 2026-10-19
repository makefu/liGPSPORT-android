package de.syntaxfehler.ligpsport.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.syntaxfehler.ligpsport.ble.DeviceStore

/**
 * Top-bar title for the two per-device screens: a fixed [title] with
 * the current device underneath, and — when more than one device is
 * paired — a dropdown to switch between them without leaving.
 *
 * These screens are reachable straight from the navigation drawer,
 * where there is no device to pick beforehand, so the choice has to
 * live in the screen itself. With a single paired device the subtitle
 * is inert text: a dropdown offering one option is just a dead tap.
 *
 * testTags: `device_switcher`, `device_option_<mac>`.
 */
@Composable
fun DeviceSwitcherTitle(
    title: String,
    devices: List<DeviceStore.Paired>,
    selectedMac: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = devices.firstOrNull { it.mac.equals(selectedMac, ignoreCase = true) }
    val label = selected?.let { it.name ?: it.mac }
    val switchable = devices.size > 1

    Column {
        Text(title)
        if (label == null) return@Column
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .clickable(enabled = switchable) { expanded = true }
                .testTag("device_switcher"),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (switchable) {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = "Switch device",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.name ?: device.mac) },
                    leadingIcon = {
                        RadioButton(
                            selected = device.mac.equals(selectedMac, ignoreCase = true),
                            onClick = null,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(device.mac)
                    },
                    modifier = Modifier.testTag("device_option_${device.mac}"),
                )
            }
        }
    }
}
