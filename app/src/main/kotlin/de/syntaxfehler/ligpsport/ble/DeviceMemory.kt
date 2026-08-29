package de.syntaxfehler.ligpsport.ble

import android.util.Log
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Read the BSC200's flash capacity, so "Delete all routes" can be a
 * decision rather than a guess.
 *
 * **Wire shape** — the same FACTORY request/response pair
 * [LocationInjector] uses, one operation over:
 *
 * | header field | value                                  |
 * |--------------|----------------------------------------|
 * | `service`    | 11 (FACTORY)                           |
 * | `operation`  | 5 (`FACTORY_OPERATE_TYPE_MEMORY_GET`)  |
 * | payload      | `factory_msg{service_type=11, factory_operate_type=5}` |
 *
 * The reply is a `factory_msg` carrying `memory_msg = 5`
 * (`memory_message{total = 1, remain = 2}`, both `uint32`).
 *
 * Despite the service name this is a read: the production app's
 * FACTORY surface mixes diagnostics in with the destructive operations,
 * and `MEMORY_GET` is the same kind of harmless query as
 * `BATTARY_GET`. Same reasoning as the note on
 * [LocationInjector.setCoordinate].
 *
 * **Unverified against hardware.** Two things to check on a real
 * device, both of which would show up immediately in the UI:
 *
 * 1. Whether the device answers this operation at all — a firmware
 *    that ignores it yields a timeout, which [read] reports as null so
 *    the caller simply shows nothing.
 * 2. **The unit.** `memory_message` doesn't say, and bytes is the
 *    assumption here. If the readout is off by a factor of 1024 the
 *    fix is [BYTES_PER_UNIT], not the parsing. The raw pair is logged
 *    at INFO for exactly this comparison — check it against the
 *    device's own storage screen.
 */
object DeviceMemory {
    private const val TAG = "DeviceMemory"

    private const val SERVICE_FACTORY = 11
    private const val FACTORY_OP_MEMORY_GET = 5

    /** Field numbers in factory.proto's `factory_msg`. */
    private const val FIELD_FACTORY_SERVICE_TYPE = 1
    private const val FIELD_FACTORY_OPERATE_TYPE = 2
    private const val FIELD_MEMORY_MSG = 5

    /** Field numbers in factory.proto's `memory_message`. */
    private const val FIELD_TOTAL = 1
    private const val FIELD_REMAIN = 2

    /**
     * What one unit of `memory_message.total` / `.remain` is worth.
     * Assumed bytes; see the class note. A `uint32` of bytes tops out
     * at 4 GiB, which comfortably covers a head unit's flash, so the
     * assumption is at least self-consistent.
     */
    internal const val BYTES_PER_UNIT = 1L

    /**
     * [total] and [free] in bytes. Both are what the device reported,
     * scaled by [BYTES_PER_UNIT] — nothing here is derived from how
     * much this app has uploaded.
     */
    data class Info(val total: Long, val free: Long) {
        val used: Long get() = (total - free).coerceAtLeast(0L)

