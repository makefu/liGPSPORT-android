package de.syntaxfehler.ligpsport.ble

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test

/**
 * `frames()` is one destructively-consumed, unbounded queue shared by
 * every operation. A reply that turns up after its requester gave up
 * waiting used to sit there until the next operation collected it and
 * took it for its own answer — which is exactly how "no activities,
 * then instantly all of them on refresh" happened.
 */
class TransportDrainTest {

    private fun frameFor(service: Int): ByteArray =
        buildFrame(Frame(service = service, operation = 1, payload = byteArrayOf(1, 2, 3)))

    @Test
    fun a_late_reply_is_still_readable_when_nothing_drains() = runTest {
        val t = LoopbackTransport()
        t.deliver(Channel.CONTROL, frameFor(service = 7))

        val got = withTimeoutOrNull(1_000) { t.frames().first() }

        // The buffering itself is fine and deliberate — it is reading a
        // *stale* frame as the answer to a new request that is not.
        assertThat(got).isNotNull()
    }

    @Test
    fun drain_discards_what_arrived_before_the_request() = runTest {
        val t = LoopbackTransport()
        t.deliver(Channel.CONTROL, frameFor(service = 7))
        t.deliver(Channel.CONTROL, frameFor(service = 6))

        t.drain()

        assertThat(withTimeoutOrNull(200) { t.frames().first() }).isNull()
    }

    @Test
    fun drain_leaves_frames_that_arrive_afterwards_alone() = runTest {
        val t = LoopbackTransport()
        t.deliver(Channel.CONTROL, frameFor(service = 7))

        t.drain()
        t.deliver(Channel.CONTROL, frameFor(service = 6))

        val got = withTimeoutOrNull(1_000) { t.frames().first() }
        assertThat(got).isNotNull()
        assertThat(parseFrame(got!!.bytes).service).isEqualTo(6)
    }

    @Test
    fun draining_an_empty_queue_is_a_no_op() = runTest {
        val t = LoopbackTransport()
        t.drain()
        t.drain()
        assertThat(withTimeoutOrNull(200) { t.frames().first() }).isNull()
    }

    /**
     * The reassembly buffer is per channel and persists, so a reply
     * that stopped half-way would otherwise prefix the next reply on
     * that channel with its leftovers and corrupt it.
     */
    @Test
    fun a_half_delivered_frame_does_not_corrupt_the_next_one() = runTest {
        val t = LoopbackTransport()
        val whole = frameFor(service = 6)

        // Half a frame, then a drain, then a whole one.
        t.deliverPartial(Channel.THIRD, whole.copyOfRange(0, whole.size / 2))
        t.drain()
        t.deliver(Channel.THIRD, whole)

        val got = withTimeoutOrNull(1_000) { t.frames().first() }
        assertThat(got?.bytes).isEqualTo(whole)
    }
}
