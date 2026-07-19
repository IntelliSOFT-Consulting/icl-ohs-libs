package ke.go.moh.icl.notification.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendGridEmailProviderTest {
    @Test
    fun `returns success with provider message id on 202`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("Bearer test-key", request.headers["Authorization"])
            respond(
                content = "",
                status = HttpStatusCode.Accepted,
                headers = headersOf("X-Message-Id", listOf("sg-123")),
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val provider = SendGridEmailProvider(client, "test-key", "noreply@icl.go.ke", "ICL OHS")

        val result = provider.send(listOf("nurse@facility.go.ke"), "Reminder", "<p>Hi</p>")

        assertTrue(result.success)
        assertEquals("sg-123", result.providerMessageId)
    }

    @Test
    fun `returns failure on non-2xx response`() = runTest {
        val engine = MockEngine {
            respond(
                content = "{\"errors\":[{\"message\":\"bad request\"}]}",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val provider = SendGridEmailProvider(client, "test-key", "noreply@icl.go.ke", "ICL OHS")

        val result = provider.send(listOf("nurse@facility.go.ke"), "Reminder", "<p>Hi</p>")

        assertEquals(false, result.success)
        assertTrue(result.error?.contains("400") == true)
    }
}
