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
}
