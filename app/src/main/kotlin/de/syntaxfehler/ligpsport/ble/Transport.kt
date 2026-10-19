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

    /**
     * Discard frames that arrived before now.
     *
     * [frames] is one destructively-consumed queue shared by every
     * operation, and it buffers without bound — a reply that arrives
     * after its requester gave up waiting simply sits there until the
     * *next* operation collects it and mistakes it for its own answer.
     * Call this immediately before sending a request so that what comes
     * back can only be a response to it.
     *
     * Also discards any half-reassembled frame. A reply that stopped
     * part-way leaves bytes stranded in the per-channel buffer, and the
     * next reply on that channel is appended to them — so the stale
     * fragment doesn't just waste space, it corrupts the following
     * frame. Dropping a partial frame is always right here: whoever was
     * waiting for it has already given up.
     *
     * Safe because [BleSessionManager] leases a device exclusively:
     * there is never a second operation mid-exchange whose reply this
     * could throw away.
     */
    fun drain()

    /**
     * Diagnostic: bytes sitting in each channel's reassembly buffer,
     * and how many the pending frame is waiting for.
     *
     * Exists because the reassembly path fails *silently* — a frame
     * that never completes simply never arrives, which is
     * indistinguishable from a device that never answered. This tells
     * the two apart in a timeout log.
     */
    fun pendingRx(): Map<Channel, Pair<Int, Int?>> = emptyMap()

    /** Close the connection and release resources. */
    suspend fun close()
}

/**
 * A [Transport] whose liveness can be interrogated. [BleSessionManager]
 * caches one of these per device and needs to tell a still-usable link
 * from one the stack dropped while the app sat idle.
 */
interface ManagedTransport : Transport {
    fun isConnected(): Boolean
}

data class ReceivedFrame(val channel: Channel, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReceivedFrame) return false
        return channel == other.channel && bytes.contentEquals(other.bytes)
    }
    override fun hashCode(): Int = 31 * channel.hashCode() + bytes.contentHashCode()
}
