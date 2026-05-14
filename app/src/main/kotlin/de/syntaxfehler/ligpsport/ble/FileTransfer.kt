package de.syntaxfehler.ligpsport.ble

import android.util.Log
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Chunked file upload — Kotlin port of ligpsport/file_transfer.py
 * `upload_general_file` (the FILE_OPERATION ADD path the BSC200 needs
 * for CNX route uploads). See docs/PROTOCOL.md §7.1.2 for wire spec.
 *
 * The protobuf for the metadata is hand-encoded (matching the Python
 * implementation), so we don't depend on the protobuf-kotlin-lite
 * runtime here.
 */
object FileTransfer {
    private const val TAG = "FileTransfer"

    // common.proto enum values
    private const val SERVICE_FILE_OPERATION = 21
    private const val SERVICE_ROUTE_PLAN = 7
    private const val SERVICE_OPERATE_TYPE_ADD = 3

    // route_plan.proto ROUTE_PLAN_OPERATE_TYPE values
    private const val ROUTE_PLAN_OP_LIST_GET = 1
    private const val ROUTE_PLAN_OP_FILE_DEL = 3
    private const val ROUTE_PLAN_OP_FILES_DEL = 6

    // general_file_operation.proto file_type
    const val FILE_OP_TYPE_ROUTE_PLAN = 2
    const val FILE_OP_TYPE_AGPS = 7

    // Magic byte at offset 3 of the 20-byte head for a chunked file
    // upload — without this, the device treats the payload as a
    // standard PbFrame request and rejects it.
    private const val FILE_OP_TAG_UPLOAD = 0xAA

    private const val STATUS_OK = 0
    private const val STATUS_DONE_EARLY = 4

    data class UploadResult(val success: Boolean, val message: String = "", val status: Int = 0)

    /**
     * Upload [fileBytes] via the FILE_OPERATION ADD path.
     *
     * The full payload (head + size prefix + metadata pb + file bytes)
     * is written in MTU-sized chunks to the FOURTH characteristic
     * (`…-6e`). The device responds with a single ConfirmFrame whose
     * `status` byte indicates success (0) or rejection (non-zero).
     */
    suspend fun uploadGeneralFile(
        transport: Transport,
        fileBytes: ByteArray,
        fileId: Long,
        fileName: String,
        fileExtension: String,
        fileType: Int = FILE_OP_TYPE_ROUTE_PLAN,
        timeoutMs: Long = 30_000,
    ): UploadResult {
        require(fileBytes.isNotEmpty()) { "fileBytes must not be empty" }

        val head = buildFileOperationHead(operate = SERVICE_OPERATE_TYPE_ADD)
        val pb = buildGeneralFileOperationPb(
            fileType = fileType,
            fileSize = fileBytes.size,
            fileId = fileId,
            fileName = fileName,
            fileExtension = fileExtension,
        )
        val sizePrefix = byteArrayOf(
            ((pb.size shr 24) and 0xFF).toByte(),
            ((pb.size shr 16) and 0xFF).toByte(),
            ((pb.size shr 8) and 0xFF).toByte(),
            (pb.size and 0xFF).toByte(),
        )
        val payload = head + sizePrefix + pb + fileBytes

        Log.i(
            TAG,
            "FILE_OPERATION ADD upload: head=${head.size}, pb=${pb.size}, " +
                "file=${fileBytes.size}, total=${payload.size}",
        )

        // The transport's flow is hot/buffered, so consumers can
        // subscribe even after frames arrive. Drop frames that don't
        // belong to FILE_OPERATION (keep-alives, etc.).
        val filtered = transport.frames().mapNotNull { rf ->
            val parsed = try { parseFrame(rf.bytes) } catch (_: Exception) { null }
            parsed?.takeIf { it.service == SERVICE_FILE_OPERATION }
        }

        // Send the entire payload as one logical "frame" to the
        // transport, which will chunk it MTU-wise on the FOURTH
        // characteristic. Routing on the data channel is what the
        // device parser expects (see PROTOCOL.md §7.1.2).
        transport.send(payload, Channel.FOURTH)

        val ack: Frame? = withTimeoutOrNull(timeoutMs) { filtered.firstOrNull() }
        val status = ack?.status ?: -1
        return when (status) {
            STATUS_OK, STATUS_DONE_EARLY -> UploadResult(true, "uploaded", status)
            -1 -> UploadResult(false, "no ack from device (timed out)", -1)
            else -> UploadResult(false, "device rejected: status=$status", status)
        }
    }

