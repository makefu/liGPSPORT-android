package de.syntaxfehler.ligpsport.ble

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.Test

/**
 * Pins the on-wire shape of the new CYCLING_DATA activity ops
 * (LIST_GET / FILE_GET / FILE_DEL / ALL_DEL). Mirrors the Python
 * reference in `ligpsport/file_transfer.py` v1.5.0; the field
 * numbers come straight from `app/src/main/proto/cycling_data.proto`
 * and `app/src/main/proto/file_download.proto`.
 *
 * Wire shapes here are exercised at the `FileTransfer.build*Pb` /
 * `FileTransfer.parseActivityDownloadPayload` /
 * `FileTransfer.decodeActivityListResponse` boundary so the tests
 * stay hermetic — no [Transport], no coroutines.
 */
class FileTransferActivitiesTest {

    // ---- Encoders ---------------------------------------------------

    @Test
    fun list_get_pb_encodes_service_op_and_inclusive_range() {
        // Default range [0, 100] — same idiom as ROUTE_PLAN LIST_GET.
        val pb = FileTransfer.buildCyclingDataListGetPb(start = 0, end = 100)
        // service_type=6 (CYCLING_DATA) → 08 06
        assertThat(pb[0]).isEqualTo(0x08.toByte())
        assertThat(pb[1]).isEqualTo(0x06.toByte())
        // operate_type=LIST_GET=1 → 10 01
        assertThat(pb[2]).isEqualTo(0x10.toByte())
        assertThat(pb[3]).isEqualTo(0x01.toByte())
        // list_msg = field 6, length-delimited → 32 <len>
        assertThat(pb[4]).isEqualTo(0x32.toByte())
        // inner = file_index_start=0, file_index_end=100
        //   field 3 varint 0 → 18 00
        //   field 4 varint 100 → 20 64
        // = 4 inner bytes
        assertThat(pb[5]).isEqualTo(4.toByte())
        assertThat(pb[6]).isEqualTo(0x18.toByte())
        assertThat(pb[7]).isEqualTo(0x00.toByte())
        assertThat(pb[8]).isEqualTo(0x20.toByte())
        assertThat(pb[9]).isEqualTo(0x64.toByte())
        assertThat(pb.size).isEqualTo(10)
    }

    @Test
    fun delete_activity_request_body_carries_timestamp() {
        // Use a small timestamp so the varint is unambiguous (single
        // byte) — the larger end of varint encoding is exercised
        // implicitly by the round-trip in ActivitiesRoundTripTest.
        val pb = FileTransfer.buildCyclingDataFileFlagPb(op = 5, timestamp = 100L)
        // service_type=6 → 08 06; operate_type=FILE_DEL=5 → 10 05
        // file_flag_msg = field 3, length-delimited → 1A <len>
        // inner = timestamp(1) varint 100 → 08 64  (length 2)
        assertThat(pb).isEqualTo(
            byteArrayOf(0x08, 0x06, 0x10, 0x05, 0x1A, 0x02, 0x08, 0x64),
        )
    }

    @Test
    fun delete_all_activities_body_is_just_service_and_op() {
        val pb = FileTransfer.buildCyclingDataAllDelPb()
        // service_type=6 → 08 06; operate_type=ALL_DEL=6 → 10 06
        assertThat(pb).isEqualTo(
            byteArrayOf(0x08, 0x06, 0x10, 0x06),
        )
    }

    @Test
    fun cycling_data_head_uses_service_6_and_named_op_and_file_tag() {
        val body = byteArrayOf(0x08, 0x06, 0x10, 0x06)  // ALL_DEL minimal body
        val head = FileTransfer.buildCyclingDataHead(body, op = 6, fileTag = 0xFF)
        assertThat(head.size).isEqualTo(20)
        assertThat(head[HDR_TYPE]).isEqualTo(TYPE_PB.toByte())
        assertThat(head[HDR_SERVICE]).isEqualTo(6.toByte())
        assertThat(head[HDR_SUB_SERVICE]).isEqualTo(0xFF.toByte())
        assertThat(head[HDR_FILE_TAG]).isEqualTo(0xFF.toByte())
        assertThat(head[HDR_OPERATION]).isEqualTo(6.toByte())
        assertThat(head[HDR_PAYLOAD_SIZE]).isEqualTo(0.toByte())
        assertThat(head[HDR_PAYLOAD_SIZE + 1]).isEqualTo(body.size.toByte())
        assertThat(head[HDR_PAYLOAD_CRC]).isEqualTo(crc8(body).toByte())
        assertThat(head[HDR_END_MARKER]).isEqualTo(TYPE_PB.toByte())
        for (i in HDR_RESERVED_PAD until HDR_HEADER_CRC) {
            assertThat(head[i]).isEqualTo(0xFF.toByte())
        }
        assertThat(head[HDR_HEADER_CRC]).isEqualTo(crc8(head, 0, HDR_HEADER_CRC).toByte())
    }

