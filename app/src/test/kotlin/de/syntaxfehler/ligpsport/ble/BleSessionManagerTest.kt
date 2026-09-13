package de.syntaxfehler.ligpsport.ble

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression cover for the bug where a route upload and the 15-second
 * nav-status poll each opened their own GATT connection to the same
 * computer, so the poll's disconnect tore down the upload mid-transfer
 * and the user had to unpair/re-pair every device to recover.
 */
@RunWith(RobolectricTestRunner::class)
class BleSessionManagerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Records how many leases are live at once and how often it was
     * asked to connect, so the tests can assert on both.
     */
    private class FakeTransport(
        val stats: Stats,
        /** Reports itself dead from the [dieAfter]-th lease onwards. */
        val dieAfter: Int = Int.MAX_VALUE,
        val failOpens: Int = 0,
    ) : ManagedTransport {
        class Stats {
            val connects = AtomicInteger(0)
            val closes = AtomicInteger(0)
            val liveLeases = AtomicInteger(0)
            val maxConcurrentLeases = AtomicInteger(0)
            val leases = AtomicInteger(0)
        }

        private var open = false

        override suspend fun open() {
            stats.connects.incrementAndGet()
            if (stats.connects.get() <= failOpens) {
                throw IllegalStateException("BLE connect failed (status=133)")
            }
            open = true
        }

        override suspend fun send(frame: ByteArray, channel: Channel) = Unit
        override fun frames(): Flow<ReceivedFrame> = emptyFlow()

        /** Nothing is ever queued on this fake, so there is nothing to drop. */
        override fun drain() = Unit
        override suspend fun close() {
            open = false
            stats.closes.incrementAndGet()
        }

        override fun isConnected(): Boolean = open && stats.leases.get() < dieAfter
    }

    private lateinit var stats: FakeTransport.Stats

    @Before
    fun setUp() {
        stats = FakeTransport.Stats()
        BleSessionManager.connector = { _, _ -> FakeTransport(stats) }
    }

    @After
    fun tearDown() = runTest {
        BleSessionManager.forgetAll()
        BleSessionManager.connector = null
    }

    /** Enter a lease, record concurrency, hold briefly, leave. */
    private suspend fun lease(mac: String, holdMs: Long = 20) =
        BleSessionManager.withSession(context, mac) {
            stats.leases.incrementAndGet()
            val live = stats.liveLeases.incrementAndGet()
            stats.maxConcurrentLeases.updateAndGet { maxOf(it, live) }
            try {
                delay(holdMs)
            } finally {
                stats.liveLeases.decrementAndGet()
            }
        }

    @Test
    fun leases_on_the_same_device_never_overlap() = runTest {
        coroutineScope {
            List(4) { async { lease("AA:BB:CC:DD:EE:FF") } }.awaitAll()
        }
        // The whole point: one operation at a time per computer.
        assertThat(stats.maxConcurrentLeases.get()).isEqualTo(1)
        // …over a single shared connection, not one per operation.
        assertThat(stats.connects.get()).isEqualTo(1)
    }

    @Test
    fun leases_on_different_devices_run_in_parallel() = runTest {
        val bothInside = CompletableDeferred<Unit>()
        val inside = AtomicInteger(0)
        coroutineScope {
            listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02").map { mac ->
                async {
                    BleSessionManager.withSession(context, mac) {
                        if (inside.incrementAndGet() == 2) bothInside.complete(Unit)
                        // Fails the test by timing out if the manager
                        // serialised across devices — the fan-out upload
                        // relies on this staying parallel.
                        withTimeout(5_000) { bothInside.await() }
                    }
                }
            }.awaitAll()
        }
        assertThat(inside.get()).isEqualTo(2)
        assertThat(stats.connects.get()).isEqualTo(2)
    }

    @Test
    fun a_link_the_stack_dropped_while_idle_is_rebuilt() = runTest {
        BleSessionManager.connector = { _, _ -> FakeTransport(stats, dieAfter = 1) }
        lease("AA:BB:CC:DD:EE:FF")
        // First lease left the transport reporting isConnected()==false,
        // which is what an app resuming after a long idle period sees.
        lease("AA:BB:CC:DD:EE:FF")
        assertThat(stats.connects.get()).isEqualTo(2)
        assertThat(stats.closes.get()).isAtLeast(1)
    }

    @Test
    fun a_transient_connect_failure_is_retried() = runTest {
        BleSessionManager.connector = { _, _ -> FakeTransport(stats, failOpens = 2) }
        lease("AA:BB:CC:DD:EE:FF")
        // Two rejections, third attempt sticks — status=133 on the first
        // connect is routine on Android.
        assertThat(stats.connects.get()).isEqualTo(3)
        assertThat(stats.leases.get()).isEqualTo(1)
    }

    @Test
    fun connect_gives_up_after_the_last_attempt() = runTest {
        BleSessionManager.connector = { _, _ -> FakeTransport(stats, failOpens = 99) }
        var thrown: Exception? = null
        try {
            lease("AA:BB:CC:DD:EE:FF")
        } catch (e: Exception) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
        assertThat(stats.connects.get()).isEqualTo(3)
        assertThat(stats.leases.get()).isEqualTo(0)
    }

    @Test
    fun forget_closes_the_cached_connection() = runTest {
        lease("AA:BB:CC:DD:EE:FF")
        assertThat(stats.closes.get()).isEqualTo(0)
        BleSessionManager.forget("aa:bb:cc:dd:ee:ff")
        assertThat(stats.closes.get()).isEqualTo(1)
        lease("AA:BB:CC:DD:EE:FF")
        assertThat(stats.connects.get()).isEqualTo(2)
    }
}