    /**
     * Delete a route previously uploaded via [uploadGeneralFile]. Sends
     * a standard PbFrame on the Control channel (NOT chunked) and
     * awaits a single ConfirmFrame whose status byte is the result.
     *
     * Hand-encodes the same three-field protobuf the Python reference
     * builds via the generated route_plan_pb2 bindings: service_type=7,
     * route_plan_operate_type=3 (FILE_DEL), line_id="<fileId>.<ext>".
     */
    suspend fun deleteRoute(
        transport: Transport,
        fileId: Long,
        fileExtension: String = "cnx",
        timeoutMs: Long = 10_000,
    ): UploadResult {
        val body = buildRoutePlanDeletePb(fileId, fileExtension)
        val wire = buildFrame(
            Frame(
                service = SERVICE_ROUTE_PLAN,
                operation = ROUTE_PLAN_OP_FILE_DEL,
                payload = body,
            ),
        )

        val filtered = transport.frames().mapNotNull { rf ->
            val parsed = try { parseFrame(rf.bytes) } catch (_: Exception) { null }
            parsed?.takeIf { it.service == SERVICE_ROUTE_PLAN }
        }
        transport.send(wire, Channel.CONTROL)

        val ack: Frame? = withTimeoutOrNull(timeoutMs) { filtered.firstOrNull() }
        val status = ack?.status ?: -1
        Log.i(TAG, "delete-route file_id=$fileId.$fileExtension → status=$status")
        return when (status) {
            STATUS_OK, STATUS_DONE_EARLY -> UploadResult(true, "deleted", status)
            -1 -> UploadResult(false, "no ack from device (timed out)", -1)
            else -> UploadResult(false, "device rejected: status=$status", status)
        }
    }

    /** One entry in a LIST_GET response. */
    data class RouteEntry(
        val id: Long,
        val name: String,
        val fileType: Int,
        val totalDistanceM: Long,
        val status: Int,
    )

    /**
     * Ask the device for the list of route_plan files it currently
     * holds. Sends `route_plan_data_msg{operate_type=LIST_GET}` and
     * decodes the nested `route_plan_info_message` entries in the
     * reply. The response can arrive as a PbFrame (typical) or — for
     * very small lists — folded into a single ConfirmFrame; in that
     * case the entry list is empty and the device's status byte still
     * stands.
     */
    suspend fun listRoutes(
        transport: Transport,
        timeoutMs: Long = 10_000,
    ): List<RouteEntry> {
        val body = buildRoutePlanListPb()
        val wire = buildFrame(
            Frame(
                service = SERVICE_ROUTE_PLAN,
                operation = ROUTE_PLAN_OP_LIST_GET,
                payload = body,
            ),
        )

        val filtered = transport.frames().mapNotNull { rf ->
            val parsed = try { parseFrame(rf.bytes) } catch (_: Exception) { null }
            parsed?.takeIf { it.service == SERVICE_ROUTE_PLAN }
        }
        transport.send(wire, Channel.CONTROL)
        val ack: Frame? = withTimeoutOrNull(timeoutMs) { filtered.firstOrNull() }
        if (ack == null) {
            Log.w(TAG, "list-routes timed out")
            return emptyList()
        }
        if (ack.payload.isEmpty()) {
            // Confirm-only reply (no payload) — empty list or error.
            Log.i(TAG, "list-routes: confirm-only reply, status=${ack.status}")
            return emptyList()
        }
        return decodeListGetResponse(ack.payload)
    }

