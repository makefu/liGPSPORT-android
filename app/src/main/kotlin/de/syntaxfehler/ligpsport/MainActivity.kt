package de.syntaxfehler.ligpsport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.syntaxfehler.ligpsport.ui.map.MapScreen
import de.syntaxfehler.ligpsport.ui.pairing.PairingScreen
import de.syntaxfehler.ligpsport.ui.settings.SettingsScreen
import de.syntaxfehler.ligpsport.ui.theme.LigpsportTheme
import de.syntaxfehler.ligpsport.ui.upload.UploadScreen

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

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "map") {
        composable("map") {
            MapScreen(
                onUpload = { gpxBytes ->
                    nav.currentBackStackEntry?.savedStateHandle?.set("gpx", gpxBytes)
                    nav.navigate("upload")
                },
                // Pairing is reached via Settings, not from the map
                // directly — the gear icon owns device management.
                onOpenPairing = { nav.navigate("settings") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenPairing = { nav.navigate("pairing") },
            )
        }
        composable("upload") {
            val gpx = nav.previousBackStackEntry?.savedStateHandle?.get<ByteArray>("gpx")
            UploadScreen(
                gpx = gpx,
                onBack = { nav.popBackStack() },
                onOpenSettings = { nav.navigate("settings") },
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
