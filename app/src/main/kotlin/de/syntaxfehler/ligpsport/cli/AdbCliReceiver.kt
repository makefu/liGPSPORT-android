package de.syntaxfehler.ligpsport.cli

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.syntaxfehler.ligpsport.ble.DeviceStore
import de.syntaxfehler.ligpsport.data.MockLocationStore
import de.syntaxfehler.ligpsport.data.RouterPreferences
import de.syntaxfehler.ligpsport.route.RouterRegistry
import de.syntaxfehler.ligpsport.service.UploadForegroundService

/**
 * Single entry point for the adb-driven e2e harness. Receives the
 * lightweight broadcasts under `de.syntaxfehler.ligpsport.action.*`,
 * extracts the caller's `req_id`, and either:
 *
 * - Handles synchronously (UNPAIR, STATUS) — no BLE, no service.
 * - Forwards to [UploadForegroundService] for long-running BLE ops.
 *
 * The structured RESULT line — `LigpsportAdb: RESULT action=… req_id=…
 * status=…` — is always emitted exactly once per broadcast.
 *
 * Sample invocation (adb):
 *
 *     adb shell am broadcast \
 *       -a de.syntaxfehler.ligpsport.action.PAIR \
 *       -n de.syntaxfehler.ligpsport.debug/de.syntaxfehler.ligpsport.cli.AdbCliReceiver \
 *       --ei req_id 42
 */
class AdbCliReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        val reqId = intent.getStringExtra(EXTRA_REQ_ID)
            ?: intent.getIntExtra(EXTRA_REQ_ID, 0).takeIf { it != 0 }?.toString()
            ?: "auto-${System.nanoTime()}"

        when (action) {
            ACTION_UNPAIR -> handleUnpair(ctx, reqId)
            ACTION_STATUS -> handleStatus(ctx, reqId)
            ACTION_SET_ROUTER -> handleSetRouter(ctx, intent, reqId)
            ACTION_LIST_ROUTERS -> handleListRouters(ctx, reqId)
            ACTION_MOCK_LOCATION -> handleMockLocation(intent, reqId)
            ACTION_PAIR -> dispatch(ctx, reqId, UploadForegroundService.OP_PAIR, intent)
            ACTION_UPLOAD -> dispatch(ctx, reqId, UploadForegroundService.OP_UPLOAD, intent)
            ACTION_PLAN_AND_UPLOAD -> dispatch(
                ctx, reqId, UploadForegroundService.OP_PLAN_AND_UPLOAD, intent,
            )
            ACTION_DELETE_ROUTE -> dispatch(
                ctx, reqId, UploadForegroundService.OP_DELETE_ROUTE, intent,
            )
            ACTION_DELETE_ROUTE_BY_ID -> dispatch(
                ctx, reqId, UploadForegroundService.OP_DELETE_ROUTE_BY_ID, intent,
            )
            ACTION_LIST_ROUTES -> dispatch(
                ctx, reqId, UploadForegroundService.OP_LIST_ROUTES, intent,
            )
            ACTION_DELETE_ALL_ROUTES -> dispatch(
                ctx, reqId, UploadForegroundService.OP_DELETE_ALL_ROUTES, intent,
            )
            ACTION_NAV_STATUS -> dispatch(
                ctx, reqId, UploadForegroundService.OP_NAV_STATUS, intent,
            )
            ACTION_SEND_AGPS -> dispatch(
                ctx, reqId, UploadForegroundService.OP_SEND_AGPS, intent,
            )
            ACTION_SEND_LOCATION -> dispatch(
                ctx, reqId, UploadForegroundService.OP_SEND_LOCATION, intent,
            )
            ACTION_LIST_ACTIVITIES -> dispatch(
                ctx, reqId, UploadForegroundService.OP_LIST_ACTIVITIES, intent,
            )
            ACTION_DOWNLOAD_ACTIVITY -> dispatch(
                ctx, reqId, UploadForegroundService.OP_DOWNLOAD_ACTIVITY, intent,
            )
            ACTION_DELETE_ACTIVITY -> dispatch(
                ctx, reqId, UploadForegroundService.OP_DELETE_ACTIVITY, intent,
            )
            ACTION_DELETE_ALL_ACTIVITIES -> dispatch(
                ctx, reqId, UploadForegroundService.OP_DELETE_ALL_ACTIVITIES, intent,
            )
            else -> AdbResult.emit(
                action.substringAfterLast('.').uppercase(),
                reqId,
                AdbResult.Status.FAIL,
                reason = "unknown action: $action",
            )
        }
    }

    private fun dispatch(ctx: Context, reqId: String, op: String, intent: Intent) {
        try {
            UploadForegroundService.enqueue(ctx, op, reqId, intent)
        } catch (e: Exception) {
            AdbResult.emit(op, reqId, AdbResult.Status.FAIL, reason = "service start failed: ${e.message}")
        }
    }

    private fun handleUnpair(ctx: Context, reqId: String) {
        DeviceStore(ctx).clear()
        AdbResult.emit("UNPAIR", reqId, AdbResult.Status.OK)
    }

    private fun handleSetRouter(ctx: Context, intent: Intent, reqId: String) {
        val id = intent.getStringExtra("id")
        if (id.isNullOrBlank()) {
            AdbResult.emit("SET_ROUTER", reqId, AdbResult.Status.FAIL, reason = "missing --es id")
            return
        }
        val provider = RouterRegistry.byId(id)
        if (provider == null) {
            AdbResult.emit("SET_ROUTER", reqId, AdbResult.Status.FAIL, reason = "unknown router: $id")
            return
        }
        RouterPreferences(ctx).set(id)
        AdbResult.emit("SET_ROUTER", reqId, AdbResult.Status.OK, mapOf("id" to id))
    }

    private fun handleListRouters(ctx: Context, reqId: String) {
        val current = RouterPreferences(ctx).get()
        val extra = buildMap<String, String> {
            put("count", RouterRegistry.all.size.toString())
            put("current", current)
            RouterRegistry.all.forEachIndexed { i, p ->
                put("r${i}_id", p.id)
                put("r${i}_name", p.displayName)
                put("r${i}_offline", p.isOffline.toString())
            }
        }
        AdbResult.emit("LIST_ROUTERS", reqId, AdbResult.Status.OK, extra)
    }

    private fun handleMockLocation(intent: Intent, reqId: String) {
        // `am --ef <key> <val>` sends a Float, not a Double — getDouble
        // returns the default. Read as float and widen.
        val lat = readDouble(intent, "lat")
        val lon = readDouble(intent, "lon")
        if (lat == null || lon == null) {
            AdbResult.emit(
                "MOCK_LOCATION", reqId, AdbResult.Status.FAIL,
                reason = "missing --ef lat / --ef lon",
            )
            return
        }
        MockLocationStore.set(lat, lon)
        AdbResult.emit(
            "MOCK_LOCATION", reqId, AdbResult.Status.OK,
            mapOf("lat" to lat.toString(), "lon" to lon.toString()),
        )
    }

    private fun readDouble(intent: Intent, key: String): Double? {
        // Prefer float (the `am --ef` wire type). Fall back to double
        // for callers that use a programmatic Intent extra.
        val f = intent.getFloatExtra(key, Float.NaN)
        if (!f.isNaN()) return f.toDouble()
        val d = intent.getDoubleExtra(key, Double.NaN)
        if (!d.isNaN()) return d
        // Last-ditch: string form (`--es lat 48.770`).
        return intent.getStringExtra(key)?.toDoubleOrNull()
    }

    private fun handleStatus(ctx: Context, reqId: String) {
        val store = DeviceStore(ctx)
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = bm?.adapter
        val extra = buildMap<String, String> {
            put("bt_enabled", (adapter?.isEnabled == true).toString())
            store.address()?.let { put("paired_mac", it) }
            store.name()?.let { put("paired_name", it) }
        }
        AdbResult.emit("STATUS", reqId, AdbResult.Status.OK, extra)
    }

    companion object {
        const val ACTION_PAIR = "de.syntaxfehler.ligpsport.action.PAIR"
        const val ACTION_UNPAIR = "de.syntaxfehler.ligpsport.action.UNPAIR"
        const val ACTION_UPLOAD = "de.syntaxfehler.ligpsport.action.UPLOAD"
        const val ACTION_PLAN_AND_UPLOAD = "de.syntaxfehler.ligpsport.action.PLAN_AND_UPLOAD"
        const val ACTION_DELETE_ROUTE = "de.syntaxfehler.ligpsport.action.DELETE_ROUTE"
        const val ACTION_DELETE_ROUTE_BY_ID = "de.syntaxfehler.ligpsport.action.DELETE_ROUTE_BY_ID"
        const val ACTION_LIST_ROUTES = "de.syntaxfehler.ligpsport.action.LIST_ROUTES"
        const val ACTION_DELETE_ALL_ROUTES = "de.syntaxfehler.ligpsport.action.DELETE_ALL_ROUTES"
        const val ACTION_NAV_STATUS = "de.syntaxfehler.ligpsport.action.NAV_STATUS"
        const val ACTION_SET_ROUTER = "de.syntaxfehler.ligpsport.action.SET_ROUTER"
        const val ACTION_LIST_ROUTERS = "de.syntaxfehler.ligpsport.action.LIST_ROUTERS"
        const val ACTION_MOCK_LOCATION = "de.syntaxfehler.ligpsport.action.MOCK_LOCATION"
        const val ACTION_STATUS = "de.syntaxfehler.ligpsport.action.STATUS"
        const val ACTION_SEND_AGPS = "de.syntaxfehler.ligpsport.action.SEND_AGPS"
        const val ACTION_SEND_LOCATION = "de.syntaxfehler.ligpsport.action.SEND_LOCATION"
        const val ACTION_LIST_ACTIVITIES = "de.syntaxfehler.ligpsport.action.LIST_ACTIVITIES"
        const val ACTION_DOWNLOAD_ACTIVITY = "de.syntaxfehler.ligpsport.action.DOWNLOAD_ACTIVITY"
        const val ACTION_DELETE_ACTIVITY = "de.syntaxfehler.ligpsport.action.DELETE_ACTIVITY"
        const val ACTION_DELETE_ALL_ACTIVITIES = "de.syntaxfehler.ligpsport.action.DELETE_ALL_ACTIVITIES"

        const val EXTRA_REQ_ID = "req_id"
    }
}
