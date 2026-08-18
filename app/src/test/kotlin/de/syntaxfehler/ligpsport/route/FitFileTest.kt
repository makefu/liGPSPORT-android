package de.syntaxfehler.ligpsport.route

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The case that matters is the one seen in the field: a download of
 * exactly the right length whose middle bytes are wrong. Length and
 * signature checks sail past that — only the CRC catches it.
 */
class FitFileTest {

    /** Minimal well-formed FIT: 14-byte header, `data`, trailing CRC. */
    private fun buildFit(data: ByteArray, headerSize: Int = 14): ByteArray {
        val out = ByteArray(headerSize + data.size + 2)
        out[0] = headerSize.toByte()
        out[1] = 0x10 // protocol version
        out[2] = 0x54; out[3] = 0x08 // profile version
        out[4] = (data.size and 0xFF).toByte()
        out[5] = ((data.size shr 8) and 0xFF).toByte()
        out[6] = ((data.size shr 16) and 0xFF).toByte()
        out[7] = ((data.size shr 24) and 0xFF).toByte()
        ".FIT".toByteArray(Charsets.US_ASCII).copyInto(out, 8)
        if (headerSize == 14) {
            val hcrc = FitFile.crc16(out, 0, 12)
            out[12] = (hcrc and 0xFF).toByte()
            out[13] = ((hcrc shr 8) and 0xFF).toByte()
        }
        data.copyInto(out, headerSize)
        val fcrc = FitFile.crc16(out, 0, headerSize + data.size)
        out[headerSize + data.size] = (fcrc and 0xFF).toByte()
        out[headerSize + data.size + 1] = ((fcrc shr 8) and 0xFF).toByte()
        return out
    }

    private val payload = ByteArray(256) { (it * 7).toByte() }

    @Test
    fun accepts_well_formed_file() {
        assertThat(FitFile.verify(buildFit(payload))).isEqualTo(FitFile.Verdict.Valid)
    }

    @Test
    fun accepts_12_byte_header_without_header_crc() {
        assertThat(FitFile.verify(buildFit(payload, headerSize = 12)))
            .isEqualTo(FitFile.Verdict.Valid)
    }

    /** The real-world failure: right length, wrong bytes in the middle. */
    @Test
    fun rejects_corruption_that_preserves_length() {
        val fit = buildFit(payload)
        fit[100] = (fit[100].toInt() xor 0xFF).toByte()
        val verdict = FitFile.verify(fit)
        assertThat(verdict).isInstanceOf(FitFile.Verdict.Invalid::class.java)
        assertThat((verdict as FitFile.Verdict.Invalid).reason).contains("file CRC mismatch")
    }

    @Test
    fun rejects_truncated_file() {
        val fit = buildFit(payload).copyOfRange(0, 200)
        val verdict = FitFile.verify(fit)
        assertThat(verdict).isInstanceOf(FitFile.Verdict.Invalid::class.java)
        assertThat((verdict as FitFile.Verdict.Invalid).reason).contains("length mismatch")
    }

    @Test
    fun rejects_non_fit_payload() {
        val verdict = FitFile.verify(ByteArray(64) { 0x41 })
        assertThat(verdict).isInstanceOf(FitFile.Verdict.Invalid::class.java)
    }

    @Test
    fun rejects_empty_input() {
        assertThat(FitFile.verify(ByteArray(0)))
            .isInstanceOf(FitFile.Verdict.Invalid::class.java)
    }

    /** Pins the table-driven CRC against the FIT SDK's own test vector. */
    @Test
    fun crc_matches_known_vector() {
        assertThat(FitFile.crc16("123456789".toByteArray())).isEqualTo(0xBB3D)
    }

    /**
     * Regression: a real BSC300T activity-list timestamp. Read as Unix
     * it lands in 2006; the FIT epoch puts it where the ride actually
     * happened.
     */
    @Test
    fun converts_device_timestamp_from_fit_epoch() {
        val fromDevice = 1_154_857_246L
        assertThat(FitFile.garminToUnixSeconds(fromDevice)).isEqualTo(1_785_922_846L)
        // 1785922846 == 2026-08-05T09:40:46Z, twenty years on from the
        // 2006 date the raw value decodes to.
    }
}
