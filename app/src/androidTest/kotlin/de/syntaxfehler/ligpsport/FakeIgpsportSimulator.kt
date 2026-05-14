package de.syntaxfehler.ligpsport

import de.syntaxfehler.ligpsport.ble.Channel
import de.syntaxfehler.ligpsport.ble.Frame
import de.syntaxfehler.ligpsport.ble.LoopbackTransport
import de.syntaxfehler.ligpsport.ble.TYPE_CONFIRM
import de.syntaxfehler.ligpsport.ble.buildFrame
import de.syntaxfehler.ligpsport.ble.parseFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Kotlin port of ligpsport/simulator.py — a stand-in iGPSPORT device
 * that responds to FILE_OPERATION ADD frames with a confirm-status=0.
 *
 * Pairs with [LoopbackTransport]: read frames from its `outgoing`
 * channel, write replies via `deliver`.
 */
class FakeIgpsportSimulator(
    private val transport: LoopbackTransport,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            for (out in transport.outgoing) {
                val frame = try {
                    parseFrame(out.bytes)
                } catch (_: Exception) {
                    continue
                }
                val ack = Frame(
                    type = TYPE_CONFIRM,
                    service = frame.service,
                    operation = frame.operation,
                    subService = frame.subService,
                    subOperation = frame.subOperation,
                    status = 0,
                )
                transport.deliver(Channel.CONTROL, buildFrame(ack))
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
