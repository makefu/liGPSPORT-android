package de.syntaxfehler.ligpsport.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import de.syntaxfehler.ligpsport.BuildConfig
import de.syntaxfehler.ligpsport.agps.AgpsClient
import de.syntaxfehler.ligpsport.data.AgpsSeedStore
import de.syntaxfehler.ligpsport.data.AgpsTokenStore
import de.syntaxfehler.ligpsport.data.MockLocationStore
import de.syntaxfehler.ligpsport.data.RouterPreferences
import de.syntaxfehler.ligpsport.route.CnxEncoder
import de.syntaxfehler.ligpsport.route.FitFile
import de.syntaxfehler.ligpsport.route.GpxParser
import de.syntaxfehler.ligpsport.route.Point
import de.syntaxfehler.ligpsport.route.RouteData
import de.syntaxfehler.ligpsport.route.RouteProvider
import de.syntaxfehler.ligpsport.route.RouterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

/**
 * High-level orchestration: GPX → CNX → BLE upload, plus the
 * complementary primitives (pair / delete / plan / list) used by both
 * the in-app UI and the adb broadcast harness.
 *
 * Every helper borrows its device link from [BleSessionManager] via
 * [withDevice], so callers don't need to manage GATT lifetime and — more
 * importantly — two helpers can never hold overlapping connections to the
 * same computer. See the class docs on [BleSessionManager] for why that
 * used to break uploads.
 */
object UploadPipeline {
    private const val TAG = "UploadPipeline"

    sealed class Result {
        data class Success(
            val status: Int = 0,
            val bytesSent: Int = 0,
            val deviceName: String? = null,
            val deviceMac: String? = null,
            val fileId: Long? = null,
            val points: Int? = null,
            val providerId: String? = null,
            val routes: List<FileTransfer.RouteEntry> = emptyList(),
            /** Number of AGPS bytes piggybacked before this upload,
             *  null when AGPS was skipped (no token / fetch error). */
            val agpsBytes: Int? = null,
            /** Coordinates that were injected as a position prior
             *  (FACTORY GPS_COORDINATE_SET) piggybacked alongside the
             *  upload, null when skipped (no fix / device rejected). */
            val seedLat: Double? = null,
            val seedLon: Double? = null,
            /** True when the post-upload ROUTE_PLAN FILE_USE landed
             *  cleanly and the device entered navigation mode. False
             *  when the upload succeeded but the device refused the
             *  activation (route stays on the device for later
             *  manual selection). Null when the navigation step
             *  wasn't requested. */
            val navStarted: Boolean? = null,
            /** Current device navigation status from ROUTE_PLAN
             *  LIST_GET — populated by `navStatus()`. */
            val navStatus: FileTransfer.NavStatus? = null,
            /** Recorded activities returned by [listActivities]. */
            val activities: List<FileTransfer.ActivityListEntry> = emptyList(),
            /** Activity FIT byte count, populated by
             *  [downloadActivity]. */
            val activityBytes: Int? = null,
            /** Filename the device echoed in `file_download.file_name`
             *  (may be empty); populated by [downloadActivity]. */
            val activityFileName: String? = null,
            /** Absolute path on the phone where the downloaded FIT
             *  was written; populated by [downloadActivity]. */
            val activitySavedPath: String? = null,
            /** Wire timestamp of the activity touched by
             *  download/delete operations. */
            val activityTimestamp: Long? = null,
        ) : Result()
        data class Failure(val reason: String, val status: Int = -1) : Result()
    }

    // ---- Scan / pair --------------------------------------------------

    @SuppressLint("MissingPermission")
    suspend fun pairFirst(
        context: Context,
        timeoutMs: Long = 15_000,
    ): Result {
        val adapter = bluetoothAdapter(context) ?: return Result.Failure("Bluetooth not available")
        if (!adapter.isEnabled) return Result.Failure("Bluetooth is off — enable it and retry")
        val scanner = DeviceScanner(adapter)
        val hit = withTimeoutOrNull(timeoutMs) { scanner.scan().firstOrNull() }
            ?: return Result.Failure("no iGPSPORT device found within ${timeoutMs}ms")
        // save() replaces every pairing, so drop the cached links of the
        // devices it just dropped instead of holding their radios open
        // until the idle reaper fires.
        BleSessionManager.forgetAll()
        DeviceStore(context).save(name = hit.name, address = hit.address)
        return Result.Success(deviceName = hit.name, deviceMac = hit.address)
    }

