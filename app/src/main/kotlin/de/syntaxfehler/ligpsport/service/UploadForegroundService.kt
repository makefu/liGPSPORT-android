package de.syntaxfehler.ligpsport.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import de.syntaxfehler.ligpsport.R
import de.syntaxfehler.ligpsport.ble.UploadPipeline
import de.syntaxfehler.ligpsport.cli.AdbResult
import de.syntaxfehler.ligpsport.data.MockLocationStore
import de.syntaxfehler.ligpsport.data.RouterPreferences
import de.syntaxfehler.ligpsport.route.Point
import de.syntaxfehler.ligpsport.route.RouterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Android-14-style foreground service that does the heavy BLE work
 * for adb-initiated CLI actions. The receiver
 * ([de.syntaxfehler.ligpsport.cli.AdbCliReceiver]) forwards every
 * action with non-trivial duration here, then returns immediately —
 * BroadcastReceivers only have ~10 s of grace, but a foreground
 * service with `connectedDevice` type can run for the full duration
 * of a BLE upload.
 *
 * Service contract:
 * - Caller passes [EXTRA_OP] = one of [OP_PAIR] / [OP_UPLOAD] /
 *   [OP_PLAN_AND_UPLOAD] / [OP_DELETE_ROUTE], plus [EXTRA_REQ_ID].
 * - Service dispatches to [UploadPipeline], emits exactly one
 *   `LigpsportAdb: RESULT …` line via [AdbResult.emit], and calls
 *   [stopSelf].
 */
class UploadForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = mutableListOf<Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (intent == null) {
            stopSelfIfIdle()
            return START_NOT_STICKY
        }
        val op = intent.getStringExtra(EXTRA_OP) ?: ""
        val reqId = intent.getStringExtra(EXTRA_REQ_ID) ?: "auto-${System.nanoTime()}"
        val job = scope.launch {
            try {
                when (op) {
                    OP_PAIR -> doPair(reqId)
                    OP_UPLOAD -> doUpload(intent, reqId)
                    OP_PLAN_AND_UPLOAD -> doPlanAndUpload(intent, reqId)
                    OP_DELETE_ROUTE -> doDeleteRoute(intent, reqId)
                    OP_DELETE_ROUTE_BY_ID -> doDeleteRouteById(intent, reqId)
                    OP_LIST_ROUTES -> doListRoutes(reqId)
                    OP_DELETE_ALL_ROUTES -> doDeleteAllRoutes(intent, reqId)
                    OP_NAV_STATUS -> doNavStatus(reqId)
                    OP_SEND_AGPS -> doSendAgps(reqId)
                    OP_SEND_LOCATION -> doSendLocation(reqId)
                    OP_LIST_ACTIVITIES -> doListActivities(reqId)
                    OP_DOWNLOAD_ACTIVITY -> doDownloadActivity(intent, reqId)
                    OP_DELETE_ACTIVITY -> doDeleteActivity(intent, reqId)
                    OP_DELETE_ALL_ACTIVITIES -> doDeleteAllActivities(intent, reqId)
                    else -> AdbResult.emit(
                        action = op.ifEmpty { "UNKNOWN" },
                        reqId = reqId,
                        status = AdbResult.Status.FAIL,
                        reason = "unknown op",
                    )
                }
            } catch (e: Throwable) {
                AdbResult.emit(
                    action = op.ifEmpty { "UNKNOWN" },
                    reqId = reqId,
                    status = AdbResult.Status.FAIL,
                    reason = "uncaught: ${e.message}",
                )
            }
        }
        pending.add(job)
        job.invokeOnCompletion { pending.remove(job); stopSelfIfIdle() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun stopSelfIfIdle() {
        if (pending.all { it.isCompleted }) stopSelf()
    }

    // ---- Op handlers --------------------------------------------------

    private suspend fun doPair(reqId: String) {
        val res = UploadPipeline.pairFirst(this)
        emitResult("PAIR", reqId, res)
    }

    private suspend fun doUpload(intent: Intent, reqId: String) {
        val gpx = readGpxBytes(intent)
        if (gpx == null) {
            AdbResult.emit(
                "UPLOAD", reqId, AdbResult.Status.FAIL,
                reason = "no GPX payload (set --es path, --es uri, or --es gpx)",
            )
            return
        }
        val fileId = intent.getLongExtra(EXTRA_FILE_ID, System.currentTimeMillis() / 1000L)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "adb-route"
        val res = UploadPipeline.uploadGpx(this, gpx, fileId = fileId, fileName = name)
        emitResult("UPLOAD", reqId, res)
    }

    private suspend fun doPlanAndUpload(intent: Intent, reqId: String) {
        // `am --ef` sends Float wire type — fall back through Double
        // and String forms for callers that build Intents programmatically.
        val startLat = readDouble(intent, EXTRA_START_LAT)
        val startLon = readDouble(intent, EXTRA_START_LON)
        val endLat = readDouble(intent, EXTRA_END_LAT)
        val endLon = readDouble(intent, EXTRA_END_LON)
        if (endLat == null || endLon == null) {
            AdbResult.emit(
                "PLAN_AND_UPLOAD", reqId, AdbResult.Status.FAIL,
                reason = "missing end_lat/end_lon",
            )
            return
        }
        // start_lat/start_lon are optional — the pipeline will resolve
        // current location (mock → fused → last) when absent.
        val start: Point? = if (startLat != null && startLon != null) {
            Point(startLat, startLon)
        } else null
        val profile = intent.getStringExtra(EXTRA_PROFILE) ?: "trekking"
        val fileId = intent.getLongExtra(EXTRA_FILE_ID, System.currentTimeMillis() / 1000L)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "planned-route"
        val res = UploadPipeline.planAndUpload(
            context = this,
            end = Point(endLat, endLon),
            start = start,
            profile = profile,
            fileId = fileId,
            fileName = name,
        )
        emitResult("PLAN_AND_UPLOAD", reqId, res)
    }

    private fun readDouble(intent: Intent, key: String): Double? {
        val f = intent.getFloatExtra(key, Float.NaN)
        if (!f.isNaN()) return f.toDouble()
        val d = intent.getDoubleExtra(key, Double.NaN)
        if (!d.isNaN()) return d
        return intent.getStringExtra(key)?.toDoubleOrNull()
    }

    private suspend fun doSendAgps(reqId: String) {
        val res = UploadPipeline.seedAgps(this)
        emitResult("SEND_AGPS", reqId, res)
    }

    private suspend fun doSendLocation(reqId: String) {
        val res = UploadPipeline.sendCurrentLocation(this)
        emitResult("SEND_LOCATION", reqId, res)
    }

    private suspend fun doDeleteAllRoutes(intent: Intent, reqId: String) {
        val confirm = intent.getStringExtra(EXTRA_CONFIRM)
        if (confirm != "true") {
            AdbResult.emit(
                "DELETE_ALL_ROUTES", reqId, AdbResult.Status.FAIL,
                reason = "destructive — pass --es confirm true to proceed",
            )
            return
        }
        val res = UploadPipeline.deleteAllRoutes(this)
        emitResult("DELETE_ALL_ROUTES", reqId, res)
    }

    private suspend fun doListRoutes(reqId: String) {
        val res = UploadPipeline.listRoutes(this)
        when (res) {
            is UploadPipeline.Result.Success -> {
                val extra = buildMap<String, String> {
                    put("count", res.routes.size.toString())
                    res.routes.forEachIndexed { i, entry ->
                        put("r${i}_id", entry.id.toString())
                        put("r${i}_name", entry.name.ifEmpty { "?" })
                        put("r${i}_dist_m", entry.totalDistanceM.toString())
                    }
                }
                AdbResult.emit("LIST_ROUTES", reqId, AdbResult.Status.OK, extra)
            }
            is UploadPipeline.Result.Failure ->
                AdbResult.emit("LIST_ROUTES", reqId, AdbResult.Status.FAIL, reason = res.reason)
        }
    }

    private suspend fun doDeleteRoute(intent: Intent, reqId: String) {
        val fileId = intent.getLongExtra(EXTRA_FILE_ID, -1L)
        val ext = intent.getStringExtra(EXTRA_EXT) ?: "cnx"
        if (fileId < 0) {
            AdbResult.emit(
                "DELETE_ROUTE", reqId, AdbResult.Status.FAIL,
                reason = "missing or invalid file_id",
            )
            return
        }
        val res = UploadPipeline.deleteRoute(this, fileId = fileId, fileExtension = ext)
        emitResult("DELETE_ROUTE", reqId, res)
    }

    private suspend fun doDeleteRouteById(intent: Intent, reqId: String) {
        val fileId = intent.getLongExtra(EXTRA_FILE_ID, -1L)
        val ext = intent.getStringExtra(EXTRA_EXT) ?: "cnx"
        val name = intent.getStringExtra(EXTRA_NAME) ?: fileId.toString()
        if (fileId < 0) {
            AdbResult.emit(
                "DELETE_ROUTE_BY_ID", reqId, AdbResult.Status.FAIL,
                reason = "missing or invalid file_id",
            )
            return
        }
        val res = UploadPipeline.deleteRouteById(this, fileId = fileId, name = name, fileExtension = ext)
        emitResult("DELETE_ROUTE_BY_ID", reqId, res)
    }

    private suspend fun doNavStatus(reqId: String) {
        when (val res = UploadPipeline.navStatus(this)) {
            is UploadPipeline.Result.Success -> {
                val ns = res.navStatus
                val extra = buildMap<String, String> {
                    res.deviceName?.let { put("name", it) }
                    res.deviceMac?.let { put("mac", it) }
                    if (ns != null) {
                        put("is_navigating", ns.isNavigating.toString())
                        ns.activeRouteId?.let { put("active_route_id", it.toString()) }
                        if (ns.activeRouteName.isNotEmpty()) {
                            put("active_route_name", ns.activeRouteName)
                        }
                    } else {
                        put("is_navigating", "unknown")
                    }
                }
                AdbResult.emit("NAV_STATUS", reqId, AdbResult.Status.OK, extra)
            }
            is UploadPipeline.Result.Failure ->
                AdbResult.emit("NAV_STATUS", reqId, AdbResult.Status.FAIL, reason = res.reason)
        }
    }

    // ---- Activities ---------------------------------------------------

    private suspend fun doListActivities(reqId: String) {
        when (val res = UploadPipeline.listActivities(this)) {
            is UploadPipeline.Result.Success -> {
                val extra = buildMap<String, String> {
                    res.deviceName?.let { put("name", it) }
                    res.deviceMac?.let { put("mac", it) }
                    put("count", res.activities.size.toString())
                    res.activities.forEachIndexed { i, e ->
                        put("a${i}_ts", e.timestamp.toString())
                        put("a${i}_size", e.fileSize.toString())
                    }
                }
                AdbResult.emit("LIST_ACTIVITIES", reqId, AdbResult.Status.OK, extra)
            }
            is UploadPipeline.Result.Failure ->
                AdbResult.emit("LIST_ACTIVITIES", reqId, AdbResult.Status.FAIL, reason = res.reason)
        }
    }

    private suspend fun doDownloadActivity(intent: Intent, reqId: String) {
        val ts = readActivityTimestamp(intent)
        if (ts == null) {
            AdbResult.emit(
                "DOWNLOAD_ACTIVITY", reqId, AdbResult.Status.FAIL,
                reason = "missing or invalid --el timestamp",
            )
            return
        }
        when (val res = UploadPipeline.downloadActivity(this, ts)) {
            is UploadPipeline.Result.Success -> {
                val extra = buildMap<String, String> {
                    put("timestamp", ts.toString())
                    res.activityBytes?.let { put("bytes", it.toString()) }
                    res.activitySavedPath?.let { put("saved_path", it) }
                    res.activityFileName?.let { put("file_name", it) }
                    put("device_status", res.status.toString())
                }
                AdbResult.emit("DOWNLOAD_ACTIVITY", reqId, AdbResult.Status.OK, extra)
            }
            is UploadPipeline.Result.Failure -> {
                val extra = buildMap<String, String> {
                    put("timestamp", ts.toString())
                    if (res.status >= 0) put("device_status", res.status.toString())
                }
                AdbResult.emit("DOWNLOAD_ACTIVITY", reqId, AdbResult.Status.FAIL, extra, reason = res.reason)
            }
        }
    }

    private suspend fun doDeleteActivity(intent: Intent, reqId: String) {
        val ts = readActivityTimestamp(intent)
        if (ts == null) {
            AdbResult.emit(
                "DELETE_ACTIVITY", reqId, AdbResult.Status.FAIL,
                reason = "missing or invalid --el timestamp",
            )
            return
        }
        when (val res = UploadPipeline.deleteActivity(this, ts)) {
            is UploadPipeline.Result.Success -> AdbResult.emit(
                "DELETE_ACTIVITY", reqId, AdbResult.Status.OK,
                mapOf("timestamp" to ts.toString(), "device_status" to res.status.toString()),
            )
            is UploadPipeline.Result.Failure -> {
                val extra = buildMap<String, String> {
                    put("timestamp", ts.toString())
                    if (res.status >= 0) put("device_status", res.status.toString())
                }
                AdbResult.emit("DELETE_ACTIVITY", reqId, AdbResult.Status.FAIL, extra, reason = res.reason)
            }
        }
    }

    private suspend fun doDeleteAllActivities(intent: Intent, reqId: String) {
        val confirm = intent.getStringExtra(EXTRA_CONFIRM)
        if (confirm != "true") {
            AdbResult.emit(
                "DELETE_ALL_ACTIVITIES", reqId, AdbResult.Status.FAIL,
                reason = "destructive — pass --es confirm true to proceed",
            )
            return
        }
        when (val res = UploadPipeline.deleteAllActivities(this)) {
            is UploadPipeline.Result.Success -> AdbResult.emit(
                "DELETE_ALL_ACTIVITIES", reqId, AdbResult.Status.OK,
                mapOf("device_status" to res.status.toString()),
            )
            is UploadPipeline.Result.Failure -> {
                val extra = buildMap<String, String> {
                    if (res.status >= 0) put("device_status", res.status.toString())
                }
                AdbResult.emit("DELETE_ALL_ACTIVITIES", reqId, AdbResult.Status.FAIL, extra, reason = res.reason)
            }
        }
    }

    /**
     * `am --el timestamp <N>` is the typed-long path. Fall back to
     * `--es timestamp "N"` for callers that build extras programmatically
     * — same long-vs-string fallback as readDouble().
     */
    private fun readActivityTimestamp(intent: Intent): Long? {
        val l = intent.getLongExtra(EXTRA_TIMESTAMP, Long.MIN_VALUE)
        if (l != Long.MIN_VALUE) return l
        return intent.getStringExtra(EXTRA_TIMESTAMP)?.toLongOrNull()
    }

    private fun readGpxBytes(intent: Intent): ByteArray? {
        // Preferred for the harness: base64-encoded inline GPX.
        // Sidesteps Android 11+ scoped-storage friction with /sdcard.
        intent.getStringExtra(EXTRA_GPX_B64)?.let { return Base64.decode(it, Base64.DEFAULT) }
        intent.getStringExtra(EXTRA_GPX)?.let { return it.toByteArray(Charsets.UTF_8) }
        intent.getStringExtra(EXTRA_PATH)?.let { path ->
            val f = File(path); if (f.isFile) return f.readBytes()
        }
        intent.getStringExtra(EXTRA_URI)?.let { uriStr ->
            try {
                contentResolver.openInputStream(Uri.parse(uriStr))?.use { return it.readBytes() }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun emitResult(action: String, reqId: String, res: UploadPipeline.Result) {
        when (res) {
            is UploadPipeline.Result.Success -> {
                val extra = buildMap<String, String> {
                    res.deviceName?.let { put("name", it) }
                    res.deviceMac?.let { put("mac", it) }
                    res.fileId?.let { put("file_id", it.toString()) }
                    res.points?.let { put("points", it.toString()) }
                    res.providerId?.let { put("provider", it) }
                    res.agpsBytes?.let { put("agps_bytes", it.toString()) }
                    // Pin to Locale.US so the decimal separator is a
                    // period regardless of device locale — the e2e
                    // harness greps these by exact value.
                    res.seedLat?.let { put("seed_lat", String.format(java.util.Locale.US, "%.5f", it)) }
                    res.seedLon?.let { put("seed_lon", String.format(java.util.Locale.US, "%.5f", it)) }
                    res.navStarted?.let { put("nav_started", it.toString()) }
                    if (res.bytesSent > 0) put("cnx_bytes", res.bytesSent.toString())
                    put("device_status", res.status.toString())
                }
                AdbResult.emit(action, reqId, AdbResult.Status.OK, extra)
            }
            is UploadPipeline.Result.Failure -> {
                val extra = buildMap<String, String> {
                    if (res.status >= 0) put("device_status", res.status.toString())
                }
                AdbResult.emit(action, reqId, AdbResult.Status.FAIL, extra, reason = res.reason)
            }
        }
    }

    // ---- Foreground notification --------------------------------------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "iGPSPORT BLE",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ligpsport")
            .setContentText("Talking to iGPSPORT…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "upload"
        const val NOTIFICATION_ID = 1

        // Intent extra keys (must match AdbCliReceiver constants).
        const val EXTRA_OP = "op"
        const val EXTRA_REQ_ID = "req_id"
        const val EXTRA_PATH = "path"
        const val EXTRA_URI = "uri"
        const val EXTRA_GPX = "gpx"
        const val EXTRA_GPX_B64 = "gpx_b64"
        const val EXTRA_NAME = "name"
        const val EXTRA_FILE_ID = "file_id"
        const val EXTRA_EXT = "ext"
        const val EXTRA_START_LAT = "start_lat"
        const val EXTRA_START_LON = "start_lon"
        const val EXTRA_END_LAT = "end_lat"
        const val EXTRA_END_LON = "end_lon"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_CONFIRM = "confirm"
        const val EXTRA_TIMESTAMP = "timestamp"

        // EXTRA_OP values.
        const val OP_PAIR = "PAIR"
        const val OP_UPLOAD = "UPLOAD"
        const val OP_PLAN_AND_UPLOAD = "PLAN_AND_UPLOAD"
        const val OP_DELETE_ROUTE = "DELETE_ROUTE"
        const val OP_DELETE_ROUTE_BY_ID = "DELETE_ROUTE_BY_ID"
        const val OP_LIST_ROUTES = "LIST_ROUTES"
        const val OP_DELETE_ALL_ROUTES = "DELETE_ALL_ROUTES"
        const val OP_NAV_STATUS = "NAV_STATUS"
        const val OP_SEND_AGPS = "SEND_AGPS"
        const val OP_SEND_LOCATION = "SEND_LOCATION"
        const val OP_LIST_ACTIVITIES = "LIST_ACTIVITIES"
        const val OP_DOWNLOAD_ACTIVITY = "DOWNLOAD_ACTIVITY"
        const val OP_DELETE_ACTIVITY = "DELETE_ACTIVITY"
        const val OP_DELETE_ALL_ACTIVITIES = "DELETE_ALL_ACTIVITIES"

        fun enqueue(ctx: Context, op: String, reqId: String, intent: Intent) {
            val launchIntent = Intent(ctx, UploadForegroundService::class.java).apply {
                putExtras(intent)
                putExtra(EXTRA_OP, op)
                putExtra(EXTRA_REQ_ID, reqId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(launchIntent)
            } else {
                ctx.startService(launchIntent)
            }
        }
    }
}
