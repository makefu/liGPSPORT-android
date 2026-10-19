package de.syntaxfehler.ligpsport.ble

import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * In-memory transport. Frames written via [send] are routed to a
 * paired [FakeSimulator]-style receiver via the [outgoing] channel;
 * frames delivered via [deliver] flow out through [frames]. Used by
 * androidTest to run protocol-level tests without a BLE radio.
 */
class LoopbackTransport : Transport {
    private val received = KChannel<ReceivedFrame>(capacity = KChannel.UNLIMITED)
    private val partial = mutableMapOf<Channel, MutableList<Byte>>()
    val outgoing = KChannel<ReceivedFrame>(capacity = KChannel.UNLIMITED)

    override suspend fun open() {}

    override suspend fun send(frame: ByteArray, channel: Channel) {
        outgoing.send(ReceivedFrame(channel, frame))
    }

    override fun frames(): Flow<ReceivedFrame> = received.receiveAsFlow()

    override fun drain() {
        while (received.tryReceive().isSuccess) { /* discard */ }
        partial.clear()
    }

    override fun pendingRx(): Map<Channel, Pair<Int, Int?>> =
        partial.mapValues { (_, bytes) -> bytes.size to null }

    /**
     * Model a reply that started and stopped. [BleTransport] strands
     * such bytes in its per-channel reassembly buffer, where they
     * prefix — and corrupt — the next frame on that channel unless
     * [drain] clears them.
     */
    fun deliverPartial(channel: Channel, bytes: ByteArray) {
        partial.getOrPut(channel) { mutableListOf() }.addAll(bytes.toList())
    }

    suspend fun deliver(channel: Channel, bytes: ByteArray) {
        val stranded = partial.remove(channel)?.toByteArray() ?: ByteArray(0)
        received.send(ReceivedFrame(channel, stranded + bytes))
    }

    override suspend fun close() {
        received.close()
        outgoing.close()
    }
}