    // ---- Plan + upload -----------------------------------------------

    /**
     * Plan a route from [start] (or the current/mocked location when
     * [start] is null) to [end] using the currently-configured
     * [RouteProvider]. Then upload the resulting CNX.
     */
    suspend fun planAndUpload(
        context: Context,
        end: Point,
        start: Point? = null,
        profile: String = "trekking",
        fileId: Long = System.currentTimeMillis() / 1000L,
        fileName: String = "route",
        providerOverride: RouteProvider? = null,
        targetMac: String? = null,
    ): Result {
        val provider = providerOverride
            ?: RouterRegistry.byId(RouterPreferences(context).get())
            ?: RouterRegistry.default
        val resolvedStart: Point = start ?: resolveCurrentLocation(context)
            ?: return Result.Failure("no GPS fix — set a mock location or wait for a real fix")
        Log.i(TAG, "plan: provider=${provider.id} start=${resolvedStart.latitude},${resolvedStart.longitude} end=${end.latitude},${end.longitude}")
        val t0 = System.currentTimeMillis()
        val gpx = try {
            provider.planGpx(resolvedStart, end, profile = profile)
        } catch (e: Exception) {
            Log.e(TAG, "plan: ${provider.id} failed", e)
            return Result.Failure("${provider.id} failed: ${e.message}")
        }
        Log.i(TAG, "plan: ${provider.id} returned ${gpx.size} bytes in ${System.currentTimeMillis() - t0}ms")
        val upload = uploadGpx(context, gpx, fileId = fileId, fileName = fileName, targetMac = targetMac)
        return when (upload) {
            is Result.Success -> upload.copy(providerId = provider.id)
            is Result.Failure -> upload
        }
    }

    // ---- Upload ------------------------------------------------------

    @SuppressLint("MissingPermission")
    suspend fun uploadGpx(
        context: Context,
        gpxBytes: ByteArray,
        fileId: Long = System.currentTimeMillis() / 1000L,
        fileName: String = "route",
        targetMac: String? = null,
    ): Result {
        val paired = resolvePaired(context, targetMac) ?: return Result.Failure("no paired device")

        val route: RouteData = try {
            GpxParser.parse(gpxBytes)
        } catch (e: Exception) {
            return Result.Failure("GPX parse failed: ${e.message}")
        }
        Log.i(TAG, "upload: parsed ${route.points.size} GPX points")
        val tEnc = System.currentTimeMillis()
        val cnx: ByteArray = try {
            CnxEncoder.encode(route, routeId = fileId)
        } catch (e: Exception) {
            return Result.Failure("CNX encode failed: ${e.message}")
        }
        Log.i(TAG, "upload: cnx encode ${cnx.size}B in ${System.currentTimeMillis() - tEnc}ms")

        // Resolve the off-device inputs *before* taking the device lease.
        // Both an AssistNow HTTP round-trip and a high-accuracy GPS fix
        // can take several seconds, and holding the device's exclusive
        // lease for them would stall the nav-status poll (and, when
        // fanning out, nothing else — the lease is per MAC) for no reason.
        //
        // The AGPS half is skipped entirely — fetch included — when this
        // device already holds assistance data that hasn't expired yet.
        val seedStore = AgpsSeedStore(context)
        val agpsData = if (seedStore.isFresh(paired.mac)) {
            val ageMin = (System.currentTimeMillis() - (seedStore.get(paired.mac)?.seededAt ?: 0L)) / 60_000
            Log.i(TAG, "agps: skipping ${paired.mac} — seeded ${ageMin}min ago, ttl ${seedStore.ttlMs / 60_000}min")
            null
        } else {
            fetchAgpsOrNull(context)
        }
        val fix = resolveCurrentLocation(context)

        return withDevice(context, paired) { transport, _ ->
            // Piggyback AGPS seed before the route so the device gets
            // assistance data while the user is still putting the bike
            // away. Best-effort: a fetch failure doesn't fail the
            // route upload — we just log it and proceed.
            val agpsBytes = agpsData?.let {
                pushAgpsBestEffort(transport, it, paired.mac, seedStore)
            }

            // Inject the phone's current location as a starting-point
            // prior. AGPS supplies "which satellite is where in orbit";
            // SET_COORDINATE supplies "the receiver is right here" —
            // together they hot-start the BSC200's GNSS chip. Best-
            // effort, same as AGPS.
            val seedFix = fix?.let { pushLocationBestEffort(transport, it) }

            Log.i(TAG, "upload: sending route…")
            val r = FileTransfer.uploadGeneralFile(
                transport = transport,
                fileBytes = cnx,
                fileId = fileId,
                fileName = fileName,
                fileExtension = "cnx",
            )
            if (r.success) {
                // Auto-start navigation. Mirrors the official app's
                // "send and use" flow (RoadBookSearchActivity →
                // setRoutePlanFile in the smali); the user already
                // committed to navigating by hitting Upload, so any
                // intermediate "now pick the route on the device"
                // step is friction. Failure here doesn't fail the
                // upload — the file is already on the device and
                // can be activated manually.
                val nav = try {
                    FileTransfer.startNavigation(
                        transport = transport,
                        fileId = fileId,
                        fileExtension = "cnx",
                        name = fileName,
                        totalDistanceM = route.distanceM.toLong(),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "start-navigation: exception ${e.message}")
                    FileTransfer.UploadResult(false, "exception: ${e.message}", -1)
                }
                if (!nav.success) {
                    Log.w(TAG, "start-navigation rejected (status=${nav.status}): ${nav.message}")
                }
                Result.Success(
                    status = r.status,
                    bytesSent = cnx.size,
                    deviceName = paired.name,
                    deviceMac = paired.mac,
                    fileId = fileId,
                    points = route.points.size,
                    agpsBytes = agpsBytes,
                    seedLat = seedFix?.latitude,
                    seedLon = seedFix?.longitude,
                    navStarted = nav.success,
                )
            } else {
                Result.Failure(r.message, r.status)
            }
        }
    }

