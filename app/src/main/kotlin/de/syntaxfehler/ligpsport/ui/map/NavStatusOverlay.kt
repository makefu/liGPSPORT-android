package de.syntaxfehler.ligpsport.ui.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Bottom-left navigation-status pill on [MapScreen]. Polls
 * `UploadPipeline.navStatus` every 15 s and renders one of four states:
 *
 * - **Pair device first** — no `DeviceStore` address set.
 * - **Connecting…** — paired but the first poll hasn't completed.
 * - **Navigating: <route name>** — BSC200 has an `enum_USED_STATUS`
 *   entry in its `ROUTE_PLAN LIST_GET` reply (PROTOCOL.md §7.3).
 * - **No active route** — paired, polled, but no route is tagged USED.
 *
 * Async by design: the pill never blocks the map and shows the previous
 * value while a new poll is in flight. Errors fall back to "connecting"
 * — transient BLE failures shouldn't make the UI think pairing was
 * lost.
 */
@Composable
internal fun NavStatusOverlay(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val store = remember { DeviceStore(ctx) }
    var state: NavStatusUiState by remember {
        mutableStateOf(
            if (store.address() == null) NavStatusUiState.Unpaired
            else NavStatusUiState.Connecting,
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (store.address() == null) {
                state = NavStatusUiState.Unpaired
                delay(2_000)
                continue
            }
            val res = withContext(Dispatchers.IO) { UploadPipeline.navStatus(ctx) }
            state = when (res) {
                is UploadPipeline.Result.Success -> {
                    val ns = res.navStatus
                    when {
                        ns == null -> NavStatusUiState.Connecting
                        ns.isNavigating -> NavStatusUiState.Navigating(ns.activeRouteName.ifEmpty {
                            ns.activeRouteId?.toString() ?: "?"
                        })
                        else -> NavStatusUiState.Idle
                    }
                }
                // Keep showing the previous (or "Connecting") on transient
                // BLE failures rather than flipping back to "Unpaired".
                is UploadPipeline.Result.Failure -> when (state) {
                    is NavStatusUiState.Navigating, NavStatusUiState.Idle -> state
                    else -> NavStatusUiState.Connecting
                }
            }
            delay(15_000)
        }
    }

    NavStatusPill(state, modifier)
}

internal sealed interface NavStatusUiState {
    data object Unpaired : NavStatusUiState
    data object Connecting : NavStatusUiState
    data object Idle : NavStatusUiState
    data class Navigating(val routeName: String) : NavStatusUiState
}

@Composable
internal fun NavStatusPill(state: NavStatusUiState, modifier: Modifier = Modifier) {
    val label = when (state) {
        NavStatusUiState.Unpaired -> "Pair device first"
        NavStatusUiState.Connecting -> "Connecting…"
        NavStatusUiState.Idle -> "No active route"
        is NavStatusUiState.Navigating -> "Navigating: ${state.routeName}"
    }
    val leadingSpinner = state is NavStatusUiState.Connecting
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = when (state) {
        NavStatusUiState.Unpaired -> Icons.Filled.BluetoothDisabled
        NavStatusUiState.Connecting -> null
        NavStatusUiState.Idle -> Icons.Outlined.Navigation
        is NavStatusUiState.Navigating -> Icons.Filled.Navigation
    }
    Surface(
        modifier = modifier.testTag("nav_status"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp).padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
            } else if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).padding(end = 6.dp),
                )
            }
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
