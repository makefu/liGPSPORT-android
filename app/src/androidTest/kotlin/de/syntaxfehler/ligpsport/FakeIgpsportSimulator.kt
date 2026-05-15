package de.syntaxfehler.ligpsport

import de.syntaxfehler.ligpsport.ble.Channel
import de.syntaxfehler.ligpsport.ble.FILE_TAG_TRANSMIT_COMPLETE
import de.syntaxfehler.ligpsport.ble.FileTransfer
import de.syntaxfehler.ligpsport.ble.Frame
import de.syntaxfehler.ligpsport.ble.HEADER_SIZE
import de.syntaxfehler.ligpsport.ble.HDR_END_MARKER
import de.syntaxfehler.ligpsport.ble.HDR_FILE_TAG
import de.syntaxfehler.ligpsport.ble.HDR_HEADER_CRC
import de.syntaxfehler.ligpsport.ble.HDR_OPERATION
import de.syntaxfehler.ligpsport.ble.HDR_PAYLOAD_CRC
import de.syntaxfehler.ligpsport.ble.HDR_PAYLOAD_SIZE
import de.syntaxfehler.ligpsport.ble.HDR_RESERVED_6
import de.syntaxfehler.ligpsport.ble.HDR_RESERVED_PAD
import de.syntaxfehler.ligpsport.ble.HDR_SERVICE
import de.syntaxfehler.ligpsport.ble.HDR_SUB_OPERATION
import de.syntaxfehler.ligpsport.ble.HDR_SUB_SERVICE
import de.syntaxfehler.ligpsport.ble.HDR_TYPE
import de.syntaxfehler.ligpsport.ble.LoopbackTransport
import de.syntaxfehler.ligpsport.ble.RESERVED_BYTE
import de.syntaxfehler.ligpsport.ble.TYPE_CONFIRM
import de.syntaxfehler.ligpsport.ble.TYPE_PB
import de.syntaxfehler.ligpsport.ble.crc8
import de.syntaxfehler.ligpsport.ble.parseFrame
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Stand-in iGPSPORT device for hermetic Espresso tests. Replies to:
 *
 * - **Any non-CYCLING_DATA request** → ConfirmFrame status=0 on
 *   CONTROL (mirrors the original simulator behaviour).
 * - **CYCLING_DATA LIST_GET (op=1)** → PbFrame on THIRD carrying a
 *   `cycling_data_msg{op=LIST_SEND, file_flag_msg=[…activities…]}`
 *   built from [activities].
 * - **CYCLING_DATA FILE_GET (op=3)** → transmit-complete PbFrame on
 *   THIRD: 20-byte head with file_tag=0x55, end_marker=0x03,
 *   followed by `[BE-u32 pb_size | file_download pb | file bytes]`
 *   for the requested timestamp drawn from [activityContent].
 * - **CYCLING_DATA FILE_DEL (op=5)** → ConfirmFrame status=0; the
 *   matching activity is removed from [activities] +
 *   [activityContent].
 * - **CYCLING_DATA ALL_DEL (op=6)** → ConfirmFrame status=0; both
 *   maps are cleared.
 */
