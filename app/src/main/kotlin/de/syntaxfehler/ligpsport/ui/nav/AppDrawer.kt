package de.syntaxfehler.ligpsport.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The app's top-level destinations — everything reachable directly
 * from the navigation drawer.
 *
 * [route] doubles as the NavHost route. The two device screens declare
 * their `mac` as an *optional* query argument, so navigating to the
 * bare route here is legal and means "no device preselected"; the
 * screen then defaults to the first paired one and offers its own
 * switcher. See `MainActivity.AppNav`.
 */
enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Map("map", "Map", Icons.Filled.Map),
    Activities("activities", "Activities on device", Icons.AutoMirrored.Filled.DirectionsBike),
    Routes("routes", "Routes on device", Icons.Filled.Route),
    Settings("settings", "Settings", Icons.Filled.Settings),
    ;

    companion object {
        /**
         * The destination a NavHost route belongs to, or null for a
         * sub-screen (pairing, previous rides). Strips the query string
         * so `activities?mac=…` still resolves to [Activities].
         */
        fun forRoute(route: String?): AppDestination? {
            val base = route?.substringBefore('?') ?: return null
            return entries.firstOrNull { it.route == base }
        }
    }
}

/**
 * Drawer contents: one row per [AppDestination].
 *
 * A drawer rather than a bottom bar because the map is the primary
 * screen and full-bleed — a permanent bottom bar would cut ~80dp off
 * it for navigation the user needs a few times a session.
 *
 * testTags: `drawer_sheet`, `drawer_<route>`.
 */
@Composable
fun AppDrawerContent(
    current: AppDestination?,
    onSelect: (AppDestination) -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.testTag("drawer_sheet")) {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 24.dp)) {
            Text(
                "liGPSPORT",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Routes and rides for the BSC200",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        AppDestination.entries.forEach { dest ->
            NavigationDrawerItem(
                label = { Text(dest.label) },
                icon = { Icon(dest.icon, contentDescription = null) },
                selected = dest == current,
                onClick = { onSelect(dest) },
                modifier = Modifier
                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                    .testTag("drawer_${dest.route}"),
            )
        }
    }
}