        /** 0f..1f, or null when the device reported a zero total. */
        val fractionUsed: Float?
            get() = if (total <= 0L) null else (used.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Ask the device how much flash it has. Returns null on timeout, a
     * rejection, or an unparseable reply — a storage readout is a nicety,
     * so every failure collapses to "don't show it" rather than an error
     * the user has to dismiss. Caller owns the transport lifecycle.
     */
    suspend fun read(transport: Transport, timeoutMs: Long = 5_000): Info? {
        val wire = buildFrame(
            Frame(
                service = SERVICE_FACTORY,
                operation = FACTORY_OP_MEMORY_GET,
                payload = buildMemoryGetPb(),
            ),
        )

        // Subscribe before writing so a fast reply can't be missed —
        // same ordering LocationInjector and deleteRoute rely on.
        val replies = transport.frames().mapNotNull { rf ->
            val parsed = try { parseFrame(rf.bytes) } catch (_: Exception) { null }
            parsed?.takeIf { it.service == SERVICE_FACTORY }
        }
        transport.send(wire, Channel.CONTROL)

        // mapNotNull, not first(): the device may ack with a bare
        // ConfirmFrame before the frame that actually carries the
        // numbers, so undecodable replies are skipped rather than
        // taken as the answer.
        val info = withTimeoutOrNull(timeoutMs) {
            replies.mapNotNull { decodeMemoryReply(it.payload) }.firstOrNull()
        }
        // Logged here rather than in the decode helpers, which stay
        // pure so they can be unit-tested off-device: android.util.Log
        // throws under plain JUnit, and the catch in decodeMemoryReply
        // would turn that into a silent null.
        if (info == null) {
            Log.i(TAG, "memory-get: no usable reply within ${timeoutMs}ms")
        } else {
            // Raw pair, for checking the unit assumption against the
            // device's own storage screen. See the class note.
            Log.i(TAG, "memory-get: total=${info.total} free=${info.free} (unit assumed bytes)")
        }
        return info
    }

    /**
     * `factory_msg{service_type=11, factory_operate_type=5}` — no data
     * section, the operation is the whole request. Hand-rolled proto2
     * for the same reason as [LocationInjector]: write side only, and
     * the generated lite bindings are not worth their runtime weight.
     *
     * Visible for testing.
     */
    internal fun buildMemoryGetPb(): ByteArray {
        val out = ByteArrayOutputStream()
        writeTag(out, FIELD_FACTORY_SERVICE_TYPE, wire = 0)
        writeVarint(out, SERVICE_FACTORY.toLong())
        writeTag(out, FIELD_FACTORY_OPERATE_TYPE, wire = 0)
        writeVarint(out, FACTORY_OP_MEMORY_GET.toLong())
        return out.toByteArray()
    }

    /**
     * Tags are varints. Both fields here are low enough to fit in one
     * byte, but writing them as raw bytes — as the surrounding code
     * does — silently corrupts the message at field 16 and above, so
     * don't plant that for whoever adds the next field.
     */
    private fun writeTag(out: ByteArrayOutputStream, field: Int, wire: Int) =
        writeVarint(out, ((field shl 3) or wire).toLong())

    /**
     * Pull `memory_msg` out of a `factory_msg` reply. Null when the
     * frame carries no memory submessage — which is how a plain ack
     * gets skipped in [read] — or when the bytes don't parse at all.
     *
     * Pure and side-effect free, so it can be tested without a device
     * or an Android runtime.
     *
     * Visible for testing.
     */
    internal fun decodeMemoryReply(payload: ByteArray): Info? {
        if (payload.isEmpty()) return null
        return try {
            val reader = FileTransfer.ProtoReader(payload)
            var found: Info? = null
            while (reader.hasMore() && found == null) {
                val tag = reader.readVarint().toInt()
                val field = tag ushr 3
                val wire = tag and 7
                if (field == FIELD_MEMORY_MSG && wire == 2) {
                    val len = reader.readVarint().toInt()
                    found = decodeMemoryMessage(reader.readBytes(len))
                } else {
                    reader.skip(wire)
                }
            }
            found
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeMemoryMessage(bytes: ByteArray): Info? {
        var total = -1L
        var remain = -1L
        val reader = FileTransfer.ProtoReader(bytes)
        while (reader.hasMore()) {
            val tag = reader.readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            when {
                field == FIELD_TOTAL && wire == 0 -> total = reader.readVarint()
                field == FIELD_REMAIN && wire == 0 -> remain = reader.readVarint()
                else -> reader.skip(wire)
            }
        }
        // Both fields are `optional`, so a reply carrying only one is
        // schema-legal and useless — a bar needs both ends.
        if (total < 0 || remain < 0) return null
        return Info(total = total * BYTES_PER_UNIT, free = remain * BYTES_PER_UNIT)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7F.inv().toLong() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }
}
