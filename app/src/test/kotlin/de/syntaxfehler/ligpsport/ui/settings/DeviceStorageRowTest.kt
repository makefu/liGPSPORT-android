package de.syntaxfehler.ligpsport.ui.settings

import com.google.common.truth.Truth.assertThat
import de.syntaxfehler.ligpsport.ble.DeviceMemory
import org.junit.Test

/**
 * Formatting for the storage line under the routes / activities lists.
 * Locale-sensitive on a phone set to a comma-decimal locale, hence the
 * explicit `Locale.US` in [formatBytes] — asserted here so a future
 * `String.format` without it fails on a CI machine set to en-US too.
 */
class DeviceStorageRowTest {

    @Test
    fun bytes_below_a_kilobyte_are_shown_raw() {
        assertThat(formatBytes(0)).isEqualTo("0 B")
        assertThat(formatBytes(999)).isEqualTo("999 B")
    }

    /** Decimal units, matching how storage is advertised and how
     *  Android's own settings report it. */
    @Test
    fun units_step_at_a_thousand_not_1024() {
        assertThat(formatBytes(1_000)).isEqualTo("1.0 kB")
        assertThat(formatBytes(1_000_000)).isEqualTo("1.0 MB")
        assertThat(formatBytes(1_000_000_000)).isEqualTo("1.0 GB")
    }

    /** One decimal below 10 of a unit, none above: 1.9 GB, 412 MB. */
    @Test
    fun precision_drops_once_the_number_gets_large() {
        assertThat(formatBytes(1_900_000_000)).isEqualTo("1.9 GB")
        assertThat(formatBytes(412_000_000)).isEqualTo("412 MB")
        assertThat(formatBytes(9_900_000)).isEqualTo("9.9 MB")
        assertThat(formatBytes(10_000_000)).isEqualTo("10 MB")
    }

    @Test
    fun the_line_leads_with_free_space() {
        val info = DeviceMemory.Info(total = 1_900_000_000, free = 412_000_000)
        assertThat(formatStorage(info)).isEqualTo("412 MB free of 1.9 GB")
    }

    /**
     * A zero total is schema-legal and is what an unimplemented
     * operation might return — print what we have rather than "of 0 B".
     */
    @Test
    fun a_zero_total_degrades_to_the_free_figure_alone() {
        assertThat(formatStorage(DeviceMemory.Info(total = 0, free = 1_500_000)))
            .isEqualTo("1.5 MB free")
    }
}
