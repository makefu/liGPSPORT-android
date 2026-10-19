package de.syntaxfehler.ligpsport.strava

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import de.syntaxfehler.ligpsport.BuildConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Strava upload button is gated on [StravaStore.isEnabled], so the
 * rules here decide whether a button that can only fail ever reaches
 * the screen — and whether "Forget credentials" actually sticks.
 */
@RunWith(RobolectricTestRunner::class)
class StravaStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun store() = StravaStore(context).also { it.clear() }

    private fun StravaStore.fill() = saveCredentials("123", "sekret", "refresh-abc")

    @Test
    fun starts_off_with_nothing_configured() {
        val store = store()

        assertThat(store.credentials()).isNull()
        assertThat(store.isEnabled()).isFalse()
        assertThat(store.clientId()).isEmpty()
    }

    /**
     * Half-entered credentials must not count. Otherwise the button
     * appears as soon as the user starts typing a client id and every
     * tap ends in an error.
     */
    @Test
    fun partial_credentials_are_no_credentials() {
        val store = store()
        store.setEnabled(true)

        store.saveCredentials("123", "sekret", "")
        assertThat(store.credentials()).isNull()
        assertThat(store.isEnabled()).isFalse()

        store.saveCredentials("123", "", "refresh-abc")
        assertThat(store.credentials()).isNull()
        assertThat(store.isEnabled()).isFalse()
    }

    @Test
    fun enabled_needs_both_the_switch_and_a_full_set() {
        val store = store()
        store.fill()

        assertThat(store.credentials()).isNotNull()
        assertThat(store.isEnabled()).isFalse() // switch still off

        store.setEnabled(true)
        assertThat(store.isEnabled()).isTrue()

        store.setEnabled(false)
        assertThat(store.isEnabled()).isFalse()
        // …but the credentials survive the switch, so turning it back
        // on doesn't mean re-pasting all three values.
        assertThat(store.credentials()).isNotNull()
    }

    @Test
    fun values_are_trimmed_so_a_pasted_token_still_matches() {
        val store = store()
        store.saveCredentials("  123 ", " sekret ", " refresh-abc\n")

        assertThat(store.clientId()).isEqualTo("123")
        assertThat(store.credentials()?.refreshToken).isEqualTo("refresh-abc")
    }

    /**
     * A cached access token belongs to whatever credentials minted it —
     * keeping it across a credential change would upload the next ride
     * to the previous account.
     */
    @Test
    fun changing_credentials_drops_the_cached_token() {
        val store = store()
        store.fill()
        store.saveTokens(
            StravaStore.Tokens(
                accessToken = "at",
                refreshToken = "refresh-abc",
                expiresAtEpochSeconds = 9_999_999_999L,
                athleteName = "Ada",
            ),
        )
        assertThat(store.cachedTokens()).isNotNull()

        store.saveCredentials("999", "other", "refresh-xyz")

        assertThat(store.cachedTokens()).isNull()
        assertThat(store.athleteName()).isNull()
    }

    /**
     * Strava rotates the refresh token on every refresh grant. Storing
     * only the access token would strand the account six hours later,
     * with the pasted token no longer accepted.
     */
    @Test
    fun a_refreshed_token_replaces_the_stored_refresh_token() {
        val store = store()
        store.fill()

        store.saveTokens(
            StravaStore.Tokens(
                accessToken = "at2",
                refreshToken = "rotated",
                expiresAtEpochSeconds = 42L,
            ),
        )

        assertThat(store.credentials()?.refreshToken).isEqualTo("rotated")
        assertThat(store.refreshToken()).isEqualTo("rotated")
    }

    /**
     * The seed latch has to outlive [StravaStore.clear], or a build
     * with baked-in credentials would restore them on the next launch
     * and "Forget credentials" would look like it did nothing.
     */
    @Test
    fun forgetting_credentials_survives_a_reseed() {
        val store = store()
        store.fill()
        store.setEnabled(true)

        store.clear()
        store.seedFromBuildConfig()

        assertThat(store.credentials()).isNull()
        assertThat(store.isEnabled()).isFalse()
    }

    /**
     * Seeding is one-shot: the second call must not undo a later edit.
     * Asserted against whatever this build actually carries, so the
     * test is meaningful with or without `app/strava.properties`.
     */
    @Test
    fun seeding_only_happens_once() {
        // Not `store()`: its `clear()` sets the seed latch, and this
        // test needs a genuinely untouched install.
        context.getSharedPreferences("ligpsport.strava", Context.MODE_PRIVATE)
            .edit().clear().apply()
        val store = StravaStore(context)

        store.seedFromBuildConfig()
        val seeded = store.clientId()
        assertThat(seeded).isEqualTo(BuildConfig.STRAVA_CLIENT_ID.trim())

        store.saveCredentials("mine", "mine-secret", "mine-refresh")
        store.seedFromBuildConfig()

        assertThat(store.clientId()).isEqualTo("mine")
    }
}
