package de.syntaxfehler.ligpsport.ui.upload

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileNameSanitiserTest {

    @Test
    fun blank_and_null_return_null() {
        assertThat(sanitiseFileName(null)).isNull()
        assertThat(sanitiseFileName("")).isNull()
        assertThat(sanitiseFileName("   ")).isNull()
    }

    @Test
    fun ascii_pois_keep_their_spaces() {
        // Underscores were our own invention — the firmware displays
        // spaces fine, as Python-uploaded routes demonstrate.
        assertThat(sanitiseFileName("Hauptbahnhof Berlin"))
            .isEqualTo("Hauptbahnhof Berlin")
    }

    @Test
    fun umlauts_and_punctuation_are_dropped_not_kept() {
        // "ä/ö/ü/ß" don't map to ASCII letters here — the firmware is
        // unforgiving so we drop them rather than guess at a romanisation.
        assertThat(sanitiseFileName("Hauptstraße 12, Köln"))
            .isEqualTo("Hauptstrae 12 Kln")
    }

    @Test
    fun street_address_format_is_preserved() {
        assertThat(sanitiseFileName("Friedrichstraße 95"))
            .isEqualTo("Friedrichstrae 95")
    }

    @Test
    fun length_is_capped_to_32_chars() {
        val long = "A".repeat(100)
        assertThat(sanitiseFileName(long)).hasLength(32)
    }

    @Test
    fun emoji_and_other_unicode_are_dropped() {
        assertThat(sanitiseFileName("Park 🌳"))
            .isEqualTo("Park")
    }

    @Test
    fun leading_trailing_separators_are_trimmed() {
        assertThat(sanitiseFileName("...weird..."))
            .isEqualTo("weird")
    }

    /**
     * Dropping a non-ASCII character mustn't leave a double space
     * behind, and a name that lands exactly on the 32-char cap mustn't
     * end mid-space.
     */
    @Test
    fun filtering_does_not_leave_ragged_whitespace() {
        assertThat(sanitiseFileName("Köln Süd")).isEqualTo("Kln Sd")
        assertThat(sanitiseFileName("A ß B")).isEqualTo("A B")
        val capped = sanitiseFileName("abcdefghijklmnopqrstuvwxyzabcde fghij")
        assertThat(capped).isNotNull()
        assertThat(capped!!.last()).isNotEqualTo(' ')
    }

    @Test
    fun only_unicode_is_unusable() {
        // Nothing survives sanitation → caller should fall back.
        assertThat(sanitiseFileName("🚴‍♀️")).isNull()
    }

    @Test
    fun coordinate_label_is_legible_after_sanitation() {
        // The provisional label MapScreen shows before reverse-geocoding
        // resolves — keep it usable as a fallback file name so an
        // un-resolved tap still uploads with something meaningful.
        assertThat(sanitiseFileName("52.52000, 13.40500"))
            .isEqualTo("52.52000 13.40500")
    }
}
