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
import de.syntaxfehler.ligpsport.data.MockLocationStore
import de.syntaxfehler.ligpsport.data.RouterPreferences
import de.syntaxfehler.ligpsport.route.CnxEncoder
import de.syntaxfehler.ligpsport.route.GpxParser
import de.syntaxfehler.ligpsport.route.Point
import de.syntaxfehler.ligpsport.route.RouteData
import de.syntaxfehler.ligpsport.route.RouteProvider
import de.syntaxfehler.ligpsport.route.RouterRegistry
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

/**
 * High-level orchestration: GPX → CNX → BLE upload, plus the
 * complementary primitives (pair / delete / plan / list) used by both
 * the in-app UI and the adb broadcast harness. Each helper opens a
 * fresh [BleTransport] so callers don't need to manage GATT lifetime.
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
        val device = withTimeoutOrNull(timeoutMs) { scanner.scan().firstOrNull() }
            ?: return Result.Failure("no iGPSPORT device found within ${timeoutMs}ms")
        val name = try { device.name } catch (_: SecurityException) { null }
        DeviceStore(context).save(name = name, address = device.address)
        return Result.Success(deviceName = name, deviceMac = device.address)
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
    ): Result {
        val provider = providerOverride
            ?: RouterRegistry.byId(RouterPreferences(context).get())
            ?: RouterRegistry.default
        val resolvedStart: Point = start ?: resolveCurrentLocation(context)
            ?: return Result.Failure("no GPS fix — set a mock location or wait for a real fix")
        Log.i(TAG, "plan: provider=${provider.id} start=${resolvedStart.latitude},${resolvedStart.longitude} end=${end.latitude},${end.longitude}")
        val t0 = System.currentTimeMillis()
        val gpx = try {
            provider.planGpx(resolvedStart, end, profile)
        } catch (e: Exception) {
            Log.e(TAG, "plan: ${provider.id} failed", e)
            return Result.Failure("${provider.id} failed: ${e.message}")
        }
        Log.i(TAG, "plan: ${provider.id} returned ${gpx.size} bytes in ${System.currentTimeMillis() - t0}ms")
        val upload = uploadGpx(context, gpx, fileId = fileId, fileName = fileName)
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
    ): Result {
        val transportSetup = openPairedTransport(context) ?: return Result.Failure("no paired device")
        val (transport, pairedName, pairedMac) = transportSetup

        val route: RouteData = try {
            GpxParser.parse(gpxBytes)
        } catch (e: Exception) {
            transport.runCatching { close() }
            return Result.Failure("GPX parse failed: ${e.message}")
        }
        Log.i(TAG, "upload: parsed ${route.points.size} GPX points")
        val tEnc = System.currentTimeMillis()
        val cnx: ByteArray = try {
            CnxEncoder.encode(route, routeId = fileId)
        } catch (e: Exception) {
            transport.runCatching { close() }
            return Result.Failure("CNX encode failed: ${e.message}")
        }
        Log.i(TAG, "upload: cnx encode ${cnx.size}B in ${System.currentTimeMillis() - tEnc}ms")

        return try {
            val tBle = System.currentTimeMillis()
            transport.open()
            Log.i(TAG, "upload: ble open in ${System.currentTimeMillis() - tBle}ms")

            // Piggyback AGPS seed before the route so the device gets
            // assistance data while the user is still putting the bike
            // away. Best-effort: a fetch failure doesn't fail the
            // route upload — we just log it and proceed.
            val agpsBytes = uploadAgpsBestEffort(transport)

            // Inject the phone's current location as a starting-point
            // prior. AGPS supplies "which satellite is where in orbit";
            // SET_COORDINATE supplies "the receiver is right here" —
            // together they hot-start the BSC200's GNSS chip. Best-
            // effort, same as AGPS.
            val seedFix = injectCurrentLocationBestEffort(context, transport)

            Log.i(TAG, "upload: sending route…")
            val r = FileTransfer.uploadGeneralFile(
                transport = transport,
                fileBytes = cnx,
                fileId = fileId,
                fileName = fileName,
                fileExtension = "cnx",
            )
            if (r.success) {
                Result.Success(
                    status = r.status,
                    bytesSent = cnx.size,
                    deviceName = pairedName,
                    deviceMac = pairedMac,
                    fileId = fileId,
                    points = route.points.size,
                    agpsBytes = agpsBytes,
                    seedLat = seedFix?.latitude,
                    seedLon = seedFix?.longitude,
                )
            } else {
                Result.Failure(r.message, r.status)
            }
        } catch (e: Exception) {
            Result.Failure("BLE error: ${e.message}")
        } finally {
            transport.runCatching { close() }
        }
    }

    // ---- Location seed ------------------------------------------------

    /**
     * Standalone entry: resolve the phone's current location and push
     * it to the device via FACTORY GPS_COORDINATE_SET. Used by the
     * `…action.SEND_LOCATION` adb broadcast for headless verification.
     */
    @SuppressLint("MissingPermission")
    suspend fun sendCurrentLocation(context: Context): Result {
        val fix = resolveCurrentLocation(context)
            ?: return Result.Failure("no GPS fix — set a mock location or wait for a real fix")
        val transportSetup = openPairedTransport(context) ?: return Result.Failure("no paired device")
        val (transport, name, mac) = transportSetup
        return try {
            transport.open()
            val r = LocationInjector.setCoordinate(transport, fix.latitude, fix.longitude)
            if (r.success) {
                Result.Success(
                    deviceName = name,
                    deviceMac = mac,
                    seedLat = fix.latitude,
                    seedLon = fix.longitude,
                    status = r.status,
                )
            } else {
                Result.Failure(r.message, r.status)
            }
        } catch (e: Exception) {
            Result.Failure("BLE error: ${e.message}")
        } finally {
            transport.runCatching { close() }
        }
    }

    /**
     * Returns the injected [Point] on success, or null when no fix
     * was available or the device rejected. Never throws — failure
     * is silent so the route upload path stays robust.
     */
    private suspend fun injectCurrentLocationBestEffort(
        context: Context,
        transport: Transport,
    ): Point? {
        val fix = resolveCurrentLocation(context)
        if (fix == null) {
            Log.i(TAG, "location-seed: no fix available — skipping")
            return null
        }
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

    /**
     * Fetch AssistNow Online data and upload it to the device as
     * `file_type=AGPS`. Standalone entry point used by the
     * `…action.SEND_AGPS` adb broadcast for headless verification;
     * the route-upload path calls [uploadAgpsBestEffort] internally
     * instead so a failure here doesn't break the route flow.
     */
    @SuppressLint("MissingPermission")
    suspend fun seedAgps(context: Context): Result {
        val transportSetup = openPairedTransport(context) ?: return Result.Failure("no paired device")
        val (transport, name, mac) = transportSetup
        return try {
            transport.open()
            val sent = uploadAgpsBestEffort(transport, suppressErrors = false)
                ?: return Result.Failure("AGPS upload failed — see logcat for details")
            Result.Success(
                deviceName = name,
                deviceMac = mac,
                agpsBytes = sent,
            )
        } catch (e: Exception) {
            Result.Failure("BLE error: ${e.message}")
        } finally {
            transport.runCatching { close() }
        }
    }

    /**
     * Returns the number of AGPS bytes successfully pushed to the
     * device, or null when the step was skipped (no token configured)
     * or failed (network / device rejection). Never throws when
     * [suppressErrors] is true — callers can ignore the null and
     * proceed with whatever they were going to do next.
     */
    private suspend fun uploadAgpsBestEffort(
        transport: Transport,
        suppressErrors: Boolean = true,
    ): Int? {
        // BuildConfig.AGPS_TOKEN takes precedence (lets users with
        // their own u-blox AssistNow account swap in their token);
        // when empty, AgpsClient falls back to fetching the token
        // from iGPSport's prod config endpoint, mirroring the
        // official app. Either way the seed runs.
        val overrideToken = BuildConfig.AGPS_TOKEN.takeIf { it.isNotBlank() }
        val client = AgpsClient()
        val data = try {
            val t0 = System.currentTimeMillis()
            val bytes = client.fetchOnline(overrideToken)
            Log.i(TAG, "agps: fetched ${bytes.size}B in ${System.currentTimeMillis() - t0}ms")
            bytes
        } catch (e: Exception) {
            Log.w(TAG, "agps: fetch failed: ${e.message}")
            client.runCatching { close() }
            if (!suppressErrors) throw e
            return null
        } finally {
            client.runCatching { close() }
        }
        if (data.isEmpty()) {
            Log.w(TAG, "agps: u-blox returned 0 bytes — invalid token?")
            return null
        }

        // file_id mirrors the official app: GPS_TYPE enum number. We
        // request GPS+GLO+GAL+BDS so any of the four is reasonable;
        // pick 1 (GPS) to stay deterministic. file_name = "online_<utc-date>".
        val dateUtc = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val r = try {
            FileTransfer.uploadGeneralFile(
                transport = transport,
                fileBytes = data,
                fileId = 1L,
                fileName = "online_$dateUtc",
                fileExtension = "ubx",
                fileType = FileTransfer.FILE_OP_TYPE_AGPS,
            )
        } catch (e: Exception) {
            Log.w(TAG, "agps: ble upload exception: ${e.message}")
            if (!suppressErrors) throw e
            return null
        }
        return if (r.success) {
            Log.i(TAG, "agps: seeded ${data.size}B, device status=${r.status}")
            data.size
        } else {
            Log.w(TAG, "agps: device rejected (status=${r.status}): ${r.message}")
            if (!suppressErrors) error("device rejected: status=${r.status}")
            null
        }
    }

    // ---- Delete -------------------------------------------------------

    @SuppressLint("MissingPermission")
    suspend fun deleteRoute(
        context: Context,
        fileId: Long,
        fileExtension: String = "cnx",
    ): Result {
        val transportSetup = openPairedTransport(context) ?: return Result.Failure("no paired device")
        val (transport, _, _) = transportSetup
        return try {
            transport.open()
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
        } catch (e: Exception) {
            Result.Failure("BLE error: ${e.message}")
        } finally {
            transport.runCatching { close() }
        }
    }

    // ---- Delete all (destructive) -----------------------------------

    @SuppressLint("MissingPermission")
    suspend fun deleteAllRoutes(context: Context): Result {
        val transportSetup = openPairedTransport(context) ?: return Result.Failure("no paired device")
        val (transport, _, _) = transportSetup
        return try {
            transport.open()
            val r = FileTransfer.deleteAllRoutes(transport)
            if (r.success) Result.Success(status = r.status)
            else Result.Failure(r.message, r.status)
        } catch (e: Exception) {
            Result.Failure("BLE error: ${e.message}")
        } finally {
            transport.runCatching { close() }
        }
    }

    // ---- List ---------------------------------------------------------

    @SuppressLint("MissingPermission")
    suspend fun listRoutes(context: Context): Result {
        val transportSetup = openPairedTransport(context) ?: return Result.Failure("no paired device")
        val (transport, name, mac) = transportSetup
        return try {
            transport.open()
            val entries = FileTransfer.listRoutes(transport)
            Result.Success(deviceName = name, deviceMac = mac, routes = entries)
        } catch (e: Exception) {
            Result.Failure("BLE error: ${e.message}")
        } finally {
            transport.runCatching { close() }
        }
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

    @SuppressLint("MissingPermission")
    private fun openPairedTransport(
        context: Context,
    ): Triple<BleTransport, String?, String>? {
        val store = DeviceStore(context)
        val address = store.address() ?: return null
        val name = store.name()
        val adapter = bluetoothAdapter(context) ?: return null
        if (!adapter.isEnabled) return null
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return Triple(BleTransport(context, device), name, address)
    }

    private fun bluetoothAdapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }
}
