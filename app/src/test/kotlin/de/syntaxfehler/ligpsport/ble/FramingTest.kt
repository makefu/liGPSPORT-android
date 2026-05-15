package de.syntaxfehler.ligpsport.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FramingTest {
    @Test
    fun pb_frame_roundtrips() {
        val payload = "hello".toByteArray(Charsets.UTF_8)
        val f = Frame(service = 7, operation = 3, payload = payload)
        val bytes = buildFrame(f)
        assertThat(bytes.size).isEqualTo(HEADER_SIZE + payload.size)
        // Type byte
        assertThat(bytes[0].toInt() and 0xFF).isEqualTo(TYPE_PB)
        // Service / operation
        assertThat(bytes[HDR_SERVICE].toInt() and 0xFF).isEqualTo(7)
        assertThat(bytes[HDR_OPERATION].toInt() and 0xFF).isEqualTo(3)
        // Payload size BE u16
        val size = ((bytes[HDR_PAYLOAD_SIZE].toInt() and 0xFF) shl 8) or
            (bytes[HDR_PAYLOAD_SIZE + 1].toInt() and 0xFF)
        assertThat(size).isEqualTo(payload.size)
        // End marker
        assertThat(bytes[HDR_END_MARKER].toInt() and 0xFF).isEqualTo(TYPE_PB)
        // Round-trip parse
        val parsed = parseFrame(bytes)
        assertThat(parsed).isEqualTo(f.copy(subService = 0xFF, subOperation = 0xFF, fileTag = 0xFF))
    }

    @Test
    fun confirm_frame_roundtrips() {
        val f = Frame(service = 7, operation = 3, type = TYPE_CONFIRM, status = 0)
        val bytes = buildFrame(f)
        assertThat(bytes.size).isEqualTo(HEADER_SIZE)
        val parsed = parseFrame(bytes)
        assertThat(parsed.type).isEqualTo(TYPE_CONFIRM)
        assertThat(parsed.service).isEqualTo(7)
        assertThat(parsed.operation).isEqualTo(3)
        assertThat(parsed.status).isEqualTo(0)
    }

    @Test
    fun parse_rejects_bad_header_crc() {
        val bytes = buildFrame(Frame(service = 1)).copyOf()
        bytes[HDR_HEADER_CRC] = (bytes[HDR_HEADER_CRC].toInt() xor 0xFF).toByte()
        try {
            parseFrame(bytes)
            error("expected FrameError")
        } catch (_: FrameError) {
            // expected
        }
    }

    @Test
    fun parse_rejects_bad_payload_crc() {
        val payload = "hello".toByteArray(Charsets.UTF_8)
        val bytes = buildFrame(Frame(service = 1, payload = payload)).copyOf()
        bytes[HDR_PAYLOAD_CRC] = (bytes[HDR_PAYLOAD_CRC].toInt() xor 0xFF).toByte()
        // Fix the header CRC so we hit the payload CRC check
        bytes[HDR_HEADER_CRC] = crc8(bytes, 0, HDR_HEADER_CRC).toByte()
        try {
            parseFrame(bytes)
            error("expected FrameError")
        } catch (_: FrameError) {
            // expected
        }
    }

    @Test
    fun expected_total_size_pb() {
        val bytes = buildFrame(Frame(service = 1, payload = ByteArray(42) { it.toByte() }))
        val total = expectedTotalSize(bytes.copyOfRange(0, HEADER_SIZE))
        assertThat(total).isEqualTo(HEADER_SIZE + 42)
    }

    @Test
    fun expected_total_size_confirm() {
        val bytes = buildFrame(Frame(service = 1, type = TYPE_CONFIRM))
        val total = expectedTotalSize(bytes)
        assertThat(total).isEqualTo(HEADER_SIZE)
    }

    @Test
    fun expected_total_size_returns_null_for_transmit_complete_head() {
        // CYCLING_DATA FILE_GET reply head: file_tag=0x55, end_marker=0x03,
        // payload_size deliberately bogus on BSC200. The reassembly path
        // must defer to transmitCompleteTotalSize() instead.
        val head = ByteArray(HEADER_SIZE).apply {
            this[HDR_TYPE] = TYPE_PB.toByte()
            this[HDR_SERVICE] = 6.toByte()
            this[HDR_SUB_SERVICE] = 0xFF.toByte()
            this[HDR_FILE_TAG] = FILE_TAG_TRANSMIT_COMPLETE.toByte()
            this[HDR_OPERATION] = 4.toByte()
            this[HDR_SUB_OPERATION] = 0xFF.toByte()
            this[HDR_RESERVED_6] = 0xFF.toByte()
            // payload_size = 0x07A7 (1959) — bogus on real firmware, but
            // the fact that file_tag == 0x55 must short-circuit before
            // the size is consulted.
            this[HDR_PAYLOAD_SIZE] = 0x07.toByte()
            this[HDR_PAYLOAD_SIZE + 1] = 0xA7.toByte()
            this[HDR_PAYLOAD_CRC] = 0x00.toByte()
            this[HDR_END_MARKER] = END_MARKER_LAST.toByte()
            for (i in HDR_RESERVED_PAD until HDR_HEADER_CRC) this[i] = 0xFF.toByte()
            this[HDR_HEADER_CRC] = crc8(this, 0, HDR_HEADER_CRC).toByte()
        }
        assertThat(expectedTotalSize(head)).isNull()
    }

    @Test
    fun transmit_complete_total_size_extracts_pb_and_file_size() {
        // file_download protobuf with file_size=15572 (varint 0x79D4):
        //   tag (1<<3|0)=0x08 | varint 0xD4 0x79
        val pb = byteArrayOf(0x08.toByte(), 0xD4.toByte(), 0x79.toByte())
        val head = ByteArray(HEADER_SIZE).apply {
            this[HDR_TYPE] = TYPE_PB.toByte()
            this[HDR_FILE_TAG] = FILE_TAG_TRANSMIT_COMPLETE.toByte()
        }
        val sizePrefix = byteArrayOf(0, 0, 0, pb.size.toByte())
        val payload = ByteArray(15572)
        val full = head + sizePrefix + pb + payload
        assertThat(transmitCompleteTotalSize(full))
            .isEqualTo(HEADER_SIZE + 4 + pb.size + 15572)
        // Returns null when only the head is present.
        assertThat(transmitCompleteTotalSize(head)).isNull()
    }
}
