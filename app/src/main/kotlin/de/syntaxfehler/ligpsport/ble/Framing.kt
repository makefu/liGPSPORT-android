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
    if (endMarker != TYPE_PB) {
        throw FrameError(
            "unexpected end_marker byte: 0x%02X (want 0x%02X)".format(endMarker, TYPE_PB),
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

fun expectedTotalSize(header: ByteArray): Int {
    if (header.size < HEADER_SIZE) {
        throw FrameError("header too short: ${header.size} < $HEADER_SIZE")
    }
    val typeByte = header[HDR_TYPE].toInt() and 0xFF
    return when (typeByte) {
        TYPE_PB -> HEADER_SIZE + (
            ((header[HDR_PAYLOAD_SIZE].toInt() and 0xFF) shl 8) or
                (header[HDR_PAYLOAD_SIZE + 1].toInt() and 0xFF)
            )
        TYPE_CONFIRM, TYPE_REQUEST -> HEADER_SIZE
        else -> throw FrameError("unknown frame type byte: 0x%02X".format(typeByte))
    }
}
