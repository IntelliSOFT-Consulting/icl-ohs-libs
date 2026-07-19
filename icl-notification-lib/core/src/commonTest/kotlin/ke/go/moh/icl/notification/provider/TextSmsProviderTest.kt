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
import kotlin.test.assertTrue

class TextSmsProviderTest {
    @Test
    fun `parses success response`() = runTest {
        val engine = MockEngine {
            respond(
                content = """
                    {"responses":[
                        {"respose-code":200,"response-description":"Success","mobileno":"254712345678","messageid":"txt-1"}
                    ]}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val provider = TextSmsProvider(client, "test-key", "partner-1", "ICLOHS")

        val result = provider.send(listOf("254712345678"), "Hello")

        assertTrue(result.success)
        assertTrue(result.recipientResults?.first()?.messageId == "txt-1")
    }
}
