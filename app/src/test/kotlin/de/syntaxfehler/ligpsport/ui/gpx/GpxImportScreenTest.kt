package de.syntaxfehler.ligpsport.ui.gpx

import com.google.common.truth.Truth.assertThat
import de.syntaxfehler.ligpsport.ble.UploadPipeline
import org.junit.Test

/**
 * The two decisions the Upload GPX screen makes that aren't obvious
 * from reading the UI: what the route gets called on the device, and
 * how a fan-out to several devices is reported.
 */
class GpxImportScreenTest {

    // ---- uploadNameFor ------------------------------------------------

    @Test
    fun the_file_name_wins_over_the_gpx_name() {
        // The user named the file; "Track" is what an exporter chose.
        assertThat(uploadNameFor("morning_ride.gpx", "Track")).isEqualTo("morning_ride")
    }

    @Test
    fun only_the_last_extension_is_dropped() {
        assertThat(uploadNameFor("ride.2026.gpx", null)).isEqualTo("ride.2026")
    }

    @Test
    fun falls_back_to_the_gpx_name_when_there_is_no_file_name() {
        assertThat(uploadNameFor(null, "Sunday Loop")).isEqualTo("Sunday_Loop")
    }

    /**
     * `sanitiseFileName` strips non-ASCII outright, so a name made
     * entirely of it sanitises to nothing — the next candidate has to
     * take over rather than the device receiving an empty name.
     */
    @Test
    fun an_unsanitisable_file_name_falls_through_to_the_gpx_name() {
        assertThat(uploadNameFor("日本語.gpx", "Kyoto ride")).isEqualTo("Kyoto_ride")
    }

    @Test
    fun both_candidates_unusable_yields_the_constant_floor() {
        assertThat(uploadNameFor("🚴.gpx", "🚵")).isEqualTo("imported_route")
        assertThat(uploadNameFor(null, null)).isEqualTo("imported_route")
    }

    // ---- summarise ----------------------------------------------------

    private fun ok() = UploadPipeline.Result.Success()

    private fun fail(reason: String) = UploadPipeline.Result.Failure(reason)

    @Test
    fun no_paired_device_says_so() {
        assertThat(summarise(emptyMap())).isEqualTo("No paired device.")
    }

    @Test
    fun a_single_device_is_not_reported_as_a_tally() {
        assertThat(summarise(mapOf("AA" to ok()))).isEqualTo("Uploaded to 1 device.")
    }

    @Test
    fun every_device_acking_reads_as_plain_success() {
        assertThat(summarise(mapOf("AA" to ok(), "BB" to ok())))
            .isEqualTo("Uploaded to 2 devices.")
    }

    /**
     * The usual reason one device didn't ack is that it is switched
     * off. That's worth stating but not worth calling a failure.
     */
    @Test
    fun a_partial_result_is_a_success_with_a_tally() {
        assertThat(summarise(mapOf("AA" to ok(), "BB" to fail("not connected"))))
            .isEqualTo("Uploaded to 1 of 2 devices.")
    }

    @Test
    fun only_a_total_failure_reports_a_reason() {
        assertThat(summarise(mapOf("AA" to fail("no paired device"))))
            .isEqualTo("Upload failed: no paired device")
    }
}
