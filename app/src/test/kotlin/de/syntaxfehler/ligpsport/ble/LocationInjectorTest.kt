package de.syntaxfehler.ligpsport.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Lock the FACTORY GPS_COORDINATE_SET protobuf encoding against a
 * canonical byte vector. If `factory.proto` field numbers ever change
 * — or the wire builder ever mis-encodes a double — this test breaks
 * loudly before the bytes reach the device.
 */
class LocationInjectorTest {

    @Test
    fun encodes_canonical_berlin_coords() {
        // 52.5200°N, 13.4050°E (Berlin Brandenburger Tor).
        val bytes = LocationInjector.buildFactoryGpsCoordinatePb(
            lat = 52.52,
            lon = 13.405,
        )

        // factory_msg outer:
        //   field 1 (service_type) varint = 11 (FACTORY)        → 08 0B
        //   field 2 (factory_operate_type) varint = 8 (GPS_SET) → 10 08
        //   field 9 (gps_coordinate_msg) length-delimited       → 4A 14 <20 bytes>
        // gps_coordinate_message inner:
        //   field 1 (latitude)  fixed64 = 52.52                 → 09 + IEEE-754 LE bits
        //   field 2 (longitude) fixed64 = 13.405                → 11 + IEEE-754 LE bits
        //
        // Inner length = 1 + 8 + 1 + 8 = 18 bytes; outer message
        // length-delimited prefix is varint(18) = 0x12.

        // service_type tag + value
        assertThat(bytes[0]).isEqualTo(0x08.toByte())
        assertThat(bytes[1]).isEqualTo(11.toByte())
        // factory_operate_type tag + value
        assertThat(bytes[2]).isEqualTo(0x10.toByte())
        assertThat(bytes[3]).isEqualTo(8.toByte())
        // gps_coordinate_msg tag (9<<3 | 2 = 0x4A) + length (18)
        assertThat(bytes[4]).isEqualTo(0x4A.toByte())
        assertThat(bytes[5]).isEqualTo(18.toByte())

        // latitude tag (1<<3 | 1 = 0x09) at offset 6
        assertThat(bytes[6]).isEqualTo(0x09.toByte())
        val latBits = (0 until 8).fold(0L) { acc, i ->
            acc or ((bytes[7 + i].toLong() and 0xFF) shl (8 * i))
        }
        assertThat(java.lang.Double.longBitsToDouble(latBits)).isEqualTo(52.52)

        // longitude tag (2<<3 | 1 = 0x11) at offset 15
        assertThat(bytes[15]).isEqualTo(0x11.toByte())
        val lonBits = (0 until 8).fold(0L) { acc, i ->
            acc or ((bytes[16 + i].toLong() and 0xFF) shl (8 * i))
        }
        assertThat(java.lang.Double.longBitsToDouble(lonBits)).isEqualTo(13.405)

        // Total length: 6 outer-header bytes + 18 inner bytes = 24.
        assertThat(bytes.size).isEqualTo(24)
    }

    @Test
    fun encodes_southern_hemisphere_negative_coords() {
        // Sydney (-33.8688°S, 151.2093°E) — exercises the sign bit of
        // the double encoding.
        val bytes = LocationInjector.buildFactoryGpsCoordinatePb(
            lat = -33.8688,
            lon = 151.2093,
        )
        val latBits = (0 until 8).fold(0L) { acc, i ->
            acc or ((bytes[7 + i].toLong() and 0xFF) shl (8 * i))
        }
        assertThat(java.lang.Double.longBitsToDouble(latBits)).isEqualTo(-33.8688)
        val lonBits = (0 until 8).fold(0L) { acc, i ->
            acc or ((bytes[16 + i].toLong() and 0xFF) shl (8 * i))
        }
        assertThat(java.lang.Double.longBitsToDouble(lonBits)).isEqualTo(151.2093)
    }

    @Test
    fun encodes_zero_coords_when_no_fix() {
        // factory.proto comment: "未定位则值为0" (lat=lon=0 means no fix).
        val bytes = LocationInjector.buildFactoryGpsCoordinatePb(lat = 0.0, lon = 0.0)
        // All eight bytes of each double should be 0x00.
        for (i in 7..14) assertThat(bytes[i]).isEqualTo(0.toByte())
        for (i in 16..23) assertThat(bytes[i]).isEqualTo(0.toByte())
    }
}
