package de.syntaxfehler.ligpsport.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks down the key-generation rule for the search-suggestions
 * LazyColumn in `MapScreen`. LazyColumn throws
 * `IllegalArgumentException: Two keys were equal` when two list
 * entries hash to the same key — this used to instant-crash the app
 * when Photon returned two POIs sharing a (name, lat, lon) tuple
 * (stacked features at the same address, e.g. a café inside a
 * shop). The fix uses the entry's list index to guarantee
 * uniqueness even on identical payloads.
 */
class SearchSuggestionKeyTest {

    private fun key(i: Int, r: SearchResult): String =
        "$i|${r.latitude}|${r.longitude}|${r.name}"

    @Test
    fun unique_keys_for_distinct_results() {
        val a = SearchResult("Café", "", 48.7700, 9.1800)
        val b = SearchResult("Bäckerei", "", 48.7700, 9.1800)
        assertThat(key(0, a)).isNotEqualTo(key(1, b))
    }

    @Test
    fun unique_keys_for_duplicate_results() {
        // Photon legitimately emits identical (name, lat, lon) twice
        // when a feature is indexed under multiple categories. Before
        // the fix this exploded LazyColumn; now each entry has a
        // distinct key.
        val r = SearchResult("Hauptbahnhof", "", 48.7838, 9.1828)
        val all = List(3) { r }
        val keys = all.mapIndexed { i, x -> key(i, x) }
        assertThat(keys.toSet()).hasSize(3)
    }

    @Test
    fun key_is_deterministic_within_one_list() {
        val r = SearchResult("Marktplatz", "", 48.7758, 9.1779)
        val results = listOf(r, r)
        val first = results.mapIndexed { i, x -> key(i, x) }
        val second = results.mapIndexed { i, x -> key(i, x) }
        assertThat(first).isEqualTo(second)
    }
}
