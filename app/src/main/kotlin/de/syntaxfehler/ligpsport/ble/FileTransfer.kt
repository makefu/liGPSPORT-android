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
    private const val SERVICE_CYCLING_DATA = 6
    private const val SERVICE_OPERATE_TYPE_ADD = 3

    // cycling_data.proto CYCLING_DATA_OPERATE_TYPE values. Field
    // numbers verified against app/src/main/proto/cycling_data.proto:
    //   LIST_GET=1, LIST_SEND=2, FILE_GET=3, FILE_SEND=4, FILE_DEL=5,
    //   ALL_DEL=6. The migration prompt referenced FILE_GET=2 / FILE_SEND=3
    //   — those were inaccurate; cycling_data.proto and PROTOCOL.md §6.4
    //   both have FILE_GET=3 / FILE_SEND=4.
    private const val CYCLING_DATA_OP_LIST_GET = 1
    private const val CYCLING_DATA_OP_FILE_GET = 3
    private const val CYCLING_DATA_OP_FILE_SEND = 4
    private const val CYCLING_DATA_OP_FILE_DEL = 5
    private const val CYCLING_DATA_OP_ALL_DEL = 6

    // cycling_data_msg field numbers (verified against
    // app/src/main/proto/cycling_data.proto):
    //   service_type=1, cycling_data_operate_type=2,
    //   cycling_data_file_flag_msg=3 (repeated), file_content=4,
    //   cycling_data_auto_upload_msg=5, list_msg=6.
    // The migration prompt named field 11 / 12 for list_msg /
    // file_flag_msg; the .proto says 6 and 3.
    private const val CYCLING_FIELD_SERVICE_TYPE = 1
    private const val CYCLING_FIELD_OPERATE_TYPE = 2
    private const val CYCLING_FIELD_FILE_FLAG_MSG = 3
    private const val CYCLING_FIELD_LIST_MSG = 6

    // cycling_data_file_flag_message fields (timestamp=1, file_size=2,
    // user_id=3, device_id=4) — match the prompt and the .proto.
    private const val CYC_FLAG_FIELD_TIMESTAMP = 1
    private const val CYC_FLAG_FIELD_FILE_SIZE = 2
    private const val CYC_FLAG_FIELD_USER_ID = 3
    private const val CYC_FLAG_FIELD_DEVICE_ID = 4

    // route_plan.proto ROUTE_PLAN_OPERATE_TYPE values
    private const val ROUTE_PLAN_OP_LIST_GET = 1
    private const val ROUTE_PLAN_OP_FILE_DEL = 3
    private const val ROUTE_PLAN_OP_FILE_USE = 5
    private const val ROUTE_PLAN_OP_FILES_DEL = 6

    // general_file_operation.proto file_type
    const val FILE_OP_TYPE_ROUTE_PLAN = 2
    const val FILE_OP_TYPE_AGPS = 7

    // route_plan.proto ROUTE_PLAN_FILE_TYPE values (subset used here).
    private const val ROUTE_PLAN_FILE_TYPE_CNX = 1
    private const val ROUTE_PLAN_FILE_TYPE_GPX = 2
    private const val ROUTE_PLAN_FILE_TYPE_FIT = 3
    private const val ROUTE_PLAN_FILE_TYPE_TCX = 4
    private const val ROUTE_PLAN_FILE_TYPE_XML = 5

    // route_plan.proto ROUTE_PLAN_FILE_STATUS values.
    internal const val ROUTE_PLAN_FILE_STATUS_INVALID = 0
    internal const val ROUTE_PLAN_FILE_STATUS_USED = 1
    internal const val ROUTE_PLAN_FILE_STATUS_UNUSED = 2

    // Magic byte at offset 3 of the 20-byte head for a chunked file
    // upload — without this, the device treats the payload as a
    // standard PbFrame request and rejects it.
    private const val FILE_OP_TAG_UPLOAD = 0xAA

    private const val STATUS_OK = 0
    private const val STATUS_DONE_EARLY = 4

    /**
     * `DeviceReturnStatus` wire-byte → name. The WiFi block sits at
     * 16-23 and Navigation at 65-66 (not sequential ordinals). See
     * PROTOCOL.md §7.2 status table. Used in error surface for
     * RouteUploadError / NavigationStartError.
     */
    internal fun deviceStatusName(status: Int): String = when (status) {
        0 -> "Success"
        1 -> "DataError"
        2 -> "CrcError"
        3 -> "OverSize"
        4 -> "QuantityIsFull"
        5 -> "IsBeingUsed"
        65 -> "NavigationRouteDeletionFailed"
        66 -> "NavigationRouteDoesNotExist"
        -1 -> "no ack (timed out)"
        else -> "device rejected: status=$status"
    }

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

    /**
     * Activate a previously-uploaded route — flips the BSC200 from
     * "route loaded" to "actively navigating". Mirrors
     * `IGPDeviceManager.setRoutePlanFile(id, fileType)` (smali c4
     * line 27391). PROTOCOL.md §7.2 has the wire-level reference.
     *
     * BSC200 reports `getGeneration() == 4` → the request goes out as
     * a **single merged write** of (20-byte head ‖ protobuf body) on
     * the FOURTH characteristic (`…-6e`). The earlier two-channel
     * split (gen-3 path) was silently ignored by the firmware.
     *
     * The protobuf body carries `route_plan_info_msg = [{id,
     * file_type, name, total_distance}]` — the BSC200 firmware
     * validates `name` and drops requests that omit it. Defaults
     * mirror the live capture: `name = str(fileId)` for unnamed
     * routes, `total_distance = 0`.
     */
    suspend fun startNavigation(
        transport: Transport,
        fileId: Long,
        fileExtension: String = "cnx",
        name: String? = null,
        totalDistanceM: Long = 0L,
        timeoutMs: Long = 10_000,
    ): UploadResult {
        val effectiveName = name ?: fileId.toString()
        val body = buildRoutePlanFileUsePb(fileId, fileExtension, effectiveName, totalDistanceM)
        val header = buildRoutePlanFileUseHeader(body)
        val merged = header + body
        Log.i(
            TAG,
            "start-navigation merged=${merged.joinToString("") { "%02x".format(it) }}",
        )

        val filtered = transport.frames().mapNotNull { rf ->
            val parsed = try { parseFrame(rf.bytes) } catch (_: Exception) { null }
            parsed?.takeIf { it.service == SERVICE_ROUTE_PLAN }
        }

        // Gen-4 merged-write path: head ‖ body in a single send on FOURTH.
        transport.send(merged, Channel.FOURTH)

        val ack: Frame? = withTimeoutOrNull(timeoutMs) { filtered.firstOrNull() }
        val status = ack?.status ?: -1
        Log.i(TAG, "start-navigation file_id=$fileId.$fileExtension → status=$status")
        return when (status) {
            STATUS_OK, STATUS_DONE_EARLY -> UploadResult(true, "navigating", status)
            -1 -> UploadResult(false, "no ack from device (timed out)", -1)
            else -> UploadResult(false, deviceStatusName(status), status)
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
        // BSC200 silently returns an empty list if no `route_list_get_msg`
        // (field 12) range is supplied — verified against firmware
        // 2024-05-14. The Android app always sends file_index_start/end.
        // `file_list_support_num_max` is 10 on current firmware, so
        // [0, 100] is a safe upper bound.
        val rangePb = ByteArrayOutputStream().apply {
            writeVarintField(this, 3, 0); writeVarint(this, 0L)
            writeVarintField(this, 4, 0); writeVarint(this, 100L)
        }.toByteArray()
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, 0); writeVarint(out, SERVICE_ROUTE_PLAN.toLong())
        writeVarintField(out, 2, 0); writeVarint(out, ROUTE_PLAN_OP_LIST_GET.toLong())
        // route_list_get_msg (field 12, length-delimited message)
        out.write((12 shl 3) or 2)
        writeVarint(out, rangePb.size.toLong())
        out.write(rangePb)
        return out.toByteArray()
    }

    /** Result of `navStatus()` — mirrors Python `commands.NavStatus`. */
    data class NavStatus(
        val isNavigating: Boolean,
        val activeRouteId: Long?,
        val activeRouteName: String,
    )

    /**
     * Read the route-plan list and report which (if any) route the
     * BSC200 is currently navigating. Mirrors
     * `RoutePlanViewModel.requestUsingRouteID` in the iGPSPORT app: a
     * `ROUTE_PLAN LIST_GET` reply tags each `route_plan_info_msg` with
     * `status` (field 7); the active route is the one with
     * `enum_USED_STATUS = 1`. PROTOCOL.md §7.3.
     *
     * `DEV_STATUS.navi_status` exists in the proto but is never
     * populated by BSC200 firmware — use this instead.
     */
    suspend fun navStatus(transport: Transport, timeoutMs: Long = 10_000): NavStatus {
        val routes = listRoutes(transport, timeoutMs)
        val active = routes.firstOrNull { it.status == ROUTE_PLAN_FILE_STATUS_USED }
        return if (active != null) {
            NavStatus(isNavigating = true, activeRouteId = active.id, activeRouteName = active.name)
        } else {
            NavStatus(isNavigating = false, activeRouteId = null, activeRouteName = "")
        }
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
     * Destructive: delete ALL inactive routes on the device. Per
     * PROTOCOL.md §7.4 the BSC200 silently ignores FILES_DEL that
     * omits the targets, and the active route is firmware-protected
     * (it acks status=0 but doesn't delete). We LIST_GET first, then
     * issue FILES_DEL with every route's full `line_id` + `info_msg`
     * pair as gen-4 demands.
     */
    suspend fun deleteAllRoutes(
        transport: Transport,
        timeoutMs: Long = 15_000,
    ): UploadResult {
        val routes = listRoutes(transport, timeoutMs)
        if (routes.isEmpty()) {
            return UploadResult(true, "no routes on device", STATUS_OK)
        }
        val targets = routes.map {
            DeleteTarget(it.id, it.name.ifEmpty { it.id.toString() }, fileTypeExt(it.fileType))
        }
        return deleteRoutesById(transport, targets, timeoutMs)
    }

    /** One target for `deleteRoutesById`. */
    data class DeleteTarget(val fileId: Long, val name: String, val extension: String)

    /**
     * Delete one or more routes by id. Sends a single
     * `ROUTE_PLAN FILES_DEL` (op = 6) with `line_id` and
     * `route_plan_info_msg` both populated — PROTOCOL.md §7.4: sending
     * only one or the other is silently no-op'd by the BSC200. Gen-4
     * single merged write on FOURTH.
     */
    suspend fun deleteRoutesById(
        transport: Transport,
        targets: List<DeleteTarget>,
        timeoutMs: Long = 15_000,
    ): UploadResult {
        if (targets.isEmpty()) {
            return UploadResult(true, "no targets", STATUS_OK)
        }
        val body = buildRoutePlanFilesDeletePb(targets)
        val header = buildRoutePlanFilesDeleteHeader(body)
        val merged = header + body
        val filtered = transport.frames().mapNotNull { rf ->
            val parsed = try { parseFrame(rf.bytes) } catch (_: Exception) { null }
            parsed?.takeIf { it.service == SERVICE_ROUTE_PLAN }
        }
        transport.send(merged, Channel.FOURTH)
        val ack: Frame? = withTimeoutOrNull(timeoutMs) { filtered.firstOrNull() }
        val status = ack?.status ?: -1
        Log.i(TAG, "files-del ids=${targets.map { it.fileId }} → status=$status")
        return when (status) {
            STATUS_OK, STATUS_DONE_EARLY -> UploadResult(true, "deleted", status)
            -1 -> UploadResult(false, "no ack from device (timed out)", -1)
            else -> UploadResult(false, deviceStatusName(status), status)
        }
    }

    private fun buildRoutePlanFilesDeletePb(targets: List<DeleteTarget>): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, 0); writeVarint(out, SERVICE_ROUTE_PLAN.toLong())
        writeVarintField(out, 2, 0); writeVarint(out, ROUTE_PLAN_OP_FILES_DEL.toLong())
        for (t in targets) {
            writeStringField(out, 3, "${t.fileId}.${t.extension}")
        }
        for (t in targets) {
            val info = ByteArrayOutputStream().apply {
                writeVarintField(this, 1, 0); writeVarint(this, t.fileId)
                writeVarintField(this, 2, 0); writeVarint(this, routePlanFileTypeFromExt(t.extension).toLong())
                writeStringField(this, 3, t.name.ifEmpty { t.fileId.toString() })
                writeVarintField(this, 4, 0); writeVarint(this, 0L)
            }.toByteArray()
            out.write((5 shl 3) or 2)
            writeVarint(out, info.size.toLong())
            out.write(info)
        }
        return out.toByteArray()
    }

    private fun buildRoutePlanFilesDeleteHeader(body: ByteArray): ByteArray {
        val head = ByteArray(HEADER_SIZE)
        head[HDR_TYPE] = TYPE_PB.toByte()
        head[HDR_SERVICE] = (SERVICE_ROUTE_PLAN and 0xFF).toByte()
        head[HDR_SUB_SERVICE] = RESERVED_BYTE.toByte()
        head[HDR_FILE_TAG] = RESERVED_BYTE.toByte()
        head[HDR_OPERATION] = (ROUTE_PLAN_OP_FILES_DEL and 0xFF).toByte()
        head[HDR_SUB_OPERATION] = RESERVED_BYTE.toByte()
        head[HDR_RESERVED_6] = RESERVED_BYTE.toByte()
        head[HDR_PAYLOAD_SIZE] = ((body.size shr 8) and 0xFF).toByte()
        head[HDR_PAYLOAD_SIZE + 1] = (body.size and 0xFF).toByte()
        head[HDR_PAYLOAD_CRC] = crc8(body).toByte()
        head[HDR_END_MARKER] = TYPE_PB.toByte()
        for (off in HDR_RESERVED_PAD until HDR_RESERVED_PAD + RESERVED_PAD_LENGTH) {
            head[off] = RESERVED_BYTE.toByte()
        }
        head[HDR_HEADER_CRC] = crc8(head, 0, HDR_HEADER_CRC).toByte()
        return head
    }

    private fun fileTypeExt(fileType: Int): String = when (fileType) {
        ROUTE_PLAN_FILE_TYPE_CNX -> "cnx"
        ROUTE_PLAN_FILE_TYPE_GPX -> "gpx"
        ROUTE_PLAN_FILE_TYPE_FIT -> "fit"
        ROUTE_PLAN_FILE_TYPE_TCX -> "tcx"
        ROUTE_PLAN_FILE_TYPE_XML -> "xml"
        else -> "cnx"
    }

    /**
     * `route_plan_data_msg{service_type=7, route_plan_operate_type=5,
     * line_id=["<id>.<ext>"], route_plan_info_msg=[{id, file_type}]}`.
     * Visible for testing.
     */
    internal fun buildRoutePlanFileUsePb(
        fileId: Long,
        ext: String,
        name: String = fileId.toString(),
        totalDistanceM: Long = 0L,
    ): ByteArray {
        // Mirror ligpsport v1.2.0 `_build_file_use_pb`: BSC200 firmware
        // validates `name` and silently drops FILE_USE if absent. The
        // captured app fills `name=str(file_id)` for unnamed routes.
        val infoPb = ByteArrayOutputStream().apply {
            writeVarintField(this, 1, 0); writeVarint(this, fileId)
            writeVarintField(this, 2, 0); writeVarint(this, routePlanFileTypeFromExt(ext).toLong())
            writeStringField(this, 3, name)
            writeVarintField(this, 4, 0); writeVarint(this, totalDistanceM)
        }.toByteArray()
        val out = ByteArrayOutputStream()
        // service_type (field 1, varint) = ROUTE_PLAN
        writeVarintField(out, 1, 0); writeVarint(out, SERVICE_ROUTE_PLAN.toLong())
        // route_plan_operate_type (field 2, varint) = FILE_USE
        writeVarintField(out, 2, 0); writeVarint(out, ROUTE_PLAN_OP_FILE_USE.toLong())
        // line_id (field 3, length-delimited string, repeated)
        writeStringField(out, 3, "$fileId.$ext")
        // route_plan_info_msg (field 5, length-delimited message, repeated)
        out.write((5 shl 3) or 2)
        writeVarint(out, infoPb.size.toLong())
        out.write(infoPb)
        return out.toByteArray()
    }

    /**
     * 20-byte PbFrame header that travels on CONTROL while the
     * protobuf body travels on FOURTH (BSC200 gen-3 split, see
     * PROTOCOL.md §7.2). Visible for testing.
     */
    internal fun buildRoutePlanFileUseHeader(body: ByteArray): ByteArray {
        // Reuse the existing PbFrame builder so the CRC, end-marker
        // and padding logic stay shared — but pass an empty payload
        // so the caller can ship the body separately on a different
        // characteristic. The body bytes still drive the size + CRC
        // fields, so we hand-build the header here rather than
        // round-tripping through buildFrame(Frame(payload=body)).
        val head = ByteArray(HEADER_SIZE)
        head[HDR_TYPE] = TYPE_PB.toByte()
        head[HDR_SERVICE] = (SERVICE_ROUTE_PLAN and 0xFF).toByte()
        head[HDR_SUB_SERVICE] = RESERVED_BYTE.toByte()
        head[HDR_FILE_TAG] = RESERVED_BYTE.toByte()
        head[HDR_OPERATION] = (ROUTE_PLAN_OP_FILE_USE and 0xFF).toByte()
        head[HDR_SUB_OPERATION] = RESERVED_BYTE.toByte()
        head[HDR_RESERVED_6] = RESERVED_BYTE.toByte()
        head[HDR_PAYLOAD_SIZE] = ((body.size shr 8) and 0xFF).toByte()
        head[HDR_PAYLOAD_SIZE + 1] = (body.size and 0xFF).toByte()
        head[HDR_PAYLOAD_CRC] = crc8(body).toByte()
        head[HDR_END_MARKER] = TYPE_PB.toByte()
        for (off in HDR_RESERVED_PAD until HDR_RESERVED_PAD + RESERVED_PAD_LENGTH) {
            head[off] = RESERVED_BYTE.toByte()
        }
        head[HDR_HEADER_CRC] = crc8(head, 0, HDR_HEADER_CRC).toByte()
        return head
    }

    private fun routePlanFileTypeFromExt(ext: String): Int = when (ext.lowercase()) {
        "cnx" -> ROUTE_PLAN_FILE_TYPE_CNX
        "gpx" -> ROUTE_PLAN_FILE_TYPE_GPX
        "fit" -> ROUTE_PLAN_FILE_TYPE_FIT
        "tcx" -> ROUTE_PLAN_FILE_TYPE_TCX
        "xml" -> ROUTE_PLAN_FILE_TYPE_XML
        else -> ROUTE_PLAN_FILE_TYPE_CNX
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

    // ====================================================================
    // CYCLING_DATA — recorded activities (FIT files on the BSC200).
    //
    // Mirrors ligpsport (Python) v1.5.0's ``file_transfer.list_activities``
    // / ``download_activity`` / ``delete_activity`` / ``delete_all_activities``.
    // PROTOCOL.md §6.4 + §7.5 cover the wire spec; the on-device path is
    // service=CYCLING_DATA(6) on the third UART RX (`…-7e`), with a
    // gen-4 merged ``head ‖ body`` write for every op.
    // ====================================================================

    /** One entry in a CYCLING_DATA `LIST_GET` reply. */
    data class ActivityListEntry(
        val timestamp: Long,
        val fileSize: Long,
        val userId: String,
        val deviceId: String,
    )

    /** Result of [downloadActivity] — the FIT file bytes plus the
     *  metadata the device echoes back in the embedded
     *  `file_download` protobuf. */
    data class ActivityDownload(
        val content: ByteArray,
        val fileSize: Long,
        val fileId: Long,
        val fileName: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ActivityDownload) return false
            return content.contentEquals(other.content) &&
                fileSize == other.fileSize &&
                fileId == other.fileId &&
                fileName == other.fileName
        }
        override fun hashCode(): Int {
            var r = content.contentHashCode()
            r = 31 * r + fileSize.hashCode()
            r = 31 * r + fileId.hashCode()
            r = 31 * r + fileName.hashCode()
            return r
        }
    }

    /**
     * List the recorded activities on the device.
     *
     * Sends `cycling_data_msg{service_type=CYCLING_DATA, op=LIST_GET,
     * list_msg{file_index_start, file_index_end}}` on the third UART
     * (gen-4 merged write). Decodes the reply's repeated
     * `cycling_data_file_flag_message` entries; the BSC200 firmware
     * pads the response with all-zero placeholders up to
     * `file_list_support_num_max` — this helper drops them.
     */
    suspend fun listActivities(
        transport: Transport,
        fileIndexStart: Int = 0,
        fileIndexEnd: Int = 100,
        timeoutMs: Long = 10_000,
    ): List<ActivityListEntry> {
        val body = buildCyclingDataListGetPb(fileIndexStart, fileIndexEnd)
        val head = buildCyclingDataHead(body, op = CYCLING_DATA_OP_LIST_GET, fileTag = FILE_TAG_DEFAULT)
        val merged = head + body

        val filtered = transport.frames().mapNotNull { rf ->
            val parsed = try { parseFrame(rf.bytes) } catch (_: Exception) { null }
            parsed?.takeIf { it.service == SERVICE_CYCLING_DATA }
        }
        transport.send(merged, Channel.THIRD)
        val ack: Frame = withTimeoutOrNull(timeoutMs) { filtered.firstOrNull() } ?: run {
            Log.w(TAG, "list-activities timed out")
            return emptyList()
        }
        if (ack.payload.isEmpty()) {
            Log.i(TAG, "list-activities: confirm-only reply, status=${ack.status}")
            return emptyList()
        }
        return decodeActivityListResponse(ack.payload)
    }

    /**
     * Download one activity by `timestamp`. The device replies with a
     * single transmit-complete (file_tag=0x55) PB frame whose payload
     * is `[BE-u32 pb_size | file_download pb | file bytes]`. The
     * framing layer's reassembler hands us the whole envelope as
     * `frame.payload`; we just split it out here.
     */
    suspend fun downloadActivity(
        transport: Transport,
        timestamp: Long,
        timeoutMs: Long = 60_000,
    ): ActivityDownload {
        val body = buildCyclingDataFileFlagPb(
            op = CYCLING_DATA_OP_FILE_GET,
            timestamp = timestamp,
        )
        val head = buildCyclingDataHead(
            body,
            op = CYCLING_DATA_OP_FILE_GET,
            fileTag = FILE_TAG_TRANSMIT_COMPLETE,
        )
        val merged = head + body

        val filtered = transport.frames().mapNotNull { rf ->
            val parsed = try { parseFrame(rf.bytes) } catch (_: Exception) { null }
            parsed?.takeIf { it.service == SERVICE_CYCLING_DATA }
        }
        transport.send(merged, Channel.THIRD)
        val ack: Frame = withTimeoutOrNull(timeoutMs) { filtered.firstOrNull() }
            ?: throw IllegalStateException("activity FILE_GET: no reply within ${timeoutMs}ms")
        if (ack.operation != CYCLING_DATA_OP_FILE_SEND) {
            throw IllegalStateException(
                "activity FILE_GET: expected FILE_SEND reply, got op=${ack.operation}",
            )
        }
        return parseActivityDownloadPayload(ack.payload)
    }

    /**
     * Delete one activity by `timestamp` (destructive). Returns the
     * device's `DeviceReturnStatus` byte (0 = success).
     */
    suspend fun deleteActivity(
        transport: Transport,
        timestamp: Long,
        timeoutMs: Long = 10_000,
    ): Int {
        val body = buildCyclingDataFileFlagPb(
            op = CYCLING_DATA_OP_FILE_DEL,
            timestamp = timestamp,
        )
        val head = buildCyclingDataHead(body, op = CYCLING_DATA_OP_FILE_DEL, fileTag = FILE_TAG_DEFAULT)
        val merged = head + body

        val filtered = transport.frames().mapNotNull { rf ->
            val parsed = try { parseFrame(rf.bytes) } catch (_: Exception) { null }
            parsed?.takeIf { it.service == SERVICE_CYCLING_DATA }
        }
        transport.send(merged, Channel.THIRD)
        val ack: Frame? = withTimeoutOrNull(timeoutMs) { filtered.firstOrNull() }
        val status = ack?.status ?: -1
        Log.i(TAG, "delete-activity ts=$timestamp → status=$status")
        return status
    }

    /**
     * Delete every recorded activity (destructive). Returns the
     * device's status byte. Note: the smali claims a split-write
     * pattern for ALL_DEL, but PROTOCOL.md §6.4 documents that the
     * live BSC200 firmware (2024-05-14) accepts only the gen-4 merged
     * (head ‖ body) write here — the split form returns
     * `status=1` (DataError). Use the merged form only.
     */
    suspend fun deleteAllActivities(
        transport: Transport,
        timeoutMs: Long = 15_000,
    ): Int {
        val body = buildCyclingDataAllDelPb()
        val head = buildCyclingDataHead(body, op = CYCLING_DATA_OP_ALL_DEL, fileTag = FILE_TAG_DEFAULT)
        val merged = head + body

        val filtered = transport.frames().mapNotNull { rf ->
            val parsed = try { parseFrame(rf.bytes) } catch (_: Exception) { null }
            parsed?.takeIf { it.service == SERVICE_CYCLING_DATA }
        }
        transport.send(merged, Channel.THIRD)
        val ack: Frame? = withTimeoutOrNull(timeoutMs) { filtered.firstOrNull() }
        val status = ack?.status ?: -1
        Log.i(TAG, "delete-all-activities → status=$status")
        return status
    }

    /**
     * Hand-decode the `cycling_data_msg` LIST_GET reply, picking
     * the repeated `cycling_data_file_flag_msg` entries (field 3).
     * Drops zero-padded placeholders so callers see real activities
     * only.
     */
    internal fun decodeActivityListResponse(payload: ByteArray): List<ActivityListEntry> {
        val out = mutableListOf<ActivityListEntry>()
        val reader = ProtoReader(payload)
        while (reader.hasMore()) {
            val tag = reader.readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            if (field == CYCLING_FIELD_FILE_FLAG_MSG && wire == 2) {
                val len = reader.readVarint().toInt()
                val bytes = reader.readBytes(len)
                val entry = decodeActivityFlag(bytes)
                if (entry.timestamp != 0L || entry.fileSize != 0L) out.add(entry)
            } else {
                reader.skip(wire)
            }
        }
        return out
    }

    private fun decodeActivityFlag(payload: ByteArray): ActivityListEntry {
        var ts = 0L
        var size = 0L
        var userId = ""
        var deviceId = ""
        val reader = ProtoReader(payload)
        while (reader.hasMore()) {
            val tag = reader.readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            when {
                field == CYC_FLAG_FIELD_TIMESTAMP && wire == 0 -> ts = reader.readVarint()
                field == CYC_FLAG_FIELD_FILE_SIZE && wire == 0 -> size = reader.readVarint()
                field == CYC_FLAG_FIELD_USER_ID && wire == 2 -> userId = reader.readString()
                field == CYC_FLAG_FIELD_DEVICE_ID && wire == 2 -> deviceId = reader.readString()
                else -> reader.skip(wire)
            }
        }
        return ActivityListEntry(timestamp = ts, fileSize = size, userId = userId, deviceId = deviceId)
    }

    /**
     * Split the FILE_GET reply payload into its three parts:
     *   `[4B BE pb_size | file_download protobuf | file bytes]`.
     * Visible for testing.
     */
    internal fun parseActivityDownloadPayload(payload: ByteArray): ActivityDownload {
        if (payload.size < 4) {
            throw IllegalStateException(
                "activity FILE_GET: reply payload too short (${payload.size} bytes)",
            )
        }
        val pbSize = ((payload[0].toInt() and 0xFF) shl 24) or
            ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or
            (payload[3].toInt() and 0xFF)
        val pbStart = 4
        val pbEnd = pbStart + pbSize
        if (payload.size < pbEnd) {
            throw IllegalStateException(
                "activity FILE_GET: pb_size=$pbSize exceeds payload (${payload.size})",
            )
        }
        val info = decodeFileDownloadInfo(payload.copyOfRange(pbStart, pbEnd))
        val fileEnd = pbEnd + info.fileSize.toInt()
        if (payload.size < fileEnd) {
            throw IllegalStateException(
                "activity FILE_GET: short payload ${payload.size} < $fileEnd",
            )
        }
        return ActivityDownload(
            content = payload.copyOfRange(pbEnd, fileEnd),
            fileSize = info.fileSize,
            fileId = info.fileId,
            fileName = info.fileName,
        )
    }

    private data class FileDownloadInfo(val fileSize: Long, val fileId: Long, val fileName: String)

    private fun decodeFileDownloadInfo(pb: ByteArray): FileDownloadInfo {
        var fileSize = 0L
        var fileId = 0L
        var fileName = ""
        val reader = ProtoReader(pb)
        while (reader.hasMore()) {
            val tag = reader.readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            when {
                // file_download.proto: file_size=1, file_type=2, file_id=3, file_name=4
                field == 1 && wire == 0 -> fileSize = reader.readVarint()
                field == 3 && wire == 0 -> fileId = reader.readVarint()
                field == 4 && wire == 2 -> fileName = reader.readString()
                else -> reader.skip(wire)
            }
        }
        return FileDownloadInfo(fileSize, fileId, fileName)
    }

    /**
     * `cycling_data_msg{service_type=CYCLING_DATA, op=LIST_GET,
     *  list_msg{file_index_start, file_index_end}}`. Visible for
     *  testing.
     */
    internal fun buildCyclingDataListGetPb(start: Int, end: Int): ByteArray {
        val rangePb = ByteArrayOutputStream().apply {
            // file_list_get_message.file_index_start (field 3, varint)
            writeVarintField(this, 3, 0); writeVarint(this, start.toLong())
            // file_list_get_message.file_index_end (field 4, varint)
            writeVarintField(this, 4, 0); writeVarint(this, end.toLong())
        }.toByteArray()
        val out = ByteArrayOutputStream()
        writeVarintField(out, CYCLING_FIELD_SERVICE_TYPE, 0); writeVarint(out, SERVICE_CYCLING_DATA.toLong())
        writeVarintField(out, CYCLING_FIELD_OPERATE_TYPE, 0); writeVarint(out, CYCLING_DATA_OP_LIST_GET.toLong())
        out.write((CYCLING_FIELD_LIST_MSG shl 3) or 2)
        writeVarint(out, rangePb.size.toLong())
        out.write(rangePb)
        return out.toByteArray()
    }

    /**
     * `cycling_data_msg{service_type=CYCLING_DATA, op=<op>,
     *  cycling_data_file_flag_msg=[{timestamp}]}`. Used by FILE_GET
     *  and FILE_DEL. Visible for testing.
     */
    internal fun buildCyclingDataFileFlagPb(op: Int, timestamp: Long): ByteArray {
        val flagPb = ByteArrayOutputStream().apply {
            writeVarintField(this, CYC_FLAG_FIELD_TIMESTAMP, 0); writeVarint(this, timestamp)
        }.toByteArray()
        val out = ByteArrayOutputStream()
        writeVarintField(out, CYCLING_FIELD_SERVICE_TYPE, 0); writeVarint(out, SERVICE_CYCLING_DATA.toLong())
        writeVarintField(out, CYCLING_FIELD_OPERATE_TYPE, 0); writeVarint(out, op.toLong())
        out.write((CYCLING_FIELD_FILE_FLAG_MSG shl 3) or 2)
        writeVarint(out, flagPb.size.toLong())
        out.write(flagPb)
        return out.toByteArray()
    }

    /**
     * `cycling_data_msg{service_type=CYCLING_DATA, op=ALL_DEL}` —
     * just the two header fields; ALL_DEL doesn't carry a file
     * selector. Visible for testing.
     */
    internal fun buildCyclingDataAllDelPb(): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, CYCLING_FIELD_SERVICE_TYPE, 0); writeVarint(out, SERVICE_CYCLING_DATA.toLong())
        writeVarintField(out, CYCLING_FIELD_OPERATE_TYPE, 0); writeVarint(out, CYCLING_DATA_OP_ALL_DEL.toLong())
        return out.toByteArray()
    }

    /**
     * 20-byte PbFrame head for a CYCLING_DATA service request.
     * Mirrors the Python helper in ligpsport/file_transfer.py:
     * service byte = CYCLING_DATA(6); operation byte = the op number;
     * file_tag is `0x55` for FILE_GET (TRANSMIT_COMPLETE), `0xFF`
     * everywhere else. Visible for testing.
     */
    internal fun buildCyclingDataHead(body: ByteArray, op: Int, fileTag: Int): ByteArray {
        val head = ByteArray(HEADER_SIZE)
        head[HDR_TYPE] = TYPE_PB.toByte()
        head[HDR_SERVICE] = (SERVICE_CYCLING_DATA and 0xFF).toByte()
        head[HDR_SUB_SERVICE] = RESERVED_BYTE.toByte()
        head[HDR_FILE_TAG] = (fileTag and 0xFF).toByte()
        head[HDR_OPERATION] = (op and 0xFF).toByte()
        head[HDR_SUB_OPERATION] = RESERVED_BYTE.toByte()
        head[HDR_RESERVED_6] = RESERVED_BYTE.toByte()
        head[HDR_PAYLOAD_SIZE] = ((body.size shr 8) and 0xFF).toByte()
        head[HDR_PAYLOAD_SIZE + 1] = (body.size and 0xFF).toByte()
        head[HDR_PAYLOAD_CRC] = crc8(body).toByte()
        head[HDR_END_MARKER] = TYPE_PB.toByte()
        for (off in HDR_RESERVED_PAD until HDR_RESERVED_PAD + RESERVED_PAD_LENGTH) {
            head[off] = RESERVED_BYTE.toByte()
        }
        head[HDR_HEADER_CRC] = crc8(head, 0, HDR_HEADER_CRC).toByte()
        return head
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
