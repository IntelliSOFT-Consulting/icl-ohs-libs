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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AfricasTalkingSmsProviderTest {
    @Test
    fun `uses sandbox host and parses per-recipient success`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://api.sandbox.africastalking.com/version1/messaging", request.url.toString())
            respond(
                content = """
                    {"SMSMessageData":{"Message":"Sent","Recipients":[
                        {"statusCode":101,"number":"+254712345678","status":"Success","cost":"KES 0.8","messageId":"at-1"}
                    ]}}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val provider = AfricasTalkingSmsProvider(client, "test-key", "sandbox", senderId = "ICLOHS")

        val result = provider.send(listOf("+254712345678"), "Your OTP is 1234")

        assertTrue(result.success)
        assertEquals(1, result.recipientResults?.size)
        assertEquals("at-1", result.recipientResults?.first()?.messageId)
    }

    @Test
    fun `marks recipient failed for non-success status codes`() = runTest {
        val engine = MockEngine {
            respond(
                content = """
                    {"SMSMessageData":{"Message":"Sent","Recipients":[
                        {"statusCode":406,"number":"+254700000000","status":"UserInBlacklist"}
                    ]}}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val provider = AfricasTalkingSmsProvider(client, "test-key", "sandbox")

        val result = provider.send(listOf("+254700000000"), "Hello")

        assertFalse(result.success)
        assertFalse(result.recipientResults!!.first().success)
    }
}
