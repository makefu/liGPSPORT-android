package de.syntaxfehler.ligpsport.ui.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.syntaxfehler.ligpsport.ble.UploadPipeline
import de.syntaxfehler.ligpsport.data.RouteSessionStore
import de.syntaxfehler.ligpsport.data.RouterPreferences
import de.syntaxfehler.ligpsport.route.GpxParser
import de.syntaxfehler.ligpsport.route.Point
import de.syntaxfehler.ligpsport.route.RouterRegistry
import de.syntaxfehler.ligpsport.search.PhotonClient
import de.syntaxfehler.ligpsport.search.SearchResult
import de.syntaxfehler.ligpsport.ui.upload.sanitiseFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * Search-first map flow:
 * - Top: a docked SearchBar with debounced Photon autocomplete results
 *   sorted by distance from the current map centre.
 * - Bottom (when a destination is selected): a card showing the
 *   selected destination + an Upload action.
 * - Tap-on-map is preserved as a quick fallback gesture for picking
 *   places the geocoder can't name.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun MapScreen(
    onOpenPairing: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Restore the previous session if the user is returning from
    // Settings/Pairing — Compose `remember` is destroyed across nav,
    // so we lean on the in-process RouteSessionStore.
    val initialSession = remember { RouteSessionStore.get() }
    var destination by remember {
        mutableStateOf(
            initialSession?.let { s ->
                Destination(s.destinationName, s.destinationLat, s.destinationLon)
            },
        )
    }
    var planningRoute by remember { mutableStateOf(false) }
    var uploadState by remember { mutableStateOf<UploadButtonState>(UploadButtonState.Idle) }
    // Live mirror of RouteSessionStore.plannedGpx so the Upload button
    // can react to the most recent plan without re-reading the store on
    // every recomposition. Pre-populated from any restored session.
    var plannedGpx by remember { mutableStateOf(initialSession?.plannedGpx) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    val queryFlow = remember { MutableStateFlow("") }
    var suggestions by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var currentLocation by remember { mutableStateOf<Point?>(null) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasLocationPermission = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!hasLocationPermission) {
            statusMessage = "Location permission denied. Tap the map to pick a start point."
        }
    }
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    // osmdroid Configuration is initialised once in App.onCreate so
    // user-agent + tile-cache path/size are set before any MapView is
    // inflated. Don't re-load here — that would race with the App
    // bootstrap and could clobber the cache settings.

    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(13.0)
            // Default centre on Berlin; will be replaced as soon as the
            // location overlay reports its first fix.
            controller.setCenter(GeoPoint(52.5200, 13.4050))
        }
    }

    val locationOverlay = remember {
        MyLocationNewOverlay(GpsMyLocationProvider(ctx), mapView).apply {
            // The default `person` bitmap looks dated; switch to the
            // small directional arrow + accuracy circle.
            enableMyLocation()
        }
    }

    // Replay the persisted session onto the freshly-created MapView
    // (marker + polyline + camera). Runs once per MapView instance.
    LaunchedEffect(mapView) {
        initialSession?.let { s ->
            val dest = Destination(s.destinationName, s.destinationLat, s.destinationLon)
            setDestination(mapView, null, dest)
            mapView.controller.animateTo(GeoPoint(s.destinationLat, s.destinationLon))
            s.plannedGpx?.let { drawRoute(mapView, it) }
        }
    }

    // Persist destination edits so they survive a Map → Upload → Back
    // round-trip. Setting destination=null clears the store entirely
    // (the user pressed the "X" on the card). When the destination
    // changes, the old plan no longer applies — drop it so the Upload
    // button stays disabled until the user re-plans.
    LaunchedEffect(destination) {
        val d = destination
        if (d == null) {
            RouteSessionStore.clear()
            plannedGpx = null
        } else {
            val cur = RouteSessionStore.get()
            if (cur == null ||
                cur.destinationLat != d.lat ||
                cur.destinationLon != d.lon ||
                cur.destinationName != d.label
            ) {
                RouteSessionStore.set(
                    RouteSessionStore.Session(d.label, d.lat, d.lon, plannedGpx = null),
                )
                plannedGpx = null
                clearRouteOverlay(mapView)
                // Reset the upload-button outcome on destination change.
                // While the upload is in flight, leave the button alone
                // — the spinner stays visible until that upload settles.
                if (uploadState !is UploadButtonState.Uploading) {
                    uploadState = UploadButtonState.Idle
                }
            }
        }
    }

    // Permission grant flips us from "overlay constructed but not
    // listening" to "overlay actively requesting fixes" without
    // recreating the MapView.
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            locationOverlay.enableMyLocation()
            locationOverlay.runOnFirstFix {
                val pt = locationOverlay.myLocation ?: return@runOnFirstFix
                currentLocation = Point(pt.latitude, pt.longitude)
                // Hop back to the main thread for camera animation —
                // runOnFirstFix dispatches from a binder thread.
                mapView.post {
                    mapView.controller.animateTo(pt)
                    mapView.controller.setZoom(16.0)
                }
            }
        } else {
            locationOverlay.disableMyLocation()
        }
    }

    // Keep `currentLocation` in sync with subsequent fixes after the
    // first one, so the BRouter start always reflects the latest fix.
    LaunchedEffect(hasLocationPermission, locationOverlay) {
        if (!hasLocationPermission) return@LaunchedEffect
        while (true) {
            locationOverlay.myLocation?.let { pt ->
                currentLocation = Point(pt.latitude, pt.longitude)
            }
            kotlinx.coroutines.delay(2_000)
        }
    }

    // Debounced search trigger. Photon's free instance asks consumers
    // to keep the QPS modest; 300 ms feels snappy yet polite.
    LaunchedEffect(Unit) {
        queryFlow
            .debounce(300)
            .distinctUntilChanged()
            .collect { q ->
                if (q.isBlank()) {
                    suggestions = emptyList()
                    searching = false
                    return@collect
                }
                searching = true
                // Prefer the live current location so suggestions are
                // sorted by distance from the user; fall back to map
                // centre when we don't have a fix yet.
                val biasLat = currentLocation?.latitude ?: mapView.mapCenter.latitude
                val biasLon = currentLocation?.longitude ?: mapView.mapCenter.longitude
                val results = try {
                    withContext(Dispatchers.IO) {
                        PhotonClient().autocomplete(
                            query = q,
                            biasLat = biasLat,
                            biasLon = biasLon,
                            limit = 8,
                        )
                    }
                } catch (e: Exception) {
                    statusMessage = "Search failed: ${e.message}"
                    emptyList()
                }
                suggestions = results
                searching = false
            }
    }

    DisposableEffect(mapView) {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (p != null) {
                    // Provisional label: coordinates while we wait for
                    // reverse-geocoding. The picker doesn't have to
                    // stall on a network round-trip; the label
                    // upgrades in place once Photon responds.
                    val provisional = Destination(
                        label = "%.5f, %.5f".format(p.latitude, p.longitude),
                        lat = p.latitude,
                        lon = p.longitude,
                    )
                    setDestination(mapView, destination, provisional)
                    destination = provisional
                    // Collapse the search overlay — the user just made
                    // a pick gesture; keeping the search panel open
                    // would hide the destination card behind it.
                    searchActive = false
                    scope.launch {
                        val named = try {
                            withContext(Dispatchers.IO) {
                                PhotonClient().reverse(p.latitude, p.longitude)
                            }
                        } catch (_: Exception) {
                            null
                        } ?: return@launch
                        // Only upgrade if the user hasn't moved on to a
                        // different destination in the meantime.
                        val cur = destination ?: return@launch
                        if (cur.lat != p.latitude || cur.lon != p.longitude) return@launch
                        val upgraded = Destination(named.name, p.latitude, p.longitude)
                        setDestination(mapView, cur, upgraded)
                        destination = upgraded
                    }
                }
                return true
            }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }
        mapView.overlays.add(0, MapEventsOverlay(receiver))
        // Add the location overlay on top so the blue dot stays
        // visible over routes / markers.
        if (locationOverlay !in mapView.overlays) {
            mapView.overlays.add(locationOverlay)
        }
        mapView.onResume()
        onDispose {
            mapView.onPause()
            locationOverlay.disableMyLocation()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize().testTag("osm_map"),
        )

        // Search bar overlays the map at the top.
        DockedSearchBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("search_bar"),
            inputField = {
                SearchBarDefaults.InputField(
                    query = searchQuery,
                    onQueryChange = {
                        searchQuery = it
                        queryFlow.value = it
                    },
                    onSearch = { searchActive = false },
                    expanded = searchActive,
                    onExpandedChange = { searchActive = it },
                    placeholder = { Text("Search a destination…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = {
                                searchQuery = ""
                                queryFlow.value = ""
                                suggestions = emptyList()
                            }) {
                                Icon(Icons.Default.Cancel, contentDescription = "Clear")
                            }
                        }
                    } else null,
                )
            },
            expanded = searchActive,
            onExpandedChange = { searchActive = it },
            colors = SearchBarDefaults.colors(),
        ) {
            // Suggestion list, "organic" — most-relevant-closest first.
            if (searching) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Searching…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(suggestions, key = { it.latitude.toString() + "_" + it.longitude.toString() + "_" + it.name }) { result ->
                    SuggestionRow(
                        result = result,
                        onClick = {
                            val picked = Destination(result.name, result.latitude, result.longitude)
                            destination = setDestination(mapView, destination, picked)
                            mapView.controller.animateTo(GeoPoint(picked.lat, picked.lon))
                            searchQuery = result.name
                            queryFlow.value = ""
                            suggestions = emptyList()
                            searchActive = false
                        },
                    )
                }
            }
        }

        // Bottom-left navigation-status pill. Polls the BSC200's
        // ROUTE_PLAN LIST_GET every ~15 s (PROTOCOL.md §7.3) and reports
        // whether a route is currently active.
        NavStatusOverlay(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 24.dp),
        )

        // Stacked FABs in the bottom-right corner. Extracted into a
        // separate composable so the visibility-gating logic ("my
        // location FAB is only present when a fix is available") can
        // be exercised by a small Compose UI test without booting up
        // the whole MapScreen + osmdroid + Photon stack.
        BottomEndFabs(
            currentLocation = currentLocation,
            onMyLocation = { pt ->
                mapView.controller.animateTo(GeoPoint(pt.latitude, pt.longitude))
                mapView.controller.setZoom(16.0)
            },
            onOpenSettings = onOpenSettings,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 24.dp),
        )

        // Auto-plan whenever the destination changes and we have a GPS
        // fix. The Plan button is gone — tapping a point on the map (or
        // picking a search result) is the user's commitment to a
        // destination, and a route preview is the immediate feedback. The
        // user still has a chance to back out before Upload.
        LaunchedEffect(destination, currentLocation) {
            val dest = destination ?: return@LaunchedEffect
            if (plannedGpx != null) return@LaunchedEffect
            val start = currentLocation ?: run {
                statusMessage = "Waiting for GPS fix…"
                return@LaunchedEffect
            }
            val provider = RouterRegistry.byId(RouterPreferences(ctx).get())
                ?: RouterRegistry.default
            statusMessage = "Planning route via ${provider.displayName}…"
            planningRoute = true
            try {
                val end = Point(dest.lat, dest.lon)
                val gpx = withContext(Dispatchers.IO) { provider.planGpx(start, end) }
                clearRouteOverlay(mapView)
                drawRoute(mapView, gpx)
                RouteSessionStore.setPlannedGpx(gpx)
                plannedGpx = gpx
                statusMessage = "Route ready — tap Upload to send."
            } catch (e: Exception) {
                statusMessage = "Routing failed: ${e.message}"
            } finally {
                planningRoute = false
            }
        }

        // Bottom card appears as soon as a destination is set.
        destination?.let { dest ->
            val uploading = uploadState is UploadButtonState.Uploading
            DestinationCard(
                destination = dest,
                planning = planningRoute,
                hasPlan = plannedGpx != null,
                uploadState = uploadState,
                // Block the X button while the upload is in flight —
                // clearing the destination during upload would tear
                // down the card mid-progress and lose the result
                // surface. The user can clear after success/failure.
                onClear = if (uploading) null else {
                    {
                        destination = null
                        plannedGpx = null
                        statusMessage = null
                        uploadState = UploadButtonState.Idle
                        clearDestination(mapView)
                    }
                },
                onUpload = onUpload@{
                    val gpx = plannedGpx ?: return@onUpload
                    val fileName = sanitiseFileName(dest.label) ?: "route"
                    uploadState = UploadButtonState.Uploading
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            UploadPipeline.uploadGpx(ctx, gpx, fileName = fileName)
                        }
                        uploadState = when (res) {
                            is UploadPipeline.Result.Success ->
                                UploadButtonState.Success
                            is UploadPipeline.Result.Failure ->
                                UploadButtonState.Failed(res.reason)
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }

        // Lightweight status toast at the bottom-edge above the card.
        statusMessage?.let { msg ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 88.dp)
                    .clickable { statusMessage = null }
                    .testTag("status_toast"),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(msg, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

/**
 * Bottom-right FAB stack: settings (always) + my-location (only when a
 * fix is available). Internal-visible so a Compose UI test can exercise
 * the visibility gating without pulling in the rest of MapScreen.
 */
@Composable
internal fun BottomEndFabs(
    currentLocation: Point?,
    onMyLocation: (Point) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        if (currentLocation != null) {
            FloatingActionButton(
                onClick = { onMyLocation(currentLocation) },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.testTag("my_location_fab"),
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Center on my location")
            }
        }
        FloatingActionButton(
            onClick = onOpenSettings,
            modifier = Modifier.testTag("settings_fab"),
        ) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun SuggestionRow(result: SearchResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("suggestion_${result.name}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(result.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (result.description.isNotBlank()) {
                Text(
                    result.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        result.distanceM?.let { dist ->
            Text(
                formatDistance(dist),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * In-place upload-button states for the destination card.
 *
 * Idle → user has a plan, button reads "Upload" in the default tonal
 *   colour and is clickable.
 * Uploading → tapped Upload; button shows a spinner and is disabled.
 *   Stays this way even if the user picks a new destination, so the
 *   in-flight upload can't be re-fired before it settles.
 * Success → BSC200 acked the upload (and the follow-up FILE_USE).
 *   Button shows a green "Uploaded ✓" pill, disabled — picking a new
 *   destination resets back to Idle.
 * Failed → upload errored out; button turns red and reads "Retry",
 *   tappable to fire the same upload again.
 */
internal sealed interface UploadButtonState {
    data object Idle : UploadButtonState
    data object Uploading : UploadButtonState
    data object Success : UploadButtonState
    data class Failed(val reason: String) : UploadButtonState
}

@Composable
private fun DestinationCard(
    destination: Destination,
    planning: Boolean,
    hasPlan: Boolean,
    uploadState: UploadButtonState,
    onClear: (() -> Unit)?,
    onUpload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().testTag("destination_card"),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text("Destination", style = MaterialTheme.typography.labelMedium)
                    Text(
                        destination.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "%.5f, %.5f".format(destination.lat, destination.lon),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onClear != null) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Cancel, contentDescription = "Clear destination")
                    }
                }
            }
            UploadButton(
                state = uploadState,
                planning = planning,
                hasPlan = hasPlan,
                onUpload = onUpload,
            )
        }
    }
}

/**
 * The in-place upload button — drives all four [UploadButtonState]
 * presentations off the same Composable so the layout stays stable
 * across state changes (no FAB-style hop when the spinner appears).
 */
@Composable
private fun UploadButton(
    state: UploadButtonState,
    planning: Boolean,
    hasPlan: Boolean,
    onUpload: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Green from the secondary container for the success state; red
    // from the error container for failure. Both are still tonal-
    // button-shaped so the page rhythm doesn't change.
    val ok = Color(0xFF1B5E20)
    val onOk = Color.White
    val err = cs.errorContainer
    val onErr = cs.onErrorContainer

    val (label, leading, containerColor, contentColor, enabled, click) = when {
        state is UploadButtonState.Success -> ButtonView(
            label = "Uploaded",
            leading = ButtonLeading.Icon(Icons.Default.CheckCircle),
            containerColor = ok,
            contentColor = onOk,
            enabled = false,
            click = {},
        )
        state is UploadButtonState.Failed -> ButtonView(
            label = "Retry — ${state.reason.take(40)}",
            leading = ButtonLeading.Icon(Icons.Default.Error),
            containerColor = err,
            contentColor = onErr,
            enabled = true,
            click = onUpload,
        )
        state is UploadButtonState.Uploading -> ButtonView(
            label = "Uploading…",
            leading = ButtonLeading.Spinner,
            containerColor = cs.secondaryContainer,
            contentColor = cs.onSecondaryContainer,
            enabled = false,
            click = {},
        )
        planning -> ButtonView(
            label = "Planning…",
            leading = ButtonLeading.Spinner,
            containerColor = cs.secondaryContainer,
            contentColor = cs.onSecondaryContainer,
            enabled = false,
            click = {},
        )
        else -> ButtonView(
            label = "Upload",
            leading = ButtonLeading.Icon(Icons.Default.CloudUpload),
            containerColor = cs.secondaryContainer,
            contentColor = cs.onSecondaryContainer,
            enabled = hasPlan,
            click = onUpload,
        )
    }

    FilledTonalButton(
        onClick = click,
        enabled = enabled,
        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = contentColor,
        ),
        modifier = Modifier.fillMaxWidth().testTag("upload_button"),
    ) {
        when (leading) {
            ButtonLeading.Spinner -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp).padding(end = 8.dp),
                strokeWidth = 2.dp,
                color = contentColor,
            )
            is ButtonLeading.Icon -> Icon(leading.icon, contentDescription = null)
        }
        Text("  $label")
    }
}

