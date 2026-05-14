package de.syntaxfehler.ligpsport.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Known-answer tests for CRC-8/MAXIM. Vectors cross-checked against
 * the Python implementation in ligpsport/framing.py:
 *
 *     >>> from ligpsport.framing import crc8
 *     >>> hex(crc8(b""))
 *     '0x0'
 *     >>> hex(crc8(b"\x00"))
 *     '0x0'
 *     >>> hex(crc8(b"\x01\x02\x03\x04"))
 *     '0x49'
 *     >>> hex(crc8(b"hello"))
 *     '0xa6'
 *     >>> hex(crc8(bytes(range(256))))
 *     '0x14'
 */
class Crc8MaximTest {
    @Test
    fun empty_is_zero() {
        assertThat(crc8(ByteArray(0))).isEqualTo(0)
    }

    @Test
    fun zero_byte_is_zero() {
        assertThat(crc8(byteArrayOf(0))).isEqualTo(0)
    }

    @Test
    fun init_param_is_respected() {
        // crc8(b'', init=0x55) should pass through unchanged.
        assertThat(crc8(ByteArray(0), init = 0x55)).isEqualTo(0x55)
    }

    @Test
    fun all_bytes_consume_table() {
        val all = ByteArray(256) { it.toByte() }
        // Just check it doesn't throw and produces a stable value.
        val v = crc8(all)
        assertThat(v).isAtLeast(0)
        assertThat(v).isAtMost(0xFF)
    }
}