    private fun buildRoutePlanListPb(): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, 0); writeVarint(out, SERVICE_ROUTE_PLAN.toLong())
        writeVarintField(out, 2, 0); writeVarint(out, ROUTE_PLAN_OP_LIST_GET.toLong())
        return out.toByteArray()
    }

    /**
     * Hand-decode a `route_plan_data_msg` response, picking out the
     * `repeated route_plan_info_message route_plan_info_msg = 5;`
     * entries. Unknown / uninteresting fields are skipped so we don't
     * choke on schema additions.
     */
    internal fun decodeListGetResponse(payload: ByteArray): List<RouteEntry> {
        val entries = mutableListOf<RouteEntry>()
        val reader = ProtoReader(payload)
        while (reader.hasMore()) {
            val tag = reader.readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            if (field == 5 && wire == 2) {
                // Nested route_plan_info_message
                val len = reader.readVarint().toInt()
                val bytes = reader.readBytes(len)
                entries.add(decodeRoutePlanInfo(bytes))
            } else {
                reader.skip(wire)
            }
        }
        return entries
    }

    private fun decodeRoutePlanInfo(payload: ByteArray): RouteEntry {
        var id = 0L
        var name = ""
        var fileType = 0
        var totalDistance = 0L
        var status = 0
        val reader = ProtoReader(payload)
        while (reader.hasMore()) {
            val tag = reader.readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            when {
                field == 1 && wire == 0 -> id = reader.readVarint()
                field == 2 && wire == 0 -> fileType = reader.readVarint().toInt()
                field == 3 && wire == 2 -> name = reader.readString()
                field == 4 && wire == 0 -> totalDistance = reader.readVarint()
                field == 7 && wire == 0 -> status = reader.readVarint().toInt()
                else -> reader.skip(wire)
            }
        }
        return RouteEntry(id, name, fileType, totalDistance, status)
    }

    /**
     * Destructive: delete ALL routes on the device. Uses ROUTE_PLAN
     * `FILES_DEL` (op = 6), which the BSC200 implements as a wipe of
     * its route_plan storage. Caller is responsible for confirmation
     * gating — there is no undo on the device side.
     */
    suspend fun deleteAllRoutes(
        transport: Transport,
        timeoutMs: Long = 15_000,
    ): UploadResult {
        val body = buildRoutePlanFilesDeletePb()
        val wire = buildFrame(
            Frame(
                service = SERVICE_ROUTE_PLAN,
                operation = ROUTE_PLAN_OP_FILES_DEL,
                payload = body,
            ),
        )
        val filtered = transport.frames().mapNotNull { rf ->
            val parsed = try { parseFrame(rf.bytes) } catch (_: Exception) { null }
            parsed?.takeIf { it.service == SERVICE_ROUTE_PLAN }
        }
        transport.send(wire, Channel.CONTROL)
        val ack: Frame? = withTimeoutOrNull(timeoutMs) { filtered.firstOrNull() }
        val status = ack?.status ?: -1
        Log.i(TAG, "delete-all-routes → status=$status")
        return when (status) {
            STATUS_OK, STATUS_DONE_EARLY -> UploadResult(true, "deleted all", status)
            -1 -> UploadResult(false, "no ack from device (timed out)", -1)
            else -> UploadResult(false, "device rejected: status=$status", status)
        }
    }

    private fun buildRoutePlanFilesDeletePb(): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, 0); writeVarint(out, SERVICE_ROUTE_PLAN.toLong())
        writeVarintField(out, 2, 0); writeVarint(out, ROUTE_PLAN_OP_FILES_DEL.toLong())
        // line_id is `repeated string` (field 3). FILES_DEL doesn't
        // need a target list — the device wipes whatever it has.
        return out.toByteArray()
    }

    private fun buildRoutePlanDeletePb(fileId: Long, ext: String): ByteArray {
        val out = ByteArrayOutputStream()
        // service_type (field 1, varint)
        writeVarintField(out, 1, 0); writeVarint(out, SERVICE_ROUTE_PLAN.toLong())
        // route_plan_operate_type (field 2, varint) = FILE_DEL
        writeVarintField(out, 2, 0); writeVarint(out, ROUTE_PLAN_OP_FILE_DEL.toLong())
        // line_id (field 3, length-delimited string, repeated)
        writeStringField(out, 3, "$fileId.$ext")
        return out.toByteArray()
    }

    private fun buildFileOperationHead(operate: Int): ByteArray {
        val head = ByteArray(HEADER_SIZE)
        head[0] = 0x01
        head[1] = (SERVICE_FILE_OPERATION and 0xFF).toByte()
        head[2] = 0xFF.toByte()
        head[3] = FILE_OP_TAG_UPLOAD.toByte()
        head[4] = (operate and 0xFF).toByte()
        head[5] = 0xFF.toByte()
        head[6] = 0xFF.toByte()
        head[7] = 0x00
        head[8] = 0x00
        head[9] = 0x00
        head[10] = 0x01
        for (off in 11 until HDR_HEADER_CRC) head[off] = 0xFF.toByte()
        head[HDR_HEADER_CRC] = crc8(head, 0, HDR_HEADER_CRC).toByte()
        return head
    }

    private fun buildGeneralFileOperationPb(
        fileType: Int,
        fileSize: Int,
        fileId: Long,
        fileName: String,
        fileExtension: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // service_type (field 1, varint)
        writeVarintField(out, fieldNumber = 1, wireType = 0)
        writeVarint(out, SERVICE_FILE_OPERATION.toLong())
        // operate_type (field 2, varint) = ADD
        writeVarintField(out, 2, 0)
        writeVarint(out, SERVICE_OPERATE_TYPE_ADD.toLong())
        // file_type (field 3, varint)
        writeVarintField(out, 3, 0)
        writeVarint(out, fileType.toLong())
        // file_size (field 4, varint)
        writeVarintField(out, 4, 0)
        writeVarint(out, fileSize.toLong())
        // file_id (field 5, varint)
        writeVarintField(out, 5, 0)
        writeVarint(out, fileId)
        // file_name (field 6, length-delimited)
        writeStringField(out, 6, fileName)
        // file_extension (field 7, length-delimited)
        writeStringField(out, 7, fileExtension)
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

    private fun writeStringField(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write((fieldNumber shl 3) or 2)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    /** Minimal proto2 reader for the LIST_GET response — varint /
     *  length-delimited / fixed64 / fixed32 wire types only. */
    internal class ProtoReader(private val buf: ByteArray) {
        private var pos = 0
        fun hasMore(): Boolean = pos < buf.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= buf.size) throw IllegalStateException("varint truncated")
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift >= 64) throw IllegalStateException("varint too long")
            }
        }

        fun readBytes(n: Int): ByteArray {
            if (pos + n > buf.size) throw IllegalStateException("length-delimited truncated")
            val out = buf.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun readString(): String {
            val n = readVarint().toInt()
            return String(readBytes(n), Charsets.UTF_8)
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> { val n = readVarint().toInt(); pos += n }
                5 -> pos += 4
                else -> throw IllegalStateException("unsupported wire type $wireType")
            }
            if (pos > buf.size) throw IllegalStateException("skip past end")
        }
    }
}
