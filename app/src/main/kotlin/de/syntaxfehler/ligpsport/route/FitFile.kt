package de.syntaxfehler.ligpsport.route

/**
 * Just enough FIT-file handling to tell a good download from a bad one.
 *
 * Activity downloads come off the BSC200 as a flat byte stream with no
 * sequence numbers, so a lost or spliced BLE notification produces a
 * file of exactly the right length whose contents are wrong from the
 * damage onwards. The FIT container carries two CRCs that catch this;
 * checking them turns a silent corruption into an error we can act on.
 *
 * Layout (FIT SDK, "File Header"):
 * ```
 *   0      header size (12 or 14)
 *   1      protocol version
 *   2..3   profile version (LE)
 *   4..7   data size, excluding header and trailing CRC (LE)
 *   8..11  ".FIT"
 *   12..13 header CRC (only when header size is 14; 0 = not set)
 *   …      data
 *   last 2 file CRC (LE), over everything before it
 * ```
 */
object FitFile {

    /**
     * Seconds between the Unix epoch and the FIT/Garmin one
     * (1989-12-31T00:00:00Z). Timestamps on the wire — both inside FIT
     * files and in the BSC200's `CYCLING_DATA LIST_GET` entries — count
     * from the latter, so reading one as a Unix time lands you 20 years
     * early.
     */
    const val GARMIN_EPOCH_OFFSET_SECONDS = 631_065_600L

    /**
     * Convert a device/FIT timestamp to Unix epoch seconds.
     *
     * Caveat worth knowing: the BSC200's activity-list timestamps appear
     * to be *local* wall-clock, while `file_id.time_created` inside the
     * FIT is UTC — observed 12 h apart on a UTC+12 device. This function
     * only shifts the epoch; it cannot know the device's timezone, so a
     * converted list timestamp is the rider's local reading, not a true
     * instant.
     */
    fun garminToUnixSeconds(timestamp: Long): Long = timestamp + GARMIN_EPOCH_OFFSET_SECONDS

    /** Nibble-wise CRC-16 table from the FIT SDK. */
    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    sealed interface Verdict {
        data object Valid : Verdict
        data class Invalid(val reason: String) : Verdict
    }

    fun crc16(data: ByteArray, from: Int = 0, until: Int = data.size): Int {
        var crc = 0
        for (i in from until until) {
            val byte = data[i].toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[byte and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(byte shr 4) and 0xF]
        }
        return crc
    }

    /**
     * Structural + checksum check. Deliberately strict: anything that
     * fails here would be rejected by Strava as a malformed FIT, so
     * it's better caught at download time.
     */
    fun verify(bytes: ByteArray): Verdict {
        if (bytes.size < 14) return Verdict.Invalid("too short (${bytes.size} bytes)")
        val headerSize = bytes[0].toInt() and 0xFF
        if (headerSize != 12 && headerSize != 14) {
            return Verdict.Invalid("bad header size $headerSize (want 12 or 14)")
        }
        if (String(bytes, 8, 4, Charsets.US_ASCII) != ".FIT") {
            return Verdict.Invalid("missing .FIT signature")
        }
        val dataSize = le32(bytes, 4)
        val expected = headerSize + dataSize + 2
        if (expected != bytes.size) {
            return Verdict.Invalid("length mismatch: header says $expected, have ${bytes.size}")
        }
        // A zero header CRC means "not set" — legal, so only check a
        // non-zero one.
        if (headerSize == 14) {
            val stored = le16(bytes, 12)
            if (stored != 0 && stored != crc16(bytes, 0, 12)) {
                return Verdict.Invalid("header CRC mismatch")
            }
        }
        val storedFileCrc = le16(bytes, headerSize + dataSize)
        val actual = crc16(bytes, 0, headerSize + dataSize)
        if (storedFileCrc != actual) {
            return Verdict.Invalid(
                "file CRC mismatch (have 0x%04X, want 0x%04X) — transfer corrupted"
                    .format(actual, storedFileCrc),
            )
        }
        return Verdict.Valid
    }

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)
}
