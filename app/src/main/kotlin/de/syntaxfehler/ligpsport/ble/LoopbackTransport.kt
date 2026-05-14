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
    val outgoing = KChannel<ReceivedFrame>(capacity = KChannel.UNLIMITED)

    override suspend fun open() {}

    override suspend fun send(frame: ByteArray, channel: Channel) {
        outgoing.send(ReceivedFrame(channel, frame))
    }

    override fun frames(): Flow<ReceivedFrame> = received.receiveAsFlow()

    suspend fun deliver(channel: Channel, bytes: ByteArray) {
        received.send(ReceivedFrame(channel, bytes))
    }

    override suspend fun close() {
        received.close()
        outgoing.close()
    }
}