    @Test
    fun file_get_head_sets_transmit_complete_file_tag() {
        // The 0x55 magic is what makes the BSC200 actually reply —
        // every variant without it times out (PROTOCOL.md §6.4).
        val body = FileTransfer.buildCyclingDataFileFlagPb(op = 3, timestamp = 1147795610L)
        val head = FileTransfer.buildCyclingDataHead(body, op = 3, fileTag = 0x55)
        assertThat(head[HDR_FILE_TAG]).isEqualTo(0x55.toByte())
        assertThat(head[HDR_OPERATION]).isEqualTo(3.toByte())
    }

    // ---- Decoders ----------------------------------------------------

    @Test
    fun list_get_decoder_extracts_three_entries_and_drops_zero_padder() {
        // Hand-crafted reply payload: a cycling_data_msg with three
        // cycling_data_file_flag_msg entries (field 3, repeated):
        //   1) all four fields populated
        //   2) minimal — only timestamp
        //   3) all-zero padder — must be filtered out
        val out = ByteArrayOutputStream()
        // service_type=6, operate_type=LIST_SEND=2 (header — decoder skips)
        out.write(0x08); out.write(0x06)
        out.write(0x10); out.write(0x02)
        // entry 1: timestamp=1700000000, file_size=15572,
        //          user_id="u1", device_id="d1"
        run {
            val inner = ByteArrayOutputStream().apply {
                // timestamp = 1700000000 → varint 80 a4 e1 ca 06
                write(0x08); writePbVarint(this, 1_700_000_000L)
                // file_size = 15572 → varint 0xD4 0x79
                write(0x10); writePbVarint(this, 15572L)
                // user_id = "u1"
                write(0x1A); write(2); write('u'.code); write('1'.code)
                // device_id = "d1"
                write(0x22); write(2); write('d'.code); write('1'.code)
            }.toByteArray()
            out.write(0x1A)  // field 3, length-delimited
            writePbVarint(out, inner.size.toLong()); out.write(inner)
        }
        // entry 2: timestamp=1700001000 only
        run {
            val inner = ByteArrayOutputStream().apply {
                write(0x08); writePbVarint(this, 1_700_001_000L)
            }.toByteArray()
            out.write(0x1A); writePbVarint(out, inner.size.toLong()); out.write(inner)
        }
        // entry 3: zero padder — nothing inside (timestamp defaults to 0)
        out.write(0x1A); out.write(0)
        val payload = out.toByteArray()

        val entries = FileTransfer.decodeActivityListResponse(payload)
        assertThat(entries).hasSize(2)
        assertThat(entries[0].timestamp).isEqualTo(1_700_000_000L)
        assertThat(entries[0].fileSize).isEqualTo(15572L)
        assertThat(entries[0].userId).isEqualTo("u1")
        assertThat(entries[0].deviceId).isEqualTo("d1")
        assertThat(entries[1].timestamp).isEqualTo(1_700_001_000L)
        assertThat(entries[1].fileSize).isEqualTo(0L)
    }

    @Test
    fun file_get_payload_parser_extracts_pb_and_file_bytes() {
        // file_download protobuf: file_size=8 (field 1), file_id=42 (field 3),
        // file_name="ride.fit" (field 4)
        val pb = ByteArrayOutputStream().apply {
            write(0x08); writePbVarint(this, 8L)
            write(0x18); writePbVarint(this, 42L)
            write(0x22); write(8); write("ride.fit".toByteArray(Charsets.UTF_8))
        }.toByteArray()
        val fileBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val sizePrefix = byteArrayOf(0, 0, 0, pb.size.toByte())
        val payload = sizePrefix + pb + fileBytes

        val r = FileTransfer.parseActivityDownloadPayload(payload)
        assertThat(r.content).isEqualTo(fileBytes)
        assertThat(r.fileSize).isEqualTo(8L)
        assertThat(r.fileId).isEqualTo(42L)
        assertThat(r.fileName).isEqualTo("ride.fit")
    }

    private fun writePbVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7FL).toInt())
    }
}