private data class ButtonView(
    val label: String,
    val leading: ButtonLeading,
    val containerColor: Color,
    val contentColor: Color,
    val enabled: Boolean,
    val click: () -> Unit,
)

private sealed interface ButtonLeading {
    data object Spinner : ButtonLeading
    data class Icon(val icon: androidx.compose.ui.graphics.vector.ImageVector) : ButtonLeading
}

private data class Destination(val label: String, val lat: Double, val lon: Double)

private fun setDestination(mapView: MapView, existing: Destination?, next: Destination): Destination {
    mapView.overlays.removeAll { it is Marker && it.title == DEST_MARKER_TITLE }
    val marker = Marker(mapView).apply {
        position = GeoPoint(next.lat, next.lon)
        title = DEST_MARKER_TITLE
        snippet = next.label
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    }
    mapView.overlays.add(marker)
    mapView.invalidate()
    return next
}

private fun clearDestination(mapView: MapView) {
    mapView.overlays.removeAll { it is Marker && it.title == DEST_MARKER_TITLE }
    mapView.overlays.removeAll { it is Polyline }
    mapView.invalidate()
}

private fun clearRouteOverlay(mapView: MapView) {
    mapView.overlays.removeAll { it is Polyline }
    mapView.invalidate()
}

private fun drawRoute(mapView: MapView, gpxBytes: ByteArray) {
    val parsed = try {
        GpxParser.parse(gpxBytes)
    } catch (_: Exception) {
        return
    }
    val poly = Polyline().apply {
        setPoints(parsed.points.map { GeoPoint(it.latitude, it.longitude) })
        outlinePaint.strokeWidth = 8f
    }
    mapView.overlays.add(poly)
    mapView.invalidate()
}

private fun formatDistance(meters: Double): String = when {
    meters < 1_000 -> "${meters.toInt()} m"
    meters < 10_000 -> "%.1f km".format(meters / 1_000.0)
    else -> "${(meters / 1_000.0).toInt()} km"
}

private const val DEST_MARKER_TITLE = "Destination"
