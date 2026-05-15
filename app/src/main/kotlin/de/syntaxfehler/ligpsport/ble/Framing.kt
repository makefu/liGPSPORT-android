package de.syntaxfehler.ligpsport.ble

/**
 * Byte-level framing for the iGPSPORT BLE protocol — Kotlin port of
 * ligpsport/framing.py. Every logical message is exactly 20 bytes of
 * fixed-size header followed by 0+ bytes of protobuf payload, with the
 * type byte at offset 0 selecting between three layouts (PbFrame,
 * ConfirmFrame, RequestFrame). See /home/makefu/r/ligpsport/docs/PROTOCOL.md
 * for the wire spec.
 */

const val HEADER_SIZE = 20

const val HDR_TYPE = 0
const val HDR_SERVICE = 1
const val HDR_SUB_SERVICE = 2
const val HDR_FILE_TAG = 3
const val HDR_OPERATION = 4
const val HDR_SUB_OPERATION = 5
const val HDR_RESERVED_6 = 6
const val HDR_PAYLOAD_SIZE = 7
const val HDR_STATUS = 7
const val HDR_PAYLOAD_CRC = 9
const val HDR_END_MARKER = 10
const val HDR_RESERVED_PAD = 11
const val HDR_HEADER_CRC = 19

const val TYPE_PB: Int = 0x01
const val TYPE_CONFIRM: Int = 0x02
const val TYPE_REQUEST: Int = 0x03

// `end_marker` byte (offset 10). 0x01 is the ordinary request/response
// sentinel; the chunked file paths (ROUTE_PLAN FILE_SEND uploads,
// CYCLING_DATA FILE_GET responses) reuse the same slot to signal
// chunk-position: 0x02 = "more chunks coming", 0x03 = "this is the
// last chunk". PROTOCOL.md §6.4 captures the BSC200 FILE_GET reply
// (file_tag=0x55, end_marker=0x03).
const val END_MARKER_PB: Int = 0x01
const val END_MARKER_CONTINUE: Int = 0x02
const val END_MARKER_LAST: Int = 0x03

// `file_tag` byte (offset 3). 0xFF is the unset default; 0xAA marks
// the FILE_OPERATION ADD upload stream; 0x55 marks a "transmit-
// complete" multi-burst download — the BSC200 uses it for the
// CYCLING_DATA FILE_GET activity-download response (PROTOCOL.md
// §6.4 / §7.5). The 0x55 magic is the smali's
// `TransmitCompleteCommand`; without it the device silently
// ignores the FILE_GET request.
const val FILE_TAG_DEFAULT: Int = 0xFF
const val FILE_TAG_FILE_OPERATION_UPLOAD: Int = 0xAA
const val FILE_TAG_TRANSMIT_COMPLETE: Int = 0x55

const val RESERVED_BYTE: Int = 0xFF
const val RESERVED_PAD_LENGTH = 8
const val CONFIRM_RESERVED_PAD_LENGTH = 11

class FrameError(message: String) : IllegalArgumentException(message)

data class Frame(
    val service: Int,
    val payload: ByteArray = ByteArray(0),
    val operation: Int = 0xFF,
    val subService: Int = 0xFF,
    val subOperation: Int = 0xFF,
    val fileTag: Int = 0xFF,
    val type: Int = TYPE_PB,
    val status: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return service == other.service &&
            payload.contentEquals(other.payload) &&
            operation == other.operation &&
            subService == other.subService &&
            subOperation == other.subOperation &&
            fileTag == other.fileTag &&
            type == other.type &&
            status == other.status
    }

    override fun hashCode(): Int {
        var result = service
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + operation
        result = 31 * result + subService
        result = 31 * result + subOperation
        result = 31 * result + fileTag
        result = 31 * result + type
        result = 31 * result + status
        return result
    }
}