    // ---- Location seed ------------------------------------------------

    /**
     * Standalone entry: resolve the phone's current location and push
     * it to the device via FACTORY GPS_COORDINATE_SET. Used by the
     * `…action.SEND_LOCATION` adb broadcast for headless verification.
     */
    @SuppressLint("MissingPermission")
    suspend fun sendCurrentLocation(context: Context, targetMac: String? = null): Result {
        val fix = resolveCurrentLocation(context)
            ?: return Result.Failure("no GPS fix — set a mock location or wait for a real fix")
        return withDevice(context, targetMac) { transport, paired ->
            val r = LocationInjector.setCoordinate(transport, fix.latitude, fix.longitude)
            if (r.success) {
                Result.Success(
                    deviceName = paired.name,
                    deviceMac = paired.mac,
                    seedLat = fix.latitude,
                    seedLon = fix.longitude,
                    status = r.status,
                )
            } else {
                Result.Failure(r.message, r.status)
            }
        }
    }

    /**
     * Push an already-resolved [fix] to the device, returning it on
     * success and null when the device rejected. Never throws — failure
     * is silent so the route upload path stays robust.
     */
    private suspend fun pushLocationBestEffort(
        transport: Transport,
        fix: Point,
    ): Point? {
        val r = try {
            LocationInjector.setCoordinate(transport, fix.latitude, fix.longitude)
        } catch (e: Exception) {
            Log.w(TAG, "location-seed: exception ${e.message}")
            return null
        }
        return if (r.success) {
            Log.i(TAG, "location-seed: ok lat=${fix.latitude} lon=${fix.longitude}")
            fix
        } else {
            Log.w(TAG, "location-seed: device rejected (status=${r.status}): ${r.message}")
            null
        }
    }

    // ---- AGPS pre-seed ------------------------------------------------

    /** Last AssistNow payload we downloaded; see [fetchAgpsOrNull]. */
    private class CachedAgps(val data: ByteArray, val fetchedAt: Long)

    private val agpsFetchMutex = Mutex()
    private var cachedAgps: CachedAgps? = null

