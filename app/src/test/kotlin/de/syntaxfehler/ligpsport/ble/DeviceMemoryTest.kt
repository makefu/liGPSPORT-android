package de.syntaxfehler.ligpsport.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Wire encode/decode for FACTORY `MEMORY_GET`. The request is the only
 * part that has to be exactly right before hardware sees it — a wrong
 * field number is a silent no-reply, indistinguishable from firmware
 * that doesn't implement the operation.
 */
class DeviceMemoryTest {

    // ---- Request ------------------------------------------------------

    /**
     * `factory_msg{service_type=11, factory_operate_type=5}`:
     * field 1 varint (tag 0x08) = 11, field 2 varint (tag 0x10) = 5.
     */
    @Test
    fun the_request_is_service_eleven_operation_five() {
        assertThat(DeviceMemory.buildMemoryGetPb().toList())
            .containsExactly(0x08.toByte(), 11.toByte(), 0x10.toByte(), 5.toByte())
            .inOrder()
    }

    // ---- Reply --------------------------------------------------------

    @Test
    fun a_memory_submessage_decodes_to_total_and_free() {
        val info = DeviceMemory.decodeMemoryReply(factoryReply(total = 8_000_000, remain = 3_000_000))

        assertThat(info).isNotNull()
        assertThat(info!!.total).isEqualTo(8_000_000L * DeviceMemory.BYTES_PER_UNIT)
        assertThat(info.free).isEqualTo(3_000_000L * DeviceMemory.BYTES_PER_UNIT)
        assertThat(info.used).isEqualTo(5_000_000L * DeviceMemory.BYTES_PER_UNIT)
    }

    /**
     * A bare ack carries no memory submessage. `read` relies on that
     * decoding to null so it keeps waiting for the real reply instead
     * of treating the ack as the answer.
     */
    @Test
    fun an_empty_or_unrelated_payload_decodes_to_null() {
        assertThat(DeviceMemory.decodeMemoryReply(ByteArray(0))).isNull()
        // factory_msg with only service_type/operate_type — no memory_msg.
        assertThat(DeviceMemory.decodeMemoryReply(DeviceMemory.buildMemoryGetPb())).isNull()
    }

    /** Both fields are `optional`; half a reply can't draw a bar. */
    @Test
    fun a_half_populated_memory_message_decodes_to_null() {
        val onlyTotal = ByteArrayOutputStream().apply {
            val inner = ByteArrayOutputStream().apply {
                write(0x08); writeVarint(this, 4096)
            }.toByteArray()
            write(0x08); writeVarint(this, 11)
            write(0x10); writeVarint(this, 5)
            writeTag(this, field = 5, wire = 2); writeVarint(this, inner.size.toLong()); write(inner)
        }.toByteArray()

        assertThat(DeviceMemory.decodeMemoryReply(onlyTotal)).isNull()
    }

    /** Schema additions must not break the parse. */
    @Test
    fun unknown_fields_around_the_memory_message_are_skipped() {
        val withExtras = ByteArrayOutputStream().apply {
            write(0x08); writeVarint(this, 11)
            write(0x10); writeVarint(this, 5)
            // Unknown varint field 30, and an unknown length-delimited
            // 31. Both tags exceed 127, so they are two-byte varints —
            // which is the point: a reader that assumed one byte per
            // tag would desynchronise here.
            writeTag(this, field = 30, wire = 0); writeVarint(this, 1234)
            val junk = byteArrayOf(1, 2, 3)
            writeTag(this, field = 31, wire = 2); writeVarint(this, junk.size.toLong()); write(junk)
            val inner = memoryMessage(total = 100, remain = 40)
            writeTag(this, field = 5, wire = 2); writeVarint(this, inner.size.toLong()); write(inner)
        }.toByteArray()

        val info = DeviceMemory.decodeMemoryReply(withExtras)
        assertThat(info?.total).isEqualTo(100L * DeviceMemory.BYTES_PER_UNIT)
        assertThat(info?.free).isEqualTo(40L * DeviceMemory.BYTES_PER_UNIT)
    }

    // ---- Info ---------------------------------------------------------

    @Test
    fun fraction_used_is_the_complement_of_free() {
        assertThat(DeviceMemory.Info(total = 1000, free = 250).fractionUsed).isWithin(1e-6f).of(0.75f)
        assertThat(DeviceMemory.Info(total = 1000, free = 1000).fractionUsed).isWithin(1e-6f).of(0f)
    }

    /**
     * A device reporting free > total is nonsense, but it is nonsense
     * that would otherwise render a negative-width progress bar.
     */
    @Test
    fun an_incoherent_reply_clamps_instead_of_going_negative() {
        val info = DeviceMemory.Info(total = 100, free = 500)
        assertThat(info.used).isEqualTo(0L)
        assertThat(info.fractionUsed).isWithin(1e-6f).of(0f)
    }

    @Test
    fun a_zero_total_has_no_fraction_to_show() {
        assertThat(DeviceMemory.Info(total = 0, free = 0).fractionUsed).isNull()
    }

    // ---- Helpers ------------------------------------------------------

    private fun memoryMessage(total: Long, remain: Long): ByteArray =
        ByteArrayOutputStream().apply {
            write(0x08); writeVarint(this, total)
            write(0x10); writeVarint(this, remain)
        }.toByteArray()

    private fun factoryReply(total: Long, remain: Long): ByteArray =
        ByteArrayOutputStream().apply {
            write(0x08); writeVarint(this, 11)
            write(0x10); writeVarint(this, 5)
            val inner = memoryMessage(total, remain)
            writeTag(this, field = 5, wire = 2)
            writeVarint(this, inner.size.toLong())
            write(inner)
        }.toByteArray()

    private fun writeTag(out: ByteArrayOutputStream, field: Int, wire: Int) =
        writeVarint(out, ((field shl 3) or wire).toLong())

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7F.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }
}
