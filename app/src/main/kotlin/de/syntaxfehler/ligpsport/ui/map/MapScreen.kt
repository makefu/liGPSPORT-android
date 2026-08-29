package de.syntaxfehler.ligpsport.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Point as AndroidPoint
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.syntaxfehler.ligpsport.ble.UploadPipeline
import de.syntaxfehler.ligpsport.data.MarkerHitboxPreferences
import de.syntaxfehler.ligpsport.data.PreviousRidesStore
import de.syntaxfehler.ligpsport.data.RouteSessionStore
import de.syntaxfehler.ligpsport.data.RouterPreferences
import de.syntaxfehler.ligpsport.route.GpxParser
import de.syntaxfehler.ligpsport.route.Point
import de.syntaxfehler.ligpsport.route.RouterRegistry
import de.syntaxfehler.ligpsport.route.formatKm
import java.util.UUID
import de.syntaxfehler.ligpsport.search.PhotonClient
import de.syntaxfehler.ligpsport.ui.upload.sanitiseFileName
import kotlinx.coroutines.Dispatchers
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
@Composable
fun MapScreen(
    onOpenPairing: () -> Unit,
    onOpenMenu: () -> Unit,
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
    // Ordered list of intermediate stops between currentLocation and
    // destination. Long-pressing the map drops one; dragging the
    // marker moves it; the auto-plan effect re-routes through them.
    var intermediates by remember {
        mutableStateOf(
            initialSession?.intermediates.orEmpty().mapIndexed { i, s ->
                Waypoint(id = System.nanoTime() + i, lat = s.lat, lon = s.lon, label = s.label)
            },
        )
    }
    // User-edited start point. null = follow the live GPS / mock fix.
    // Set by dragging the Start marker on the map; takes precedence
    // over `currentLocation` in the auto-plan effect.
    var startOverride by remember {
        mutableStateOf(
            initialSession?.let { s ->
                if (s.startLat != null && s.startLon != null) Point(s.startLat, s.startLon) else null
            },
        )
    }
    // Marker hit-area size. Settings-controlled (in dp); recomposes
    // markers whenever the value changes. Re-read on every resume so
    // a tweak in Settings → Map markers takes effect on next return.
    val hitboxPrefs = remember { MarkerHitboxPreferences(ctx) }
    var hitboxSizeDp by remember { mutableStateOf(hitboxPrefs.get()) }
    @Suppress("DEPRECATION")
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hitboxSizeDp = hitboxPrefs.get()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // Live mirror of RouteSessionStore.plannedGpx so the Upload button
    // can react to the most recent plan without re-reading the store on
    // every recomposition. Pre-populated from any restored session.
    var plannedGpx by remember { mutableStateOf(initialSession?.plannedGpx) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var currentLocation by remember { mutableStateOf<Point?>(null) }
    // Set by a map tap once a destination is already in place: opens
    // the [TapActionPopup] anchored to the tapped pixel coordinates.
    // Null when no popup is showing. We stash the GeoPoint plus the
    // pixel coords captured at tap time so the popup placement doesn't
    // drift on subsequent map pans.
    var pendingTap by remember { mutableStateOf<PendingTap?>(null) }
    // Sticky display label for the Start row. Defaults to "Your
    // location"; replaced when the user picks a custom origin via
    // either dragging the start marker or searching the Start row.
    var startLabel by remember { mutableStateOf(initialSession?.startLabel ?: "Your location") }
    // Signature of the *restored* route (destination + vias + start).
    // While the live snapshot still matches, [AutoPlanEffect] skips
    // a re-plan — otherwise opening a previous ride from history would
    // immediately overwrite the saved polyline with a freshly-computed
    // one. The signature is cleared the moment the user edits any
    // stop, so subsequent edits replan as normal.
    var suppressPlanSignature by remember {
        mutableStateOf(
            initialSession?.plannedGpx?.let {
                routeSignature(
                    destination = destination,
                    intermediates = intermediates,
                    startOverride = startOverride,
                )
            },
        )
    }
    // Tracks the last RouteSessionStore session we've replayed into
    // local state. Used by the ON_RESUME observer to detect a fresh
    // restore (e.g. picked from the Previous Rides screen) without
    // re-applying our own outbound writes.
    var lastAppliedSession by remember { mutableStateOf(initialSession) }
    // Drag-end handler for the destination marker. Updates coords but
    // keeps the existing label (a drag isn't a "pick a new place"
    // gesture) and keeps the intermediates list intact (the user is
    // refining the same multi-stop route).
    val onDestDragEnd: (Double, Double) -> Unit = { lat, lon ->
        destination?.let { destination = it.copy(lat = lat, lon = lon) }
    }
    // Drag-end for the Start marker — promotes the live fix to an
    // explicit override so subsequent live-fix updates don't yank the
    // route's origin out from under the user. The Start row's label
    // is then reverse-geocoded asynchronously so the top bar shows
    // the nearest place name; until that round-trip lands we display
    // the raw coordinates so the row never reads "Your location"
    // while in fact pointing somewhere else.
    val onStartDragEnd: (Double, Double) -> Unit = { lat, lon ->
        startOverride = Point(lat, lon)
        startLabel = "%.5f, %.5f".format(lat, lon)
        scope.launch {
            val named = try {
                withContext(Dispatchers.IO) { PhotonClient().reverse(lat, lon) }
            } catch (_: Exception) {
                null
            } ?: return@launch
            val cur = startOverride ?: return@launch
            if (cur.latitude != lat || cur.longitude != lon) return@launch
            startLabel = named.name
        }
    }
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
            setDestination(mapView, null, dest, hitboxSizeDp, onDestDragEnd)
            mapView.controller.animateTo(GeoPoint(s.destinationLat, s.destinationLon))
            s.plannedGpx?.let { drawRoute(mapView, it) }
        }
    }

    // Mirror the full editor state — destination, vias, start
    // override, start label — into [RouteSessionStore] so a round-
    // trip through Settings (where `remember` blocks blow away) or
    // a navigate-back from "Previous rides" keeps every stop.
    //
    // When the destination itself changes (vs. the previously-stored
    // one) the polyline is invalidated — the old route no longer
    // applies — so the Upload button reverts to disabled until the
    // auto-plan computes a fresh one.
    LaunchedEffect(destination, intermediates, startOverride, startLabel) {
        val d = destination
        if (d == null) {
            RouteSessionStore.clear()
            plannedGpx = null
            lastAppliedSession = null
            return@LaunchedEffect
        }
        val cur = RouteSessionStore.get()
        val destChanged = cur == null ||
            cur.destinationLat != d.lat ||
            cur.destinationLon != d.lon ||
            cur.destinationName != d.label
        val vias = intermediates.map { RouteSessionStore.Stop(it.lat, it.lon, it.label) }
        val next = RouteSessionStore.Session(
            destinationName = d.label,
            destinationLat = d.lat,
            destinationLon = d.lon,
            plannedGpx = if (destChanged) null else cur?.plannedGpx,
            intermediates = vias,
            startLat = startOverride?.latitude,
            startLon = startOverride?.longitude,
            startLabel = startLabel,
        )
        RouteSessionStore.set(next)
        lastAppliedSession = next
        if (destChanged) {
            plannedGpx = null
            clearRouteOverlay(mapView)
        }
        // If anything changed away from the suppressed signature,
        // drop the gate so the next AutoPlanEffect tick computes a
        // fresh route. The signature uses 6-decimal coordinate keys
        // — drag jitter under ~10 cm doesn't accidentally re-plan,
        // a real stop add/move does.
        val sigNow = routeSignature(destination, intermediates, startOverride)
        if (suppressPlanSignature != null && suppressPlanSignature != sigNow) {
            suppressPlanSignature = null
        }
    }

    // Watch [RouteSessionStore] across resumes so an external writer
    // (the Previous Rides screen) can drop a fully-formed session in
    // and have MapScreen reflect it without re-creating the activity.
    DisposableEffect(lifecycle, mapView) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event != androidx.lifecycle.Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val cur = RouteSessionStore.get() ?: return@LifecycleEventObserver
            if (cur == lastAppliedSession) return@LifecycleEventObserver
            // Replay onto the live editor + the MapView. Set the
            // mutable states first; the LaunchedEffects keyed on
            // them will refresh their own visuals (start marker,
            // intermediate markers). Polyline + destination marker +
            // camera need an explicit nudge here — they're driven
            // off LaunchedEffect(mapView) which doesn't re-fire on
            // resume.
            val dest = Destination(cur.destinationName, cur.destinationLat, cur.destinationLon)
            destination = dest
            val tBase = System.nanoTime()
            intermediates = cur.intermediates.mapIndexed { i, s ->
                Waypoint(
                    id = tBase + i,
                    lat = s.lat,
                    lon = s.lon,
                    label = s.label,
                )
            }
            startOverride = if (cur.startLat != null && cur.startLon != null) {
                Point(cur.startLat, cur.startLon)
            } else {
                null
            }
            startLabel = cur.startLabel ?: "Your location"
            plannedGpx = cur.plannedGpx
            suppressPlanSignature = cur.plannedGpx?.let {
                routeSignature(dest, intermediates, startOverride)
            }
            lastAppliedSession = cur

            setDestination(mapView, null, dest, hitboxSizeDp, onDestDragEnd)
            clearRouteOverlay(mapView)
            cur.plannedGpx?.let { drawRoute(mapView, it) }
            mapView.controller.animateTo(GeoPoint(dest.lat, dest.lon))
            uploadState = UploadButtonState.Idle
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Any edit to the planned route — destination, vias, or an explicit
    // start override — invalidates the previous upload outcome. Reset
    // the upload-button so it reads "Upload" again once a fresh plan is
    // ready. An in-flight upload is left alone so the result can land.
    RouteEditUploadReset(
        destination = destination,
        intermediates = intermediates,
        startOverride = startOverride,
        uploadState = uploadState,
        onReset = { uploadState = UploadButtonState.Idle },
    )

    // Auto-name vias that don't already carry a Photon label —
    // i.e. those added by long-pressing the map (the inline geocode
    // there can race or fail) and those whose label was cleared by a
    // marker drag. The brief asked vias to read their nearest place
    // name, same as start / destination, instead of "Stop N".
    //
    // Tracked per-id so a drag of via A doesn't fire a duplicate
    // Photon call for via B that's still in flight. Stale ids
    // (vias that were removed) are pruned each tick.
    val viaGeocodes = remember { androidx.compose.runtime.mutableStateMapOf<Long, Pair<Double, Double>>() }
    LaunchedEffect(intermediates) {
        viaGeocodes.keys.toList().forEach { id ->
            if (intermediates.none { it.id == id }) viaGeocodes.remove(id)
        }
        intermediates.forEach { via ->
            if (via.label != null) return@forEach
            val key = via.lat to via.lon
            if (viaGeocodes[via.id] == key) return@forEach
            viaGeocodes[via.id] = key
            scope.launch {
                val named = try {
                    withContext(Dispatchers.IO) { PhotonClient().reverse(via.lat, via.lon) }
                } catch (_: Exception) { null } ?: return@launch
                intermediates = intermediates.map { existing ->
                    if (existing.id == via.id &&
                        existing.lat == via.lat &&
                        existing.lon == via.lon &&
                        existing.label == null
                    ) {
                        existing.copy(label = named.name)
                    } else {
                        existing
                    }
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

    // Picks the tapped point as the destination. Renders a marker with
    // a provisional "lat, lon" label, then upgrades the label
    // asynchronously via Photon reverse-geocoding. The previous
    // intermediates list is preserved — a Google-Maps-style flow lets
    // the user keep their vias when refining the destination.
    val pickDestinationFromGeo: (GeoPoint) -> Unit = pick@{ p ->
        val provisional = Destination(
            label = "%.5f, %.5f".format(p.latitude, p.longitude),
            lat = p.latitude,
            lon = p.longitude,
        )
        setDestination(mapView, destination, provisional, hitboxSizeDp, onDestDragEnd)
        destination = provisional
        scope.launch {
            val named = try {
                withContext(Dispatchers.IO) { PhotonClient().reverse(p.latitude, p.longitude) }
            } catch (_: Exception) {
                null
            } ?: return@launch
            val cur = destination ?: return@launch
            if (cur.lat != p.latitude || cur.lon != p.longitude) return@launch
            val upgraded = Destination(named.name, p.latitude, p.longitude)
            setDestination(mapView, cur, upgraded, hitboxSizeDp, onDestDragEnd)
            destination = upgraded
        }
    }
    // Appends the tapped point as a new intermediate. Reverse-geocodes
    // the position so the top bar's stop row shows the nearest place
    // name instead of "Stop N". Only meaningful when a destination
    // already exists; the caller is responsible for that guard.
    val addIntermediateFromGeo: (GeoPoint) -> Unit = add@{ p ->
        val newId = System.nanoTime()
        intermediates = intermediates + Waypoint(
            id = newId,
            lat = p.latitude,
            lon = p.longitude,
            label = null,
        )
        scope.launch {
            val named = try {
                withContext(Dispatchers.IO) { PhotonClient().reverse(p.latitude, p.longitude) }
            } catch (_: Exception) {
                null
            } ?: return@launch
            intermediates = intermediates.map { existing ->
                if (existing.id == newId && existing.lat == p.latitude && existing.lon == p.longitude) {
                    existing.copy(label = named.name)
                } else {
                    existing
                }
            }
        }
    }

    DisposableEffect(mapView) {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (p == null) return false
                if (destination == null) {
                    // First-ever pick: treat as the destination.
                    pickDestinationFromGeo(p)
                } else {
                    // Subsequent tap with a route in progress: surface
                    // the Set-destination / Add-stop popup at the tap
                    // pixel. Convert the GeoPoint to pixel coords now
                    // so the popup placement doesn't drift if the user
                    // pans the map before choosing.
                    val pix = mapView.projection.toPixels(p, null)
                    pendingTap = PendingTap(p, pix.x, pix.y)
                }
                return true
            }
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p == null) return false
                // Long-press remains a quick shortcut for "add an
                // intermediate stop" — bypassing the popup matches
                // Google Maps' muscle memory. Only meaningful once a
                // destination exists.
                if (destination == null) return false
                addIntermediateFromGeo(p)
                return true
            }
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

        // Google-Maps-style stops sheet, with the status toast stacked
        // directly beneath it. They share a single TopCenter column so
        // the toast always flows below whatever height the bar happens
        // to take (one row in empty state, four-plus rows once vias are
        // added) — previously the toast was anchored to a hard-coded
        // top padding tuned for the old single-line search bar, which
        // made it overlap the multi-row stops sheet.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RouteStopsBar(
                destination = destination,
                intermediates = intermediates,
                startLabel = startLabel,
                startIsOverride = startOverride != null,
                currentLocation = currentLocation,
                onDestinationPicked = { result ->
                    val picked = Destination(result.name, result.latitude, result.longitude)
                    destination = setDestination(mapView, destination, picked, hitboxSizeDp, onDestDragEnd)
                    mapView.controller.animateTo(GeoPoint(picked.lat, picked.lon))
                },
                onStartChange = { result ->
                    startOverride = Point(result.latitude, result.longitude)
                    startLabel = result.name
                    mapView.controller.animateTo(GeoPoint(result.latitude, result.longitude))
                },
                onStartClear = {
                    startOverride = null
                    startLabel = "Your location"
                },
                onViaChange = { id, result ->
                    intermediates = intermediates.map {
                        if (it.id == id) it.copy(
                            lat = result.latitude,
                            lon = result.longitude,
                            label = result.name,
                        ) else it
                    }
                },
                onViaRemove = { id ->
                    intermediates = intermediates.filterNot { it.id == id }
                },
                onViaReorder = { from, to ->
                    val safeFrom = from.coerceIn(0, intermediates.lastIndex)
                    val safeTo = to.coerceIn(0, intermediates.lastIndex)
                    if (safeFrom == safeTo) return@RouteStopsBar
                    intermediates = intermediates.toMutableList().also { list ->
                        val item = list.removeAt(safeFrom)
                        list.add(safeTo, item)
                    }
                },
                onAddStop = { result ->
                    intermediates = intermediates + Waypoint(
                        id = System.nanoTime(),
                        lat = result.latitude,
                        lon = result.longitude,
                        label = result.name,
                    )
                },
                onSwapStartAndDestination = onSwap@{
                    val dest = destination ?: return@onSwap
                    val effectiveStart = startOverride ?: currentLocation
                    if (effectiveStart == null) {
                        statusMessage = "Waiting for GPS fix to swap…"
                        return@onSwap
                    }
                    val oldStartLabel = startLabel
                    // Destination becomes the start; the old start
                    // (live fix or override) becomes the destination.
                    // Vias reverse so the new geometry doesn't double
                    // back — matches Google Maps' swap-arrows behaviour.
                    val newDest = Destination(
                        label = oldStartLabel,
                        lat = effectiveStart.latitude,
                        lon = effectiveStart.longitude,
                    )
                    destination = setDestination(mapView, dest, newDest, hitboxSizeDp, onDestDragEnd)
                    startOverride = Point(dest.lat, dest.lon)
                    startLabel = dest.label
                    intermediates = intermediates.reversed()
                    mapView.controller.animateTo(GeoPoint(newDest.lat, newDest.lon))
                },
            )

            statusMessage?.let { msg ->
                Surface(
                    modifier = Modifier
                        .clickable { statusMessage = null }
                        .testTag("status_toast"),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(msg, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }

        // Tap-action popup, anchored to the left of the tapped pixel
        // when a destination is already in place. Closed by either
        // choosing an action or tapping outside the popup.
        pendingTap?.let { tap ->
            TapActionPopup(
                pixelX = tap.pixelX,
                pixelY = tap.pixelY,
                onSetDestination = {
                    pickDestinationFromGeo(tap.geo)
                    pendingTap = null
                },
                onAddStop = {
                    addIntermediateFromGeo(tap.geo)
                    pendingTap = null
                },
                onDismiss = { pendingTap = null },
            )
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
            onOpenMenu = onOpenMenu,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 24.dp),
        )

        // Auto-plan whenever the destination, the list of intermediate
        // stops, or the explicit start override changes — or when the
        // GPS arrives for the first time. The Plan button is gone —
        // any edit (new destination, new intermediate, dragged marker)
        // is the user's commitment, and a route preview is the
        // immediate feedback. They still get to back out before Upload.
        //
        // We key off a `hasInitialFix` boolean rather than
        // `currentLocation` itself so subsequent GPS drift (the
        // overlay polls every 2 s) does NOT re-fire the planner.
        // The route is to a static destination, not a follow-me nav
        // update — without this guard, the UI thrashed between
        // "Planning…" and "Route ready" forever and the user could
        // never tap Upload. If the user genuinely wants to re-plan
        // from a different origin, the Start marker is draggable
        // (see [setStartMarker] / [startOverride]).
        AutoPlanEffect(
            destination = destination,
            intermediates = intermediates,
            startOverride = startOverride,
            currentLocation = currentLocation,
            suppressSignature = suppressPlanSignature,
            onNoFix = { statusMessage = "Waiting for GPS fix…" },
            onPlan = onPlan@{ start, dest, via ->
                val provider = RouterRegistry.byId(RouterPreferences(ctx).get())
                    ?: RouterRegistry.default
                statusMessage = "Planning route via ${provider.displayName}…"
                planningRoute = true
                try {
                    val end = Point(dest.lat, dest.lon)
                    val gpx = withContext(Dispatchers.IO) {
                        provider.planGpx(start, end, intermediates = via)
                    }
                    clearRouteOverlay(mapView)
                    drawRoute(mapView, gpx)
                    RouteSessionStore.setPlannedGpx(gpx)
                    plannedGpx = gpx
                    statusMessage = if (via.isEmpty()) {
                        "Route ready — tap Upload to send."
                    } else {
                        "Route via ${via.size} stop${if (via.size == 1) "" else "s"} ready."
                    }
                } catch (e: Exception) {
                    statusMessage = "Routing failed: ${e.message}"
                } finally {
                    planningRoute = false
                }
            },
        )

        // Render the Start marker. Defaults to the live fix; once the
        // user drags it, [startOverride] takes precedence so subsequent
        // GPS updates don't reset the planned origin. Hidden when there
        // is no destination (nothing to route from yet) and there's no
        // override — the blue MyLocation dot is enough on its own.
        LaunchedEffect(destination, startOverride, currentLocation, hitboxSizeDp) {
            val markerAt = startOverride
                ?: if (destination != null) currentLocation else null
            setStartMarker(mapView, markerAt, hitboxSizeDp, onStartDragEnd)
        }

        // Re-render intermediate markers whenever the list changes
        // OR the hitbox preference changes (so the new size applies
        // without requiring a re-tap of every waypoint).
        LaunchedEffect(intermediates, hitboxSizeDp) {
            setIntermediates(
                mapView = mapView,
                waypoints = intermediates,
                hitboxSizeDp = hitboxSizeDp,
                onMove = { id, lat, lon ->
                    // Dragging invalidates the previous reverse-
                    // geocoded label — the place name is for the old
                    // coords. Clear it; the auto-geocode effect below
                    // will fetch a fresh one for the new position.
                    intermediates = intermediates.map {
                        if (it.id == id) it.copy(lat = lat, lon = lon, label = null) else it
                    }
                },
            )
        }

        // Re-render the destination marker too when the hitbox setting
        // changes. Avoids the user having to clear + re-pick a
        // destination just for the new hit area to take effect.
        LaunchedEffect(hitboxSizeDp) {
            destination?.let {
                setDestination(mapView, null, it, hitboxSizeDp, onDestDragEnd)
            }
        }

        // Bottom card appears as soon as a destination is set.
        destination?.let { dest ->
            val uploading = uploadState is UploadButtonState.Uploading
            // Parse the GPX once per byte-array identity to extract the
            // total distance — cheap, but a recomposition every 2 s
            // (currentLocation refresh) would still add up.
            val routeKm = remember(plannedGpx) {
                plannedGpx?.let { bytes ->
                    runCatching { GpxParser.parse(bytes).distanceM }.getOrNull()
                }
            }
            DestinationCard(
                destination = dest,
                planning = planningRoute,
                hasPlan = plannedGpx != null,
                distanceM = routeKm,
                uploadState = uploadState,
                // Block the X button while the upload is in flight —
                // clearing the destination during upload would tear
                // down the card mid-progress and lose the result
                // surface. The user can clear after success/failure.
                onClear = if (uploading) null else {
                    {
                        destination = null
                        plannedGpx = null
                        intermediates = emptyList()
                        startOverride = null
                        startLabel = "Your location"
                        statusMessage = null
                        uploadState = UploadButtonState.Idle
                        clearDestination(mapView)
                    }
                },
                onUpload = onUpload@{
                    val gpx = plannedGpx ?: return@onUpload
                    val fileName = sanitiseFileName(dest.label) ?: "route"
                    // Snapshot the full route the user committed to. If
                    // any of these change while the upload is in flight
                    // — destination drag, via add/move, start drag —
                    // the BLE round-trip's bytes no longer match what's
                    // on screen, so we surface Idle (re-Upload) instead
                    // of a misleading "Uploaded ✓".
                    val uploadedLat = dest.lat
                    val uploadedLon = dest.lon
                    val uploadedVias = intermediates
                    val uploadedStart = startOverride
                    uploadState = UploadButtonState.Uploading
                    scope.launch {
                        // Fan out to every paired device. BleSessionManager
                        // holds one shared connection per MAC and leases it
                        // exclusively, so the upload can't collide with the
                        // nav-status poll running against the same computer;
                        // different MACs still upload in parallel. The
                        // MAC-keyed map lets us distinguish "all good" from
                        // "device 3 was off". Single-device users see
                        // exactly the same behaviour as before — the map has
                        // one entry, which collapses cleanly into the
                        // existing Success / Failed states.
                        val results = withContext(Dispatchers.IO) {
                            UploadPipeline.uploadGpxAll(ctx, gpx, fileName = fileName)
                        }
                        val current = destination
                        val routeChanged = current == null ||
                            current.lat != uploadedLat ||
                            current.lon != uploadedLon ||
                            intermediates != uploadedVias ||
                            startOverride != uploadedStart
                        uploadState = if (routeChanged) {
                            UploadButtonState.Idle
                        } else if (results.isEmpty()) {
                            UploadButtonState.Failed("no paired device")
                        } else {
                            val successes = results.count { it.value is UploadPipeline.Result.Success }
                            // Connect-level retries already happened inside
                            // BleSessionManager. If at least one device
                            // acked, surface a clean Success —
                            // suppress the partial-fail breakdown that
                            // pestered users when the second computer was
                            // legitimately switched off.
                            when {
                                successes > 0 -> {
                                    saveCurrentRide(
                                        context = ctx,
                                        destination = dest,
                                        startLabel = startLabel,
                                        startOverride = uploadedStart,
                                        vias = uploadedVias,
                                        gpx = gpx,
                                        fileName = fileName,
                                    )
                                    UploadButtonState.Success(
                                        successes = successes,
                                        total = results.size,
                                    )
                                }
                                else -> UploadButtonState.Failed("upload failed")
                            }
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }

    }
}

/**
 * Bottom-right FAB stack: the navigation menu (always) + my-location
 * (only when a fix is available). Internal-visible so a Compose UI test
 * can exercise the visibility gating without pulling in the rest of
 * MapScreen.
 *
 * The menu FAB replaced a dedicated settings one: the map is full-bleed
 * with no app bar to hang a hamburger off, and Settings is now a drawer
 * item like the rest.
 */
@Composable
internal fun BottomEndFabs(
    currentLocation: Point?,
    onMyLocation: (Point) -> Unit,
    onOpenMenu: () -> Unit,
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
            onClick = onOpenMenu,
            modifier = Modifier.testTag("menu_fab"),
        ) {
            Icon(Icons.Default.Menu, contentDescription = "Menu")
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
    /** [successes] devices acked out of [total] attempted. Surfaced
     *  as "Uploaded (N/M)" so a partial result (e.g. 1 of 2 paired
     *  devices was off) is visible without nagging error text. */
    data class Success(val successes: Int, val total: Int) : UploadButtonState
    data class Failed(val reason: String) : UploadButtonState
}

@Composable
private fun DestinationCard(
    destination: Destination,
    planning: Boolean,
    hasPlan: Boolean,
    distanceM: Double?,
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
                    val coordsLine = "%.5f, %.5f".format(destination.lat, destination.lon)
                    val combined = distanceM?.let { "$coordsLine  ·  ${formatKm(it)}" } ?: coordsLine
                    Text(
                        combined,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("destination_subtitle"),
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
            label = "Uploaded (${state.successes}/${state.total})",
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

internal data class Destination(val label: String, val lat: Double, val lon: Double)

/**
 * One-shot record of a map tap that should surface the
 * Set-destination / Add-stop popup. Captures both the GeoPoint (so
 * the eventual pick still routes to the right place if the map has
 * panned) and the pixel coordinates at tap time (so the popup
 * anchors to where the user actually pressed, not the new screen
 * position of the GeoPoint after a pan).
 */
internal data class PendingTap(val geo: GeoPoint, val pixelX: Int, val pixelY: Int)

/**
 * Auto-plan the route whenever the user's *intent* changes —
 * destination, vias, or an explicit start override — and once when
 * the first GPS fix arrives. Subsequent drift in [currentLocation]
 * (the location overlay polls every 2 s) does NOT re-fire [onPlan]:
 * the planned route is for a static destination, and constant
 * re-planning thrashed the status pill between "Planning…" and
 * "Route ready", preventing the user from ever tapping Upload.
 *
 * The body still reads the latest [currentLocation] when it fires,
 * so picking a destination after the GPS has been stable for a
 * while still routes from where the user is right now.
 */
@Composable
internal fun AutoPlanEffect(
    destination: Destination?,
    intermediates: List<Waypoint>,
    startOverride: Point?,
    currentLocation: Point?,
    onPlan: suspend (start: Point, dest: Destination, vias: List<Point>) -> Unit,
    onNoFix: () -> Unit = {},
    /** When the live (destination, vias, start) snapshot equals this
     *  signature, suppress re-planning. Used after restoring a saved
     *  ride from history so the user sees the *saved* polyline, not a
     *  freshly-computed (and possibly subtly different) one. Any edit
     *  changes the signature → suppression lifts → plan as normal. */
    suppressSignature: String? = null,
) {
    val hasInitialFix = currentLocation != null
    LaunchedEffect(destination, intermediates, startOverride, hasInitialFix, suppressSignature) {
        val dest = destination ?: return@LaunchedEffect
        val start = startOverride ?: currentLocation
        if (start == null) {
            onNoFix()
            return@LaunchedEffect
        }
        if (suppressSignature != null &&
            suppressSignature == routeSignature(destination, intermediates, startOverride)
        ) {
            return@LaunchedEffect
        }
        onPlan(start, dest, intermediates.map { Point(it.lat, it.lon) })
    }
}

/**
 * Persist a successfully-uploaded ride into [PreviousRidesStore].
 * Called whenever the fan-out upload reports at least one device
 * acked — see the "any device acked" save criterion. Distance comes
 * from a fresh GPX parse rather than the live [RouteData] cache
 * because the cache lives in the planner and isn't surfaced here.
 */
private fun saveCurrentRide(
    context: android.content.Context,
    destination: Destination,
    startLabel: String,
    startOverride: Point?,
    vias: List<Waypoint>,
    gpx: ByteArray,
    fileName: String,
) {
    val distance = runCatching { GpxParser.parse(gpx).distanceM }.getOrDefault(0.0)
    PreviousRidesStore(context).add(
        PreviousRidesStore.Ride(
            id = UUID.randomUUID().toString(),
            savedAt = System.currentTimeMillis(),
            destinationLabel = destination.label,
            destinationLat = destination.lat,
            destinationLon = destination.lon,
            startLabel = startLabel,
            startLat = startOverride?.latitude,
            startLon = startOverride?.longitude,
            intermediates = vias.map {
                PreviousRidesStore.SavedWaypoint(it.lat, it.lon, it.label)
            },
            routerId = RouterPreferences(context).get(),
            fileName = fileName,
            gpxBase64 = PreviousRidesStore.encodeGpx(gpx),
            distanceM = distance,
        ),
    )
}

/**
 * Stable hash of the route's editable inputs. Used to decide whether
 * the current snapshot still matches a restored ride (in which case
 * we leave the saved polyline alone) or has drifted because the user
 * edited a stop. 6-decimal precision matches Photon coordinates so
 * marker drag-jitter under ~10 cm doesn't flip the signature.
 */
internal fun routeSignature(
    destination: Destination?,
    intermediates: List<Waypoint>,
    startOverride: Point?,
): String {
    val d = destination?.let { "%.6f,%.6f".format(it.lat, it.lon) } ?: "null"
    val v = intermediates.joinToString("|") { "%.6f,%.6f".format(it.lat, it.lon) }
    val s = startOverride?.let { "%.6f,%.6f".format(it.latitude, it.longitude) } ?: "live"
    return "$d#$v#$s"
}

/**
 * Reset the upload-button outcome whenever the planned route is
 * edited (destination, vias, or an explicit start override). Without
 * this, dragging an intermediate or the start marker after a
 * successful upload left the button stuck on "Uploaded ✓" while the
 * actual plan on screen had moved on — the user couldn't tell their
 * new route hadn't been sent.
 *
 * An in-flight upload (Uploading) is left alone so its result can
 * still land on the button; the route-change check baked into the
 * upload's completion handler will surface Idle in that case.
 */
@Composable
internal fun RouteEditUploadReset(
    destination: Destination?,
    intermediates: List<Waypoint>,
    startOverride: Point?,
    uploadState: UploadButtonState,
    onReset: () -> Unit,
) {
    LaunchedEffect(destination, intermediates, startOverride) {
        if (uploadState is UploadButtonState.Success ||
            uploadState is UploadButtonState.Failed
        ) {
            onReset()
        }
    }
}

/**
 * One intermediate stop the route must pass through. Holds a stable
 * [id] so Compose recompositions don't lose track of which marker
 * corresponds to which entry when the list is reshuffled by adding /
 * dragging / removing.
 */
internal data class Waypoint(
    val id: Long,
    val lat: Double,
    val lon: Double,
    /**
     * Reverse-geocoded place name (Photon's "nearest point"). Null
     * while the geocoding round-trip is in flight or if it failed —
     * the top-bar row falls back to "Stop N" in that case. Kept
     * optional so existing instrumented tests that pass
     * `Waypoint(id, lat, lon)` still compile.
     */
    val label: String? = null,
)

/**
 * Replaces the destination marker with a fresh draggable one. The
 * marker reports drag-end through [onDragEnd] so MapScreen can
 * update its `destination` state and the auto-plan effect can
 * re-route. The dragged marker keeps the previous label so a drag
 * doesn't strip the reverse-geocoded place name.
 */
private fun setDestination(
    mapView: MapView,
    existing: Destination?,
    next: Destination,
    hitboxSizeDp: Int,
    onDragEnd: ((lat: Double, lon: Double) -> Unit)? = null,
): Destination {
    mapView.overlays.removeAll { it is Marker && it.title == DEST_MARKER_TITLE }
    val marker = draggableMarker(
        mapView = mapView,
        titleField = DEST_MARKER_TITLE,
        snippetField = next.label,
        position = GeoPoint(next.lat, next.lon),
        hitboxSizeDp = hitboxSizeDp,
        onDragEnd = onDragEnd,
    )
    mapView.overlays.add(marker)
    mapView.invalidate()
    return next
}

private fun clearDestination(mapView: MapView) {
    mapView.overlays.removeAll { it is Marker && it.title == DEST_MARKER_TITLE }
    mapView.overlays.removeAll { it is Marker && it.title == START_MARKER_TITLE }
    mapView.overlays.removeAll { it is Marker && it.title?.startsWith(INTERMEDIATE_MARKER_PREFIX) == true }
    mapView.overlays.removeAll { it is Polyline }
    mapView.invalidate()
}

/**
 * Re-render the full set of intermediate-stop markers. Draggable so
 * the user can refine each stop's position. Removal is no longer a
 * marker-tap gesture — that moved to the top bar's X button when the
 * stops sheet replaced the single search bar; tapping the marker now
 * does nothing on purpose so a stray tap can't silently drop a stop.
 * Cheaper to wipe and re-add than to diff: 0–8 markers per redraw.
 */
private fun setIntermediates(
    mapView: MapView,
    waypoints: List<Waypoint>,
    hitboxSizeDp: Int,
    onMove: (id: Long, lat: Double, lon: Double) -> Unit,
) {
    mapView.overlays.removeAll { it is Marker && it.title?.startsWith(INTERMEDIATE_MARKER_PREFIX) == true }
    for ((index, w) in waypoints.withIndex()) {
        val m = draggableMarker(
            mapView = mapView,
            titleField = "$INTERMEDIATE_MARKER_PREFIX${w.id}",
            snippetField = w.label ?: "Stop ${index + 1}",
            position = GeoPoint(w.lat, w.lon),
            hitboxSizeDp = hitboxSizeDp,
            onDragEnd = { lat, lon -> onMove(w.id, lat, lon) },
        )
        mapView.overlays.add(m)
    }
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

private const val DEST_MARKER_TITLE = "Destination"
private const val START_MARKER_TITLE = "Start"
private const val INTERMEDIATE_MARKER_PREFIX = "Stop#"

/**
 * A [Marker] subclass that widens the touch hit-test well beyond
 * the visible icon's bounds. osmdroid's default `hitTest` only
 * accepts touches inside the icon's pixel rect — for the small
 * default pin that's a ~30×45 dp target, well under the
 * Material-recommended 48 dp. We override it with a fixed-dp box
 * sized for the pin's bottom-anchored geometry: a wide area
 * around the visible pin body, extending a touch below the tip
 * so a slightly low finger still grabs the marker.
 *
 * The visible icon is unchanged — only the touchable area grows.
 */
private class WideHitMarker(mapView: MapView, hitboxSizeDp: Int) : Marker(mapView) {
    private val density = mapView.context.resources.displayMetrics.density
    // Bias the box: for the bottom-anchored pin, more area above the
    // tip (pin body lives there) and a slack strip below.
    private val xHalfPx = (hitboxSizeDp / 2f * density).toInt()
    private val yAbovePx = (hitboxSizeDp * 3 / 4f * density).toInt()
    private val yBelowPx = (hitboxSizeDp / 4f * density).toInt()
    private val scratch = AndroidPoint()

    override fun hitTest(event: MotionEvent, mapView: MapView): Boolean {
        mapView.projection.toPixels(position, scratch)
        val dx = event.x - scratch.x
        val dy = event.y - scratch.y
        // Position is the pin tip (ANCHOR_CENTER/ANCHOR_BOTTOM). Pin
        // body extends upward from there, so accept a tall box biased
        // above the tip plus a small slack below for off-tip taps.
        return dx >= -xHalfPx && dx <= xHalfPx &&
            dy >= -yAbovePx && dy <= yBelowPx
    }
}

/**
 * Build a draggable Marker with a wide hit area (see
 * [WideHitMarker]). Used uniformly by Start / Destination /
 * Intermediate markers so they all feel the same to the touch.
 * Visuals use osmdroid's stock pin — the only change vs. plain
 * `Marker` is the larger hitbox.
 */
private fun draggableMarker(
    mapView: MapView,
    titleField: String,
    snippetField: String,
    position: GeoPoint,
    hitboxSizeDp: Int,
    onDragEnd: ((lat: Double, lon: Double) -> Unit)?,
    onClick: (() -> Unit)? = null,
): Marker {
    return WideHitMarker(mapView, hitboxSizeDp).apply {
        this.position = position
        title = titleField
        snippet = snippetField
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        if (onDragEnd != null) {
            isDraggable = true
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDragStart(marker: Marker) {}
                override fun onMarkerDrag(marker: Marker) {}
                override fun onMarkerDragEnd(marker: Marker) {
                    onDragEnd(marker.position.latitude, marker.position.longitude)
                }
            })
        }
        if (onClick != null) {
            setOnMarkerClickListener { _, _ ->
                onClick(); true
            }
        }
    }
}

/**
 * Render (or remove, when [at] is null) the editable Start marker.
 * Draggable so the user can plan from a fictional origin without
 * physically moving — dragging promotes the live fix to a sticky
 * override, mirroring Google Maps' "drag your starting point".
 */
private fun setStartMarker(
    mapView: MapView,
    at: Point?,
    hitboxSizeDp: Int,
    onDragEnd: (lat: Double, lon: Double) -> Unit,
) {
    mapView.overlays.removeAll { it is Marker && it.title == START_MARKER_TITLE }
    if (at == null) {
        mapView.invalidate()
        return
    }
    val m = draggableMarker(
        mapView = mapView,
        titleField = START_MARKER_TITLE,
        snippetField = "Drag to change start",
        position = GeoPoint(at.latitude, at.longitude),
        hitboxSizeDp = hitboxSizeDp,
        onDragEnd = onDragEnd,
    )
    mapView.overlays.add(m)
    mapView.invalidate()
}
