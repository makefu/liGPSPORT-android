package de.syntaxfehler.ligpsport.ble

import kotlinx.coroutines.flow.Flow

/**
 * Transport contract — Kotlin port of ligpsport/transport.py.
 *
 * Two implementations: [BleTransport] talks to a real iGPSPORT device
 * via Nordic-ble; [LoopbackTransport] (in androidTest) feeds a
 * [FakeIgpsportSimulator] for hermetic Espresso runs.
 */
interface Transport {
    /** Open the connection. Idempotent. */
    suspend fun open()

    /** Send one 20-byte-headered frame on [channel]. */
    suspend fun send(frame: ByteArray, channel: Channel = Channel.CONTROL)

    /** Frames received from the device, multiplexed across all channels. */
    fun frames(): Flow<ReceivedFrame>

    /** Close the connection and release resources. */
    suspend fun close()
}

data class ReceivedFrame(val channel: Channel, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReceivedFrame) return false
        return channel == other.channel && bytes.contentEquals(other.bytes)
    }
    override fun hashCode(): Int = 31 * channel.hashCode() + bytes.contentHashCode()
}