    /**
     * Fetch AssistNow Online data and upload it to the device as
     * `file_type=AGPS`. Standalone entry point used by the
     * `…action.SEND_AGPS` adb broadcast and by the Settings "Seed AGPS
     * now" action; the route-upload path fetches and pushes the same
     * bytes inline (see [fetchAgpsOrNull] / [pushAgpsBestEffort]) so a
     * failure there doesn't break the route flow.
     *
     * This entry point always fetches and always pushes, even when the
     * device's existing seed is still fresh: it only runs when a human
     * (or a test harness) explicitly asked for it, and the reason to ask
     * is usually "the device still won't get a fix". The freshly-fetched
     * payload deliberately bypasses the [fetchAgpsOrNull] cache for the
     * same reason.
     */
    @SuppressLint("MissingPermission")
    suspend fun seedAgps(context: Context, targetMac: String? = null): Result {
        // Network round-trip before the device lease — see uploadGpx.
        val data = try {
            fetchAgps(context)
        } catch (e: Exception) {
            return Result.Failure("AGPS fetch failed: ${e.message}")
        }
        if (data.isEmpty()) {
            return Result.Failure("AGPS fetch returned 0 bytes — invalid token?")
        }
        return withDevice(context, targetMac) { transport, paired ->
            val r = pushAgps(transport, data)
            if (r.success) {
                Log.i(TAG, "agps: seeded ${data.size}B, device status=${r.status}")
                // Recording the manual seed suppresses the next automatic
                // one — otherwise "seed now" followed by an upload would
                // push the same bytes twice.
                AgpsSeedStore(context).record(paired.mac, data.size)
                Result.Success(
                    deviceName = paired.name,
                    deviceMac = paired.mac,
                    agpsBytes = data.size,
                )
            } else {
                Result.Failure("AGPS rejected: ${r.message}", r.status)
            }
        }
    }

    /**
     * AssistNow Online bytes, or null when the step should be skipped —
     * no usable token, network error, or an empty reply. Never throws;
     * the route upload treats AGPS as strictly best-effort.
     *
     * Kept separate from [pushAgpsBestEffort] so the HTTP round-trip
     * happens *before* the device lease is taken.
     *
     * The payload is memoised for as long as it stays valid. The
     * fan-out ([uploadGpxAll]) runs one [uploadGpx] per device in
     * parallel, and without the cache each of them would fetch its own
     * copy of the identical assistance data. The mutex makes the losers
     * of that race wait for the winner's response instead of opening
     * their own connection.
     */
    private suspend fun fetchAgpsOrNull(context: Context): ByteArray? = agpsFetchMutex.withLock {
        val cached = cachedAgps
        val now = System.currentTimeMillis()
        if (cached != null && AgpsSeedStore.isFresh(cached.fetchedAt, now, AgpsSeedStore.DEFAULT_TTL_MS)) {
            Log.i(TAG, "agps: reusing payload fetched ${(now - cached.fetchedAt) / 1000}s ago")
            return@withLock cached.data
        }
        val data = try {
            fetchAgps(context)
        } catch (e: Exception) {
            Log.w(TAG, "agps: fetch failed: ${e.message}")
            return@withLock null
        }
        if (data.isEmpty()) {
            Log.w(TAG, "agps: u-blox returned 0 bytes — invalid token?")
            return@withLock null
        }
        cachedAgps = CachedAgps(data, now)
        data
    }

    private suspend fun fetchAgps(context: Context): ByteArray {
        // Token resolution order:
        //   1. Runtime override from Settings → AgpsTokenStore.
        //   2. BuildConfig.AGPS_TOKEN — build-time injection.
        //   3. null → AgpsClient falls back to fetching the token
        //      from iGPSport's prod config endpoint, mirroring the
        //      official app.
        val overrideToken = AgpsTokenStore(context).get()
            ?: BuildConfig.AGPS_TOKEN.takeIf { it.isNotBlank() }
        val client = AgpsClient()
        return try {
            val t0 = System.currentTimeMillis()
            val bytes = client.fetchOnline(overrideToken)
            Log.i(TAG, "agps: fetched ${bytes.size}B in ${System.currentTimeMillis() - t0}ms")
            bytes
        } finally {
            client.runCatching { close() }
        }
    }

    private suspend fun pushAgps(transport: Transport, data: ByteArray): FileTransfer.UploadResult {
        // file_id mirrors the official app: GPS_TYPE enum number. We
        // request GPS+GLO+GAL+BDS so any of the four is reasonable;
        // pick 1 (GPS) to stay deterministic. file_name = "online_<utc-date>".
        val dateUtc = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        return FileTransfer.uploadGeneralFile(
            transport = transport,
            fileBytes = data,
            fileId = 1L,
            fileName = "online_$dateUtc",
            fileExtension = "ubx",
            fileType = FileTransfer.FILE_OP_TYPE_AGPS,
        )
    }

