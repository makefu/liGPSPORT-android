package de.syntaxfehler.ligpsport.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the wire format of ROUTE_PLAN FILE_USE — the
 * "start navigation" command issued after a successful CNX upload.
 * Matches ligpsport (Python) v1.2.0's `_build_file_use_pb`, the live
 * capture in PROTOCOL.md §7.2, and the gen-4 merged-write path the
 * BSC200 firmware actually accepts.
 */
class FileUseTest {

    @Test
    fun pb_body_contains_service_op_line_id_and_full_info_message() {
        val pb = FileTransfer.buildRoutePlanFileUsePb(fileId = 99, ext = "cnx")

        // service_type=7  → 08 07
        assertThat(pb[0]).isEqualTo(0x08.toByte())
        assertThat(pb[1]).isEqualTo(0x07.toByte())
        // route_plan_operate_type=5 (FILE_USE)
        assertThat(pb[2]).isEqualTo(0x10.toByte())
        assertThat(pb[3]).isEqualTo(0x05.toByte())
        // line_id "99.cnx"
        assertThat(pb[4]).isEqualTo(0x1A.toByte())
        assertThat(pb[5]).isEqualTo(6.toByte())
        assertThat(String(pb.copyOfRange(6, 12), Charsets.UTF_8)).isEqualTo("99.cnx")

        // route_plan_info_msg inner (BSC200 firmware requires all four):
        //   id (1)             = 99 → 08 63              (2B)
        //   file_type (2)      = 1  → 10 01              (2B, CNX)
        //   name (3)           = "99" → 1A 02 39 39      (4B)
        //   total_distance (4) = 0  → 20 00              (2B)
        // = 10 bytes inner
        assertThat(pb[12]).isEqualTo(0x2A.toByte())
        assertThat(pb[13]).isEqualTo(10.toByte())
        assertThat(pb[14]).isEqualTo(0x08.toByte())
        assertThat(pb[15]).isEqualTo(99.toByte())
        assertThat(pb[16]).isEqualTo(0x10.toByte())
        assertThat(pb[17]).isEqualTo(0x01.toByte())
        assertThat(pb[18]).isEqualTo(0x1A.toByte())
        assertThat(pb[19]).isEqualTo(0x02.toByte())
        assertThat(String(pb.copyOfRange(20, 22), Charsets.UTF_8)).isEqualTo("99")
        assertThat(pb[22]).isEqualTo(0x20.toByte())
        assertThat(pb[23]).isEqualTo(0.toByte())

        assertThat(pb.size).isEqualTo(24)
    }

    @Test
    fun gpx_extension_picks_file_type_2() {
        val pb = FileTransfer.buildRoutePlanFileUsePb(fileId = 1, ext = "gpx")
        val infoTagIdx = pb.indexOf(0x2A.toByte())
        val inner = pb.copyOfRange(infoTagIdx + 2, pb.size)
        // inner offset:
        //   0..1 : id (08 01)
        //   2..3 : file_type
        assertThat(inner[2]).isEqualTo(0x10.toByte()) // file_type tag
        assertThat(inner[3]).isEqualTo(0x02.toByte()) // GPX
    }

    @Test
    fun explicit_name_and_total_distance_round_trip() {
        val pb = FileTransfer.buildRoutePlanFileUsePb(
            fileId = 7,
            ext = "cnx",
            name = "tour",
            totalDistanceM = 12345L,
        )
        val infoTagIdx = pb.indexOf(0x2A.toByte())
        val inner = pb.copyOfRange(infoTagIdx + 2, pb.size)
        // inner: 08 07 | 10 01 | 1A 04 "tour" | 20 b9 60
        assertThat(inner[0]).isEqualTo(0x08.toByte())
        assertThat(inner[1]).isEqualTo(7.toByte())
        assertThat(inner[2]).isEqualTo(0x10.toByte())
        assertThat(inner[3]).isEqualTo(0x01.toByte())
        assertThat(inner[4]).isEqualTo(0x1A.toByte())
        assertThat(inner[5]).isEqualTo(4.toByte())
        assertThat(String(inner.copyOfRange(6, 10), Charsets.UTF_8)).isEqualTo("tour")
        assertThat(inner[10]).isEqualTo(0x20.toByte())
        // 12345 → varint b9 60
        assertThat(inner[11]).isEqualTo(0xB9.toByte())
        assertThat(inner[12]).isEqualTo(0x60.toByte())
    }

    @Test
    fun header_encodes_service_op_size_and_crcs() {
        val pb = FileTransfer.buildRoutePlanFileUsePb(fileId = 99, ext = "cnx")
        val head = FileTransfer.buildRoutePlanFileUseHeader(pb)

        assertThat(head.size).isEqualTo(20)
        assertThat(head[0]).isEqualTo(0x01.toByte())   // TYPE_PB
        assertThat(head[1]).isEqualTo(0x07.toByte())   // ROUTE_PLAN service
        assertThat(head[2]).isEqualTo(0xFF.toByte())   // sub_service
        assertThat(head[3]).isEqualTo(0xFF.toByte())   // file_tag
        assertThat(head[4]).isEqualTo(0x05.toByte())   // operation = FILE_USE
        assertThat(head[5]).isEqualTo(0xFF.toByte())   // sub_operation
        assertThat(head[6]).isEqualTo(0xFF.toByte())   // reserved
        // payload_size = pb.size (24 with all four info_msg fields)
        assertThat(head[7]).isEqualTo(0.toByte())
        assertThat(head[8]).isEqualTo(24.toByte())
        assertThat(head[9]).isEqualTo(crc8(pb).toByte())
        assertThat(head[10]).isEqualTo(0x01.toByte())  // END_TYPE_PB
        for (i in 11..18) assertThat(head[i]).isEqualTo(0xFF.toByte())
        assertThat(head[19]).isEqualTo(crc8(head, 0, 19).toByte())
    }
}
