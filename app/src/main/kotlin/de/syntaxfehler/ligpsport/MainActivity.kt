package de.syntaxfehler.ligpsport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.syntaxfehler.ligpsport.ui.map.MapScreen
import de.syntaxfehler.ligpsport.ui.nav.AppDestination
import de.syntaxfehler.ligpsport.ui.nav.AppDrawerContent
import de.syntaxfehler.ligpsport.ui.pairing.PairingScreen
import de.syntaxfehler.ligpsport.ui.settings.DeviceActivitiesScreen
import de.syntaxfehler.ligpsport.ui.settings.DeviceRoutesScreen
import de.syntaxfehler.ligpsport.ui.settings.PreviousRidesScreen
import de.syntaxfehler.ligpsport.ui.settings.SettingsScreen
import de.syntaxfehler.ligpsport.ui.theme.LigpsportTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LigpsportTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNav()
                }
            }
        }
    }
}

/**
 * Navigation host plus the drawer that reaches every top-level screen
 * ([AppDestination]).
 *
 * Two kinds of destination live here:
 *
 * - **Top-level** — in the drawer, leading icon is a hamburger, and
 *   selecting one pops back to the map first so the back stack stays
 *   flat instead of accumulating a chain of sibling screens.
 * - **Sub-screens** (pairing, previous rides, and the device screens
 *   entered from a Settings device row) — pushed with a back arrow.
 *
 * The device screens serve both roles. Their `mac` is an optional
 * query argument: absent means "came from the drawer", so the screen
 * defaults to the first paired device and shows its own switcher;
 * present means a Settings row preselected one and back is the way
 * out.
 */
@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val current = AppDestination.forRoute(route)
    // A screen entered from a Settings device row keeps its back arrow;
    // only the drawer's own entry point gets the hamburger.
    val fromDrawer = current != null && backStackEntry?.arguments?.getString("mac") == null

    fun openMenu() {
        scope.launch { drawer.open() }
    }

    fun go(dest: AppDestination) {
        scope.launch { drawer.close() }
        if (dest == current) return
        nav.navigate(dest.route) {
            // Everything hangs off the map: selecting Settings from
            // Activities should leave one screen behind you, not two.
            popUpTo(AppDestination.Map.route) { inclusive = false }
            launchSingleTop = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        // Swipe-to-open is off on the map, where an edge drag is a pan;
        // the FAB is the way in there. Swipe-to-close always works.
        gesturesEnabled = drawer.isOpen || (fromDrawer && current != AppDestination.Map),
        drawerContent = { AppDrawerContent(current = current, onSelect = ::go) },
    ) {
        NavHost(navController = nav, startDestination = AppDestination.Map.route) {
            composable(AppDestination.Map.route) {
                MapScreen(
                    // Pairing is reached via Settings, not from the map
                    // directly — device management lives there.
                    onOpenPairing = { go(AppDestination.Settings) },
                    onOpenMenu = ::openMenu,
                )
            }
            composable(AppDestination.Settings.route) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenPairing = { nav.navigate("pairing") },
                    onOpenRoutes = { mac -> nav.navigate("${AppDestination.Routes.route}?mac=$mac") },
                    onOpenActivities = { mac ->
                        nav.navigate("${AppDestination.Activities.route}?mac=$mac")
                    },
                    onOpenPreviousRides = { nav.navigate("previous_rides") },
                    onOpenMenu = ::openMenu,
                )
            }
            composable(
                route = "${AppDestination.Routes.route}?mac={mac}",
                arguments = listOf(
                    navArgument("mac") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                DeviceRoutesScreen(
                    onBack = { nav.popBackStack() },
                    targetMac = entry.arguments?.getString("mac"),
                    onOpenMenu = if (entry.arguments?.getString("mac") == null) ::openMenu else null,
                )
            }
            composable(
                route = "${AppDestination.Activities.route}?mac={mac}",
                arguments = listOf(
                    navArgument("mac") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                DeviceActivitiesScreen(
                    onBack = { nav.popBackStack() },
                    targetMac = entry.arguments?.getString("mac"),
                    onOpenMenu = if (entry.arguments?.getString("mac") == null) ::openMenu else null,
                )
            }
            composable("previous_rides") {
                PreviousRidesScreen(
                    onBack = { nav.popBackStack() },
                    // Pop the entire settings stack so the user lands
                    // back on the map with the restored ride already in
                    // place — RouteSessionStore was filled in onOpen.
                    onRestoredToMap = {
                        nav.popBackStack(route = AppDestination.Map.route, inclusive = false)
                    },
                )
            }
            composable("pairing") {
                PairingScreen(
                    onPaired = { nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