    /**
     * Number of AGPS bytes the device accepted, or null when it rejected
     * them. Never throws — the route upload proceeds either way.
     *
     * Only a genuinely accepted payload is recorded in [seedStore]: a
     * rejection or a dropped link must leave the device marked stale so
     * the next upload tries again.
     */
    private suspend fun pushAgpsBestEffort(
        transport: Transport,
        data: ByteArray,
        mac: String,
        seedStore: AgpsSeedStore,
    ): Int? {
        val r = try {
            pushAgps(transport, data)
        } catch (e: Exception) {
            Log.w(TAG, "agps: ble upload exception: ${e.message}")
            return null
        }
        return if (r.success) {
            Log.i(TAG, "agps: seeded ${data.size}B, device status=${r.status}")
            seedStore.record(mac, data.size)
            data.size
        } else {
            Log.w(TAG, "agps: device rejected (status=${r.status}): ${r.message}")
            null
        }
    }

    // ---- Delete -------------------------------------------------------

    @SuppressLint("MissingPermission")
    suspend fun deleteRoute(
        context: Context,
        fileId: Long,
        fileExtension: String = "cnx",
        targetMac: String? = null,
    ): Result {
        return withDevice(context, targetMac) { transport, _ ->
            val r = FileTransfer.deleteRoute(
                transport = transport,
                fileId = fileId,
                fileExtension = fileExtension,
            )
            if (r.success) {
                Result.Success(status = r.status, fileId = fileId)
            } else {
                Result.Failure(r.message, r.status)
            }
        }
    }

    // ---- Delete all (destructive) -----------------------------------

    @SuppressLint("MissingPermission")
    suspend fun deleteAllRoutes(context: Context, targetMac: String? = null): Result =
        withDevice(context, targetMac) { transport, _ ->
            val r = FileTransfer.deleteAllRoutes(transport)
            if (r.success) Result.Success(status = r.status)
            else Result.Failure(r.message, r.status)
        }

    // ---- List ---------------------------------------------------------

    @SuppressLint("MissingPermission")
    suspend fun listRoutes(context: Context, targetMac: String? = null): Result =
        withDevice(context, targetMac) { transport, paired ->
            val entries = FileTransfer.listRoutes(transport)
            Result.Success(deviceName = paired.name, deviceMac = paired.mac, routes = entries)
        }

    // ---- Nav-status (PROTOCOL.md §7.3) --------------------------------

    @SuppressLint("MissingPermission")
    suspend fun navStatus(context: Context, targetMac: String? = null): Result =
        withDevice(context, targetMac) { transport, paired ->
            val ns = FileTransfer.navStatus(transport)
            Result.Success(deviceName = paired.name, deviceMac = paired.mac, navStatus = ns)
        }

    // ---- Delete by id (FILES_DEL) ------------------------------------

    @SuppressLint("MissingPermission")
    suspend fun deleteRouteById(
        context: Context,
        fileId: Long,
        name: String,
        fileExtension: String = "cnx",
        targetMac: String? = null,
    ): Result {
        return withDevice(context, targetMac) { transport, _ ->
            val r = FileTransfer.deleteRoutesById(
                transport = transport,
                targets = listOf(FileTransfer.DeleteTarget(fileId, name, fileExtension)),
            )
            if (r.success) Result.Success(status = r.status, fileId = fileId)
            else Result.Failure(r.message, r.status)
        }
    }

    // ---- Activities (CYCLING_DATA — recorded FIT files) --------------

    @SuppressLint("MissingPermission")
    suspend fun listActivities(context: Context, targetMac: String? = null): Result =
        withDevice(context, targetMac) { transport, paired ->
            val entries = FileTransfer.listActivities(transport)
            Result.Success(deviceName = paired.name, deviceMac = paired.mac, activities = entries)
        }