fun buildFrame(frame: Frame): ByteArray = when (frame.type) {
    TYPE_PB -> buildPbFrame(frame)
    TYPE_CONFIRM, TYPE_REQUEST -> {
        if (frame.payload.isNotEmpty()) {
            throw FrameError(
                "type=0x%02X frames carry no payload (got %d bytes)".format(
                    frame.type, frame.payload.size,
                ),
            )
        }
        buildConfirmFrame(frame)
    }
    else -> throw FrameError("unknown frame type: 0x%02X".format(frame.type))
}

private fun buildPbFrame(frame: Frame): ByteArray {
    val payload = frame.payload
    val size = payload.size
    if (size > 0xFFFF) {
        throw FrameError("payload too large for u16 size field: $size bytes")
    }
    val header = ByteArray(HEADER_SIZE)
    header[HDR_TYPE] = TYPE_PB.toByte()
    header[HDR_SERVICE] = (frame.service and 0xFF).toByte()
    header[HDR_SUB_SERVICE] = (frame.subService and 0xFF).toByte()
    header[HDR_FILE_TAG] = (frame.fileTag and 0xFF).toByte()
    header[HDR_OPERATION] = (frame.operation and 0xFF).toByte()
    header[HDR_SUB_OPERATION] = (frame.subOperation and 0xFF).toByte()
    header[HDR_RESERVED_6] = RESERVED_BYTE.toByte()
    header[HDR_PAYLOAD_SIZE] = ((size shr 8) and 0xFF).toByte()
    header[HDR_PAYLOAD_SIZE + 1] = (size and 0xFF).toByte()
    header[HDR_PAYLOAD_CRC] = crc8(payload).toByte()
    header[HDR_END_MARKER] = TYPE_PB.toByte()
    for (off in HDR_RESERVED_PAD until HDR_RESERVED_PAD + RESERVED_PAD_LENGTH) {
        header[off] = RESERVED_BYTE.toByte()
    }
    header[HDR_HEADER_CRC] = crc8(header, 0, HDR_HEADER_CRC).toByte()
    return header + payload
}

private fun buildConfirmFrame(frame: Frame): ByteArray {
    val header = ByteArray(HEADER_SIZE)
    header[HDR_TYPE] = (frame.type and 0xFF).toByte()
    header[HDR_SERVICE] = (frame.service and 0xFF).toByte()
    header[HDR_SUB_SERVICE] = (frame.subService and 0xFF).toByte()
    header[3] = RESERVED_BYTE.toByte()
    header[HDR_OPERATION] = (frame.operation and 0xFF).toByte()
    header[HDR_SUB_OPERATION] = (frame.subOperation and 0xFF).toByte()
    header[HDR_RESERVED_6] = RESERVED_BYTE.toByte()
    header[HDR_STATUS] = (frame.status and 0xFF).toByte()
    for (off in 8 until 8 + CONFIRM_RESERVED_PAD_LENGTH) {
        header[off] = RESERVED_BYTE.toByte()
    }
    header[HDR_HEADER_CRC] = crc8(header, 0, HDR_HEADER_CRC).toByte()
    return header
}

fun parseFrame(buf: ByteArray): Frame {
    if (buf.size < HEADER_SIZE) {
        throw FrameError("frame too short: ${buf.size} < $HEADER_SIZE")
    }
    val typeByte = buf[HDR_TYPE].toInt() and 0xFF
    val crcObserved = buf[HDR_HEADER_CRC].toInt() and 0xFF
    val crcExpected = crc8(buf, 0, HDR_HEADER_CRC)
    if (crcObserved != crcExpected) {
        throw FrameError(
            "header CRC mismatch: have 0x%02X, want 0x%02X".format(crcObserved, crcExpected),
        )
    }
    return when (typeByte) {
        TYPE_PB -> parsePbFrame(buf)
        TYPE_CONFIRM, TYPE_REQUEST -> {
            if (buf.size != HEADER_SIZE) {
                throw FrameError(
                    "type=0x%02X frames are 20 bytes; got %d".format(typeByte, buf.size),
                )
            }
            Frame(
                type = typeByte,
                service = buf[HDR_SERVICE].toInt() and 0xFF,
                operation = buf[HDR_OPERATION].toInt() and 0xFF,
                subService = buf[HDR_SUB_SERVICE].toInt() and 0xFF,
                subOperation = buf[HDR_SUB_OPERATION].toInt() and 0xFF,
                status = buf[HDR_STATUS].toInt() and 0xFF,
            )
        }
        else -> throw FrameError("unknown frame type byte: 0x%02X".format(typeByte))
    }
}

