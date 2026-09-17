package de.syntaxfehler.ligpsport.strava

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Strava rotates the refresh token on every grant and rejects the one
 * it replaced. Two uploads refreshing at once from the same stored
 * token used to mean one of them failed and, depending on which
 * response was saved last, the stored token could be the dead one.
 */
@RunWith(RobolectricTestRunner::class)
class StravaTokenRefreshTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A token endpoint that only honours the newest refresh token. */
    private class RotatingServer {
        val grants = AtomicInteger(0)

        @Volatile
        var valid = "refresh-0"

        val engine = MockEngine { request ->
            val presented = (request.body as FormDataContent).formData["refresh_token"]
            // Long enough that two unserialised callers both get here
            // before either has rotated anything.
            delay(200)
            synchronized(this@RotatingServer) {
                if (presented != valid) {
                    return@MockEngine respond("""{"message":"Bad Request"}""", HttpStatusCode.BadRequest)
                }
                val n = grants.incrementAndGet()
                valid = "refresh-$n"
                respond(
                    """{"access_token":"access-$n","refresh_token":"refresh-$n","expires_at":${Long.MAX_VALUE / 2}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }

        fun client() = StravaClient("123", "sekret", HttpClient(engine))
    }

    private fun store() = StravaStore(context).also {
        it.clear()
        it.saveCredentials("123", "sekret", "refresh-0")
    }

    @Test
    fun concurrent_refreshes_share_one_grant() = runBlocking {
        val server = RotatingServer()
        val store = store()

        val results = List(2) {
            async(Dispatchers.Default) { StravaUploader.ensureFreshToken(store, server.client()) }
        }.awaitAll()

        assertThat(server.grants.get()).isEqualTo(1)
        assertThat(results.map { it.accessToken }).containsExactly("access-1", "access-1")
        assertThat(store.refreshToken()).isEqualTo(server.valid)
    }

    /**
     * An expired cache must refresh with the token in the store now,
     * not the one an upload read before its BLE fetch.
     */
    @Test
    fun an_expired_token_refreshes_with_the_latest_stored_refresh_token() = runBlocking {
        val server = RotatingServer()
        val store = store()
        StravaUploader.ensureFreshToken(store, server.client())

        val later = StravaUploader.ensureFreshToken(
            store,
            server.client(),
            nowEpochSeconds = { Long.MAX_VALUE / 2 },
        )

        assertThat(server.grants.get()).isEqualTo(2)
        assertThat(later.accessToken).isEqualTo("access-2")
        assertThat(store.refreshToken()).isEqualTo("refresh-2")
    }
}
