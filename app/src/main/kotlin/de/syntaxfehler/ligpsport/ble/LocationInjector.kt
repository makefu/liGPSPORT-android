package de.syntaxfehler.ligpsport.ble

import android.util.Log
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Inject a fresh (lat, lon) prior into the BSC200 so its GNSS chip
 * doesn't have to guess where on Earth it is at cold start.
 *
 * **Wire shape** — derived from `IGPDeviceManager.setCoordinate`
 * (classes4.dex line ~26349) and `ManufacturerServiceFactory.getMessage`
 * (line ~148). A standard PbFrame on the Control channel:
 *
 * | header field | value                                       |
 * |--------------|---------------------------------------------|
 * | `service`    | 11 (FACTORY)                                |
 * | `operation`  | 8 (`FACTORY_OPERATE_TYPE_GPS_COORDINATE_SET`)|
 * | payload      | `factory_msg{service_type=11, factory_operate_type=8, gps_coordinate_msg={lat, lon}}` |
 *
 * The BSC200 acks with a ConfirmFrame (`status=0` on success). Despite
 * the FACTORY service number this is NOT a destructive command — the
 * production app uses it from `BroadcastViewModel` and
 * `RecordingViewModel` to share the phone's GPS fix while a ride is
 * being planned or watched live. Pairs well with the AGPS pre-seed:
 * AGPS supplies ephemeris (which satellite is where in orbit),
 * SET_COORDINATE supplies the receiver's own rough position prior
 * (which satellites are *visible* from here).
 */
object LocationInjector {
    private const val TAG = "LocationInjector"

    private const val SERVICE_FACTORY = 11
    private const val FACTORY_OP_GPS_COORDINATE_SET = 8

    /** Field numbers in factory.proto's `factory_msg`. */
    private const val FIELD_FACTORY_SERVICE_TYPE = 1
    private const val FIELD_FACTORY_OPERATE_TYPE = 2
    private const val FIELD_GPS_COORDINATE_MSG = 9

    /** Field numbers in factory.proto's `gps_coordinate_message`. */
    private const val FIELD_LATITUDE = 1
    private const val FIELD_LONGITUDE = 2

    data class Result(val success: Boolean, val status: Int, val message: String = "")

    /**
     * Send (lat, lon) to the device. Caller is responsible for the
     * transport's open/close lifecycle.
     */
    suspend fun setCoordinate(
        transport: Transport,
        latitude: Double,
        longitude: Double,
        timeoutMs: Long = 5_000,
    ): Result {
        val body = buildFactoryGpsCoordinatePb(latitude, longitude)
        val wire = buildFrame(
            Frame(
                service = SERVICE_FACTORY,
                operation = FACTORY_OP_GPS_COORDINATE_SET,
                payload = body,
            ),
        )

        // Filter incoming frames to FACTORY-service replies. Use a hot
        // subscription before the write so we don't race the device's
        // ConfirmFrame (matches the deleteRoute pattern).
        val filtered = transport.frames().mapNotNull { rf ->
            val parsed = try { parseFrame(rf.bytes) } catch (_: Exception) { null }
            parsed?.takeIf { it.service == SERVICE_FACTORY }
        }
        transport.send(wire, Channel.CONTROL)

        val ack: Frame? = withTimeoutOrNull(timeoutMs) { filtered.firstOrNull() }
        val status = ack?.status ?: -1
        Log.i(TAG, "set-coordinate lat=$latitude lon=$longitude → status=$status")
        return when (status) {
            0 -> Result(true, 0, "accepted")
            -1 -> Result(false, -1, "no ack (timed out)")
            else -> Result(false, status, "device rejected: status=$status")
        }
    }

    /**
     * Wire-encode `factory_msg{service_type=11, factory_operate_type=8,
     * gps_coordinate_msg={latitude, longitude}}` — proto2, hand-rolled
     * because we only need write side and the generated lite bindings
     * pull in significant runtime weight.
     *
     * Visible for testing.
     */
    internal fun buildFactoryGpsCoordinatePb(lat: Double, lon: Double): ByteArray {
        val inner = ByteArrayOutputStream()
        // field 1 (latitude) — double = wire type 1 (fixed64)
        writeDoubleField(inner, FIELD_LATITUDE, lat)
        // field 2 (longitude) — double = wire type 1 (fixed64)
        writeDoubleField(inner, FIELD_LONGITUDE, lon)

        val out = ByteArrayOutputStream()
        // service_type = FACTORY (varint)
        writeVarintField(out, FIELD_FACTORY_SERVICE_TYPE, wireType = 0)
        writeVarint(out, SERVICE_FACTORY.toLong())
        // factory_operate_type = GPS_COORDINATE_SET (varint)
        writeVarintField(out, FIELD_FACTORY_OPERATE_TYPE, wireType = 0)
        writeVarint(out, FACTORY_OP_GPS_COORDINATE_SET.toLong())
        // gps_coordinate_msg = (length-delimited)
        val innerBytes = inner.toByteArray()
        out.write((FIELD_GPS_COORDINATE_MSG shl 3) or 2)
        writeVarint(out, innerBytes.size.toLong())
        out.write(innerBytes)
        return out.toByteArray()
    }

    private fun writeVarintField(out: ByteArrayOutputStream, fieldNumber: Int, wireType: Int) {
        out.write((fieldNumber shl 3) or wireType)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7F.inv().toLong() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }

    private fun writeDoubleField(out: ByteArrayOutputStream, fieldNumber: Int, value: Double) {
        out.write((fieldNumber shl 3) or 1) // wire type 1 = fixed64
        val bits = java.lang.Double.doubleToRawLongBits(value)
        // Little-endian per protobuf spec.
        for (i in 0 until 8) {
            out.write(((bits ushr (8 * i)) and 0xFF).toInt())
        }
    }
}