    /**
     * Download one recorded activity by `timestamp`. Writes the FIT
     * bytes to scoped external storage
     * (`<getExternalFilesDir>/activities/<UTC-ISO8601>.fit`) and
     * returns the saved path on `Success`.
     *
     * Filename is derived from the wire `timestamp` (epoch seconds,
     * UTC) — matches the spirit of Python's
     * `fit_activity.activity_filename_from_meta` without round-tripping
     * the FIT file through a parser. The device's own
     * `file_download.file_name`, when present, is preserved on
     * [Result.Success.activityFileName] for callers that want to show
     * it.
     */
    @SuppressLint("MissingPermission")
    suspend fun downloadActivity(context: Context, timestamp: Long, targetMac: String? = null): Result =
        withDevice(context, targetMac) { transport, paired ->
            val download = FileTransfer.downloadActivity(transport, timestamp)
            // The stream carries no sequence numbers, so a lost or
            // interleaved notification yields a right-length file with
            // wrong contents. The FIT's own CRC is the only thing that
            // catches it — without this the corruption stays silent
            // until something downstream (Strava) rejects the file.
            when (val verdict = FitFile.verify(download.content)) {
                is FitFile.Verdict.Valid -> Unit
                is FitFile.Verdict.Invalid -> {
                    Log.w(TAG, "activity ts=$timestamp failed FIT check: ${verdict.reason}")
                    return@withDevice Result.Failure("corrupt download: ${verdict.reason}")
                }
            }
            val saved = saveActivityFit(context, timestamp, download.content)
            Result.Success(
                deviceName = paired.name,
                deviceMac = paired.mac,
                activityBytes = download.content.size,
                activityFileName = download.fileName.takeIf { it.isNotEmpty() },
                activitySavedPath = saved.absolutePath,
                activityTimestamp = timestamp,
            )
        }

    @SuppressLint("MissingPermission")
    suspend fun deleteActivity(context: Context, timestamp: Long, targetMac: String? = null): Result =
        withDevice(context, targetMac) { transport, _ ->
            val status = FileTransfer.deleteActivity(transport, timestamp)
            if (status == 0) Result.Success(status = status, activityTimestamp = timestamp)
            else Result.Failure(FileTransfer.deviceStatusName(status), status)
        }

    @SuppressLint("MissingPermission")
    suspend fun deleteAllActivities(context: Context, targetMac: String? = null): Result =
        withDevice(context, targetMac) { transport, _ ->
            val status = FileTransfer.deleteAllActivities(transport)
            if (status == 0) Result.Success(status = status)
            else Result.Failure(FileTransfer.deviceStatusName(status), status)
        }

    private fun saveActivityFit(context: Context, timestamp: Long, content: ByteArray): java.io.File {
        // Scoped external storage — no runtime permissions needed.
        val dir = java.io.File(context.getExternalFilesDir(null), "activities").apply { mkdirs() }
        val nameFmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        // Device timestamps count from the FIT epoch, not the Unix one.
        // No trailing 'Z': the device's list timestamps read as local
        // wall-clock, so stamping them UTC would be a lie. See
        // FitFile.garminToUnixSeconds.
        val unix = FitFile.garminToUnixSeconds(timestamp)
        val fileName = "${nameFmt.format(Date(unix * 1000L))}.fit"
        val out = java.io.File(dir, fileName)
        out.writeBytes(content)
        return out
    }

    // ---- Internals ----------------------------------------------------

