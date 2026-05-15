package de.syntaxfehler.ligpsport

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import de.syntaxfehler.ligpsport.ble.FileTransfer
import de.syntaxfehler.ligpsport.ble.LoopbackTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hermetic round-trip of the four CYCLING_DATA ops against the
 * extended [FakeIgpsportSimulator]. Exercises the wire codec end-
 * to-end without touching a real radio.
 */
@RunWith(AndroidJUnit4::class)
class ActivitiesRoundTripTest {

    @Test
    fun list_then_download_then_delete_then_delete_all() = runBlocking {
        val transport = LoopbackTransport()
        val scope = CoroutineScope(Dispatchers.Default)
        val sim = FakeIgpsportSimulator(transport, scope)
        // Seed two activities. Use small but distinguishable contents.
        val ts1 = 1_700_000_000L
        val ts2 = 1_700_001_000L
        val fit1 = ByteArray(64) { (it and 0xFF).toByte() }
        val fit2 = ByteArray(96) { ((it * 3) and 0xFF).toByte() }
        sim.activities.add(FileTransfer.ActivityListEntry(ts1, fit1.size.toLong(), "u1", "d1"))
        sim.activities.add(FileTransfer.ActivityListEntry(ts2, fit2.size.toLong(), "u1", "d1"))
        sim.activityContent[ts1] = fit1
        sim.activityContent[ts2] = fit2
        sim.start()
        try {
            transport.open()

            val list = FileTransfer.listActivities(transport)
            assertThat(list.map { it.timestamp }).containsExactly(ts1, ts2).inOrder()
            assertThat(list[0].fileSize).isEqualTo(fit1.size.toLong())

            val dl = FileTransfer.downloadActivity(transport, ts1)
            assertThat(dl.content).isEqualTo(fit1)
            assertThat(dl.fileSize).isEqualTo(fit1.size.toLong())
            assertThat(dl.fileId).isEqualTo(ts1)
            assertThat(dl.fileName).isEqualTo("$ts1.fit")

            val delStatus = FileTransfer.deleteActivity(transport, ts1)
            assertThat(delStatus).isEqualTo(0)
            val afterDel = FileTransfer.listActivities(transport)
            assertThat(afterDel.map { it.timestamp }).containsExactly(ts2)

            val allDelStatus = FileTransfer.deleteAllActivities(transport)
            assertThat(allDelStatus).isEqualTo(0)
            val afterAll = FileTransfer.listActivities(transport)
            assertThat(afterAll).isEmpty()
        } finally {
            sim.stop()
            transport.close()
        }
    }
}
