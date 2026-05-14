package de.syntaxfehler.ligpsport

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import de.syntaxfehler.ligpsport.ble.Channel
import de.syntaxfehler.ligpsport.ble.Frame
import de.syntaxfehler.ligpsport.ble.LoopbackTransport
import de.syntaxfehler.ligpsport.ble.buildFrame
import de.syntaxfehler.ligpsport.ble.parseFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hermetic end-to-end protocol test: send a FILE_OPERATION ADD frame
 * via [LoopbackTransport], the [FakeIgpsportSimulator] replies with a
 * confirm-status=0, and we assert the round-trip.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndUploadTest {
    @Test
    fun fake_simulator_acks_upload_request() = runBlocking {
        val transport = LoopbackTransport()
        val scope = CoroutineScope(Dispatchers.Default)
        val sim = FakeIgpsportSimulator(transport, scope)
        sim.start()
        try {
            transport.open()
            val request = buildFrame(
                Frame(service = 21, operation = 3, payload = "hello".toByteArray()),
            )
            transport.send(request, Channel.CONTROL)
            val received = transport.frames().first()
            val parsed = parseFrame(received.bytes)
            assertThat(parsed.service).isEqualTo(21)
            assertThat(parsed.operation).isEqualTo(3)
            assertThat(parsed.status).isEqualTo(0)
        } finally {
            sim.stop()
            transport.close()
        }
    }
}
