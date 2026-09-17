package de.syntaxfehler.ligpsport.strava

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * By the time [StravaUploader.awaitProcessing] runs, Strava holds the
 * file. Reporting a failure from here makes the user retry, and the
 * retry is rejected as a duplicate — so only a real rejection may
 * surface as one.
 */
@RunWith(RobolectricTestRunner::class)
class StravaPollTest {

    private val ticket = StravaClient.UploadStatus(id = 42L, status = "Your activity is still being processed.")

    private fun clientAnswering(
        vararg answers: Pair<HttpStatusCode, String>,
        latencyMs: Long = 0,
    ): StravaClient {
        var i = 0
        val engine = MockEngine {
            delay(latencyMs)
            val (code, body) = answers[minOf(i++, answers.lastIndex)]
            respond(body, code, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return StravaClient("123", "sekret", HttpClient(engine))
    }

    private fun poll(client: StravaClient, timeoutMs: Long = 5_000) = runBlocking {
        StravaUploader.awaitProcessing(client, "access", ticket, "ride.fit", timeoutMs = timeoutMs, intervalMs = 1)
    }

    @Test
    fun a_failed_poll_is_retried() {
        val client = clientAnswering(
            HttpStatusCode.TooManyRequests to """{"message":"Rate Limit Exceeded"}""",
            HttpStatusCode.OK to """{"id":42,"activity_id":7}""",
        )

        assertThat(poll(client)).isEqualTo(StravaUploader.Result.Success(7L, "ride.fit"))
    }

    @Test
    fun a_rejection_is_a_failure() {
        val client = clientAnswering(
            HttpStatusCode.OK to """{"id":42,"error":"duplicate of activity 5"}""",
        )

        assertThat(poll(client)).isEqualTo(StravaUploader.Result.Failure("duplicate of activity 5"))
    }

    /** The answer to the poll that straddles the deadline still counts. */
    @Test
    fun the_last_poll_is_not_thrown_away() {
        val client = clientAnswering(
            HttpStatusCode.OK to """{"id":42,"error":"corrupt file"}""",
            latencyMs = 300,
        )

        assertThat(poll(client, timeoutMs = 100)).isEqualTo(StravaUploader.Result.Failure("corrupt file"))
    }

    @Test
    fun polls_that_keep_failing_end_as_still_processing() {
        val client = clientAnswering(HttpStatusCode.ServiceUnavailable to "")

        assertThat(poll(client, timeoutMs = 50)).isEqualTo(StravaUploader.Result.Success(null, "ride.fit"))
    }
}
