package de.syntaxfehler.ligpsport.ble

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Uploading and starting navigation are two steps, and whether the
 * second ran says nothing about whether the first worked.
 *
 * Conflating them shipped: skipping navigation for a GPX import sent a
 * perfectly good upload down the failure branch, where it was reported
 * as "Upload failed: uploaded" — the success message, behind the word
 * failed.
 */
@RunWith(RobolectricTestRunner::class)
class UploadNavigationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val mac = "AA:BB:CC:DD:EE:FF"

    /** Acks whatever it is sent, and records which services were asked. */
    private class AckAllTransport : ManagedTransport {
        val servicesSent = mutableListOf<Int>()
        private val received = KChannel<ReceivedFrame>(KChannel.UNLIMITED)

        override suspend fun open() = Unit
        override fun isConnected() = true
        override fun frames(): Flow<ReceivedFrame> = received.receiveAsFlow()
        override fun drain() = Unit
        override suspend fun close() = Unit

        override suspend fun send(frame: ByteArray, channel: Channel) {
            // A chunked upload arrives as head+payload rather than a
            // parseable frame, so fall back to the head's service byte.
            val service = runCatching { parseFrame(frame).service }.getOrNull()
                ?: frame.getOrNull(1)?.toInt()?.and(0xFF)
                ?: return
            servicesSent += service
            received.trySend(
                ReceivedFrame(
                    channel,
                    buildFrame(Frame(service = service, type = TYPE_CONFIRM, status = 0)),
                ),
            )
        }
    }

    private lateinit var transport: AckAllTransport

    @Before
    fun setUp() {
        transport = AckAllTransport()
        DeviceStore(context).save(name = "BSC200", address = mac)
        BleSessionManager.connector = { _, _ -> transport }
    }

    @After
    fun tearDown() = runTest {
        // BleSessionManager is an object, so its per-MAC session cache
        // outlives this test class and would hand a stale fake to
        // whatever runs next in the same JVM.
        BleSessionManager.forgetAll()
        BleSessionManager.connector = null
    }

    private val gpx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test"><trk><name>Sunday Loop</name><trkseg>
        <trkpt lat="52.5200" lon="13.4050"/>
        <trkpt lat="52.5210" lon="13.4060"/>
        <trkpt lat="52.5220" lon="13.4070"/>
        </trkseg></trk></gpx>
    """.trimIndent().toByteArray()

    @Test
    fun an_upload_that_skips_navigation_still_succeeds() = runTest {
        val result = UploadPipeline.uploadGpx(
            context = context,
            gpxBytes = gpx,
            fileName = "sunday",
            targetMac = mac,
            startNavigation = false,
        )

        assertThat(result).isInstanceOf(UploadPipeline.Result.Success::class.java)
        // null, not false: navigation wasn't attempted, which is a
        // different thing from the device having refused it.
        assertThat((result as UploadPipeline.Result.Success).navStarted).isNull()
        assertThat(transport.servicesSent).doesNotContain(SERVICE_ROUTE_PLAN)
    }

    @Test
    fun an_upload_that_starts_navigation_reports_that_it_did() = runTest {
        val result = UploadPipeline.uploadGpx(
            context = context,
            gpxBytes = gpx,
            fileName = "sunday",
            targetMac = mac,
            startNavigation = true,
        )

        assertThat(result).isInstanceOf(UploadPipeline.Result.Success::class.java)
        assertThat((result as UploadPipeline.Result.Success).navStarted).isTrue()
        assertThat(transport.servicesSent).contains(SERVICE_ROUTE_PLAN)
    }

    private companion object {
        const val SERVICE_ROUTE_PLAN = 7
    }
}