class FakeIgpsportSimulator(
    private val transport: LoopbackTransport,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    /** In-memory activity table — mutate before [start] to seed the device. */
    val activities: MutableList<FileTransfer.ActivityListEntry> = mutableListOf()

    /** Maps activity timestamp → raw FIT bytes returned by FILE_GET. */
    val activityContent: MutableMap<Long, ByteArray> = mutableMapOf()

    fun start() {
        job = scope.launch {
            for (out in transport.outgoing) {
                val frame = try {
                    parseFrame(out.bytes)
                } catch (_: Exception) {
                    continue
                }
                if (frame.service == SERVICE_CYCLING_DATA) {
                    handleCyclingData(frame)
                } else {
                    val ack = Frame(
                        type = TYPE_CONFIRM,
                        service = frame.service,
                        operation = frame.operation,
                        subService = frame.subService,
                        subOperation = frame.subOperation,
                        status = 0,
                    )
                    transport.deliver(Channel.CONTROL, de.syntaxfehler.ligpsport.ble.buildFrame(ack))
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private suspend fun handleCyclingData(frame: Frame) {
        when (frame.operation) {
            CYC_OP_LIST_GET -> deliverListReply()
            CYC_OP_FILE_GET -> deliverFileGetReply(frame)
            CYC_OP_FILE_DEL -> {
                val ts = decodeFileFlagTimestamp(frame.payload)
                if (ts != null) {
                    activities.removeAll { it.timestamp == ts }
                    activityContent.remove(ts)
                }
                deliverConfirm(frame.operation, status = 0)
            }
            CYC_OP_ALL_DEL -> {
                activities.clear()
                activityContent.clear()
                deliverConfirm(frame.operation, status = 0)
            }
            else -> deliverConfirm(frame.operation, status = 0)
        }
    }

    private suspend fun deliverConfirm(operation: Int, status: Int) {
        val ack = Frame(
            type = TYPE_CONFIRM,
            service = SERVICE_CYCLING_DATA,
            operation = operation,
            status = status,
        )
        transport.deliver(Channel.THIRD, de.syntaxfehler.ligpsport.ble.buildFrame(ack))
    }

    private suspend fun deliverListReply() {
        // cycling_data_msg{service_type=6, op=LIST_SEND=2,
        //                  cycling_data_file_flag_msg=[…]}
        val body = ByteArrayOutputStream().apply {
            write(0x08); write(SERVICE_CYCLING_DATA)
            write(0x10); write(2)  // op=LIST_SEND
            for (e in activities) {
                val inner = ByteArrayOutputStream().apply {
                    write(0x08); writeVarint(this, e.timestamp)
                    if (e.fileSize != 0L) {
                        write(0x10); writeVarint(this, e.fileSize)
                    }
                    if (e.userId.isNotEmpty()) {
                        write(0x1A)
                        val ub = e.userId.toByteArray(Charsets.UTF_8)
                        writeVarint(this, ub.size.toLong()); write(ub)
                    }
                    if (e.deviceId.isNotEmpty()) {
                        write(0x22)
                        val db = e.deviceId.toByteArray(Charsets.UTF_8)
                        writeVarint(this, db.size.toLong()); write(db)
                    }
                }.toByteArray()
                write(0x1A)  // field 3, length-delimited
                writeVarint(this, inner.size.toLong())
                write(inner)
            }
        }.toByteArray()
        val frame = Frame(
            type = TYPE_PB,
            service = SERVICE_CYCLING_DATA,
            operation = 2,
            payload = body,
        )
        transport.deliver(Channel.THIRD, de.syntaxfehler.ligpsport.ble.buildFrame(frame))
    }

    private suspend fun deliverFileGetReply(request: Frame) {
        val ts = decodeFileFlagTimestamp(request.payload) ?: return deliverConfirm(request.operation, status = 1)
        val content = activityContent[ts] ?: return deliverConfirm(request.operation, status = 1)
        // file_download protobuf: file_size=<n>, file_id=<ts>, file_name="<ts>.fit"
        val name = "$ts.fit"
        val pb = ByteArrayOutputStream().apply {
            write(0x08); writeVarint(this, content.size.toLong())
            write(0x18); writeVarint(this, ts)
            write(0x22)
            val nb = name.toByteArray(Charsets.UTF_8)
            writeVarint(this, nb.size.toLong()); write(nb)
        }.toByteArray()
        val sizePrefix = byteArrayOf(
            ((pb.size shr 24) and 0xFF).toByte(),
            ((pb.size shr 16) and 0xFF).toByte(),
            ((pb.size shr 8) and 0xFF).toByte(),
            (pb.size and 0xFF).toByte(),
        )
        val tail = sizePrefix + pb + content
        // Build a transmit-complete head by hand: file_tag=0x55,
        // end_marker=0x03, payload_size deliberately bogus (the real
        // device writes 0x07A7 regardless), payload_crc zero.
        val head = ByteArray(HEADER_SIZE).apply {
            this[HDR_TYPE] = TYPE_PB.toByte()
            this[HDR_SERVICE] = SERVICE_CYCLING_DATA.toByte()
            this[HDR_SUB_SERVICE] = RESERVED_BYTE.toByte()
            this[HDR_FILE_TAG] = FILE_TAG_TRANSMIT_COMPLETE.toByte()
            this[HDR_OPERATION] = CYC_OP_FILE_SEND.toByte()
            this[HDR_SUB_OPERATION] = RESERVED_BYTE.toByte()
            this[HDR_RESERVED_6] = RESERVED_BYTE.toByte()
            // BSC200 quirk: hard-coded 0x07A7 (1959) regardless of stream length.
            this[HDR_PAYLOAD_SIZE] = 0x07.toByte()
            this[HDR_PAYLOAD_SIZE + 1] = 0xA7.toByte()
            this[HDR_PAYLOAD_CRC] = 0x00.toByte()
            this[HDR_END_MARKER] = 0x03.toByte()
            for (i in HDR_RESERVED_PAD until HDR_HEADER_CRC) this[i] = RESERVED_BYTE.toByte()
            this[HDR_HEADER_CRC] = crc8(this, 0, HDR_HEADER_CRC).toByte()
        }
        transport.deliver(Channel.THIRD, head + tail)
    }

    private fun decodeFileFlagTimestamp(body: ByteArray): Long? {
        // Walk cycling_data_msg. We want field 3 (file_flag_msg, repeated) →
        // inner field 1 (timestamp, varint). First entry only.
        var pos = 0
        while (pos < body.size) {
            val (tag, p1) = readVarint(body, pos)
            pos = p1
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x07L).toInt()
            if (field == 3 && wire == 2) {
                val (len, p2) = readVarint(body, pos)
                val end = p2 + len.toInt()
                var inner = p2
                while (inner < end) {
                    val (innerTag, ip1) = readVarint(body, inner)
                    inner = ip1
                    val innerField = (innerTag ushr 3).toInt()
                    val innerWire = (innerTag and 0x07L).toInt()
                    when (innerWire) {
                        0 -> {
                            val (v, ip2) = readVarint(body, inner)
                            inner = ip2
                            if (innerField == 1) return v
                        }
                        2 -> {
                            val (l, ip2) = readVarint(body, inner)
                            inner = ip2 + l.toInt()
                        }
                        else -> return null
                    }
                }
                pos = end
            } else {
                when (wire) {
                    0 -> { val (_, p2) = readVarint(body, pos); pos = p2 }
                    2 -> { val (l, p2) = readVarint(body, pos); pos = p2 + l.toInt() }
                    else -> return null
                }
            }
        }
        return null
    }

    private fun readVarint(buf: ByteArray, start: Int): Pair<Long, Int> {
        var v = 0L; var shift = 0; var pos = start
        while (pos < buf.size) {
            val b = buf[pos].toInt() and 0xFF
            pos += 1
            v = v or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return v to pos
            shift += 7
        }
        return v to pos
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7FL).toInt())
    }

    private companion object {
        const val SERVICE_CYCLING_DATA = 6
        const val CYC_OP_LIST_GET = 1
        const val CYC_OP_FILE_GET = 3
        const val CYC_OP_FILE_SEND = 4
        const val CYC_OP_FILE_DEL = 5
        const val CYC_OP_ALL_DEL = 6
    }
}