    /**
     * Resolution order:
     *   1. [MockLocationStore] — set by the adb e2e harness.
     *   2. `FusedLocationProviderClient.getCurrentLocation` (high accuracy, ~10 s).
     *   3. `lastLocation` fallback.
     *   4. null → caller surfaces the failure.
     */
    @SuppressLint("MissingPermission")
    private suspend fun resolveCurrentLocation(context: Context): Point? {
        MockLocationStore.get()?.let { return it }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val current = awaitCurrentLocation(client)
        if (current != null) return Point(current.latitude, current.longitude)
        val last = awaitLastLocation(client)
        return last?.let { Point(it.latitude, it.longitude) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitCurrentLocation(
        client: com.google.android.gms.location.FusedLocationProviderClient,
    ): Location? = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine<Location?> { cont ->
            val tokenSrc = CancellationTokenSource()
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSrc.token)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
            cont.invokeOnCancellation { tokenSrc.cancel() }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitLastLocation(
        client: com.google.android.gms.location.FusedLocationProviderClient,
    ): Location? = withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine<Location?> { cont ->
            client.lastLocation
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        }
    }

    /**
     * Resolve which paired device an operation targets. When [targetMac]
     * is null we pick the first paired device — that preserves the
     * existing single-device semantics for every legacy caller
     * (UploadScreen, the adb harness, etc.). When non-null we look the
     * MAC up in [DeviceStore]; an unrecognised MAC returns null so the
     * caller surfaces "device not paired" instead of silently routing to
     * the wrong target.
     */
    private fun resolvePaired(context: Context, targetMac: String?): DeviceStore.Paired? {
        val all = DeviceStore(context).list()
        if (all.isEmpty()) return null
        return if (targetMac == null) {
            all.first()
        } else {
            all.firstOrNull { it.mac.equals(targetMac, ignoreCase = true) }
        }
    }

    /**
     * Run [block] against an exclusive lease on the target device's
     * shared connection ([BleSessionManager]).
     *
     * `CancellationException` deliberately escapes: a composable leaving
     * the screen mid-poll is not a BLE failure, and reporting it as one
     * used to make the nav-status pill flip to "Connecting…" for no
     * reason.
     */
    private suspend fun withDevice(
        context: Context,
        targetMac: String?,
        block: suspend (Transport, DeviceStore.Paired) -> Result,
    ): Result {
        val paired = resolvePaired(context, targetMac) ?: return Result.Failure("no paired device")
        return withDevice(context, paired, block)
    }

    private suspend fun withDevice(
        context: Context,
        paired: DeviceStore.Paired,
        block: suspend (Transport, DeviceStore.Paired) -> Result,
    ): Result = try {
        BleSessionManager.withSession(context, paired.mac) { transport -> block(transport, paired) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.Failure("BLE error: ${e.message}")
    }

    private fun bluetoothAdapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    // ---- Fan-out helpers --------------------------------------------
    //
    // Multi-device entry points. They iterate over every paired device
    // in parallel and return a MAC-keyed map of per-device results.
    // [BleSessionManager] leases are per MAC, so the fan-out stays truly
    // concurrent — one connection per device, never two per device.
    // Callers that want the legacy "do one device" behaviour still use
    // the original singular functions above with targetMac=null.

    /**
     * Upload [gpxBytes] to every paired device in parallel.
     *
     * MAC-keyed result map; the keys are uppercase canonical MACs as
     * stored in [DeviceStore]. Empty map → no devices paired.
     *
     * One pipeline attempt per device by default. Connect-level retries
     * live in [BleSessionManager] (which also rebuilds a link the stack
     * dropped while the app was idle), so retrying the whole upload here
     * would only re-send megabytes for a device that is genuinely
     * switched off — the exact regression that made an upload wait ~3×
     * the GATT connect timeout before the UI could surface "Uploaded ✓".
     * Callers can still opt in with [maxAttempts] > 1.
     */
    @SuppressLint("MissingPermission")
    suspend fun uploadGpxAll(
        context: Context,
        gpxBytes: ByteArray,
        fileId: Long = System.currentTimeMillis() / 1000L,
        fileName: String = "route",
        maxAttempts: Int = 1,
    ): Map<String, Result> {
        val macs = DeviceStore(context).list().map { it.mac }
        if (macs.isEmpty()) return emptyMap()
        val attempts = maxAttempts.coerceAtLeast(1)
        return coroutineScope {
            macs.map { mac ->
                mac to async {
                    var last: Result = Result.Failure("not attempted")
                    repeat(attempts) { i ->
                        last = uploadGpx(
                            context = context,
                            gpxBytes = gpxBytes,
                            fileId = fileId,
                            fileName = fileName,
                            targetMac = mac,
                        )
                        if (last is Result.Success) return@async last
                        Log.w(
                            TAG,
                            "uploadGpxAll: $mac attempt ${i + 1}/$attempts failed: " +
                                (last as Result.Failure).reason,
                        )
                    }
                    last
                }
            }.associate { (mac, deferred) -> mac to deferred.await() }
        }
    }

    /**
     * Poll [navStatus] on every paired device in parallel. Used by the
     * map screen's bottom-left status stack — one pill per device.
     */
    @SuppressLint("MissingPermission")
    suspend fun navStatusAll(context: Context): Map<String, Result> {
        val macs = DeviceStore(context).list().map { it.mac }
        if (macs.isEmpty()) return emptyMap()
        return coroutineScope {
            macs.map { mac ->
                mac to async { navStatus(context, targetMac = mac) }
            }.associate { (mac, deferred) -> mac to deferred.await() }
        }
    }
}
