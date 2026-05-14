package de.syntaxfehler.ligpsport.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import de.syntaxfehler.ligpsport.route.CnxEncoder
import de.syntaxfehler.ligpsport.route.GpxParser
import de.syntaxfehler.ligpsport.ui.theme.LigpsportTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Receives `ACTION_SEND` intents from apps like OsmAnd that share a
 * GPX track. The GPX bytes are read via ContentResolver (no permission
 * needed — the sender granted us read access on the URI), parsed,
 * and previewed before the user confirms upload.
 */
class ShareImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = if (intent?.action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
        } else null

        setContent {
            LigpsportTheme {
                SharePreview(uri = uri, onFinish = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharePreview(uri: Uri?, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("Loading shared GPX…") }
    var cnxSize by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(uri) {
        if (uri == null) {
            status = "No GPX URI in share intent."
            return@LaunchedEffect
        }
        try {
            val bytes = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("could not open stream for $uri")
            }
            val parsed = withContext(Dispatchers.Default) { GpxParser.parse(bytes) }
            val cnx = withContext(Dispatchers.Default) { CnxEncoder.encode(parsed) }
            cnxSize = cnx.size
            status = "Loaded ${parsed.points.size} points from \"${parsed.name}\"."
        } catch (e: Exception) {
            status = "Failed to load GPX: ${e.message}"
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Upload shared GPX") }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(status, modifier = Modifier.testTag("share_status"))
            cnxSize?.let { Text("CNX: $it bytes") }
            Button(onClick = onFinish, modifier = Modifier.testTag("share_close")) {
                Text("Close")
            }
        }
    }
}