private fun parsePbFrame(buf: ByteArray): Frame {
    val endMarker = buf[HDR_END_MARKER].toInt() and 0xFF
    // Accept all three end-marker values: ordinary request/response
    // (0x01), continue (0x02) and last-chunk (0x03). The chunked
    // CYCLING_DATA FILE_GET response uses 0x03.
    if (endMarker !in setOf(END_MARKER_PB, END_MARKER_CONTINUE, END_MARKER_LAST)) {
        throw FrameError(
            "unexpected end_marker byte: 0x%02X (want 0x01/0x02/0x03)".format(endMarker),
        )
    }
    if (isTransmitCompleteHead(buf)) {
        // file_tag=0x55 transmit-complete download (PROTOCOL.md §6.4).
        // The head's `payload_size` is bogus on BSC200 firmware
        // (always 0x07A7) and `payload_crc` is zero — the real length
        // is computed from the embedded `file_download` protobuf by
        // the reassembly path. Pass the post-header bytes through
        // unchanged; consumers split them into pb_size / file_download
        // pb / file content themselves.
        return Frame(
            type = TYPE_PB,
            service = buf[HDR_SERVICE].toInt() and 0xFF,
            operation = buf[HDR_OPERATION].toInt() and 0xFF,
            subService = buf[HDR_SUB_SERVICE].toInt() and 0xFF,
            subOperation = buf[HDR_SUB_OPERATION].toInt() and 0xFF,
            fileTag = buf[HDR_FILE_TAG].toInt() and 0xFF,
            payload = buf.copyOfRange(HEADER_SIZE, buf.size),
        )
    }
    val size = ((buf[HDR_PAYLOAD_SIZE].toInt() and 0xFF) shl 8) or
        (buf[HDR_PAYLOAD_SIZE + 1].toInt() and 0xFF)
    val expectedTotal = HEADER_SIZE + size
    if (buf.size != expectedTotal) {
        throw FrameError(
            "frame length mismatch: have ${buf.size}, header says $expectedTotal",
        )
    }
    val payload = buf.copyOfRange(HEADER_SIZE, buf.size)
    val payloadCrcObserved = buf[HDR_PAYLOAD_CRC].toInt() and 0xFF
    val payloadCrcExpected = crc8(payload)
    if (payloadCrcObserved != payloadCrcExpected) {
        throw FrameError(
            "payload CRC mismatch: have 0x%02X, want 0x%02X"
                .format(payloadCrcObserved, payloadCrcExpected),
        )
    }
    return Frame(
        type = TYPE_PB,
        service = buf[HDR_SERVICE].toInt() and 0xFF,
        operation = buf[HDR_OPERATION].toInt() and 0xFF,
        subService = buf[HDR_SUB_SERVICE].toInt() and 0xFF,
        subOperation = buf[HDR_SUB_OPERATION].toInt() and 0xFF,
        fileTag = buf[HDR_FILE_TAG].toInt() and 0xFF,
        payload = payload,
    )
}

/**
 * True when [header] (≥20 bytes) marks a CYCLING_DATA transmit-
 * complete download stream — `file_tag = 0x55` on a PB frame. The
 * BSC200 streams activity downloads this way; the head's
 * `payload_size` field is *not* the stream length and the
 * `payload_crc` byte is zero.
 */
fun isTransmitCompleteHead(header: ByteArray): Boolean {
    if (header.size < HEADER_SIZE) return false
    val type = header[HDR_TYPE].toInt() and 0xFF
    val tag = header[HDR_FILE_TAG].toInt() and 0xFF
    return type == TYPE_PB && tag == FILE_TAG_TRANSMIT_COMPLETE
}

/**
 * Decode `file_download.proto` field 1 (`file_size`, varint) from a
 * raw protobuf buffer. Used by the reassembly path so it can compute
 * a transmit-complete stream length without pulling in a generated
 * protobuf binding. Throws [FrameError] when the buffer is truncated
 * or doesn't carry field 1.
 */
fun parseFileDownloadSize(pbBytes: ByteArray): Long {
    var pos = 0
    while (pos < pbBytes.size) {
        val (tag, p1) = readPbVarint(pbBytes, pos)
        pos = p1
        val fieldNum = (tag ushr 3).toInt()
        val wireType = (tag and 0x07L).toInt()
        when (wireType) {
            0 -> {
                val (value, p2) = readPbVarint(pbBytes, pos)
                pos = p2
                if (fieldNum == 1) return value
            }
            2 -> {
                val (length, p2) = readPbVarint(pbBytes, pos)
                pos = p2 + length.toInt()
            }
            1 -> pos += 8
            5 -> pos += 4
            else -> throw FrameError("unsupported wire_type $wireType")
        }
    }
    throw FrameError("file_download protobuf does not carry file_size (field 1)")
}

private fun readPbVarint(buf: ByteArray, start: Int): Pair<Long, Int> {
    var value = 0L
    var shift = 0
    var pos = start
    while (pos < buf.size) {
        val b = buf[pos].toInt() and 0xFF
        pos += 1
        value = value or ((b and 0x7F).toLong() shl shift)
        if ((b and 0x80) == 0) return value to pos
        shift += 7
        if (shift > 63) throw FrameError("varint overflow")
    }
    throw FrameError("varint truncated")
}

/**
 * Total size of an in-flight transmit-complete download stream —
 * mirror of `framing.transmit_complete_total_size` (Python). Returns
 * `null` while [buf] is too short to determine the length (we still
 * need more chunks to read the embedded file_download protobuf).
 *
 * Stream layout (from PROTOCOL.md §6.4):
 *   [0..19]                  20-byte head (file_tag=0x55)
 *   [20..23]                 4-byte big-endian pb_size
 *   [24..24+pb_size]         file_download protobuf
 *   [24+pb_size..]           file_size bytes of raw FIT content
 */
fun transmitCompleteTotalSize(buf: ByteArray): Int? {
    if (buf.size < HEADER_SIZE + 4) return null
    val pbSize = ((buf[HEADER_SIZE].toInt() and 0xFF) shl 24) or
        ((buf[HEADER_SIZE + 1].toInt() and 0xFF) shl 16) or
        ((buf[HEADER_SIZE + 2].toInt() and 0xFF) shl 8) or
        (buf[HEADER_SIZE + 3].toInt() and 0xFF)
    val pbEnd = HEADER_SIZE + 4 + pbSize
    if (buf.size < pbEnd) return null
    val fileSize = parseFileDownloadSize(buf.copyOfRange(HEADER_SIZE + 4, pbEnd))
    return pbEnd + fileSize.toInt()
}

/**
 * Total wire size from a 20-byte header. Returns `null` for
 * transmit-complete download heads (`file_tag = 0x55`) — the head
 * alone doesn't carry the true stream length; the BLE reassembly
 * loop must accumulate more bytes and call [transmitCompleteTotalSize]
 * until it returns a non-null value.
 */
fun expectedTotalSize(header: ByteArray): Int? {
    if (header.size < HEADER_SIZE) {
        throw FrameError("header too short: ${header.size} < $HEADER_SIZE")
    }
    val typeByte = header[HDR_TYPE].toInt() and 0xFF
    return when (typeByte) {
        TYPE_PB -> {
            if (isTransmitCompleteHead(header)) null
            else HEADER_SIZE + (
                ((header[HDR_PAYLOAD_SIZE].toInt() and 0xFF) shl 8) or
                    (header[HDR_PAYLOAD_SIZE + 1].toInt() and 0xFF)
                )
        }
        TYPE_CONFIRM, TYPE_REQUEST -> HEADER_SIZE
        else -> throw FrameError("unknown frame type byte: 0x%02X".format(typeByte))
    }
}
