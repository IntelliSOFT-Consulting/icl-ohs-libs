package ke.go.moh.icl.notification.server

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import ke.go.moh.icl.notification.NotifyService
import ke.go.moh.icl.notification.model.NotificationResult
import ke.go.moh.icl.notification.provider.EmailProvider
import ke.go.moh.icl.notification.provider.SmsProvider
import ke.go.moh.icl.notification.template.TemplateRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeEmailProvider : EmailProvider {
    override val name = "fake-email"
    override suspend fun send(to: List<String>, subject: String, body: String) = NotificationResult(success = true)
}

private class FakeSmsProvider : SmsProvider {
    override val name = "fake-sms"
    override suspend fun send(to: List<String>, message: String) = NotificationResult(success = true)
}

private fun fakeNotifyService() = NotifyService(
    FakeEmailProvider(),
    FakeSmsProvider(),
    TemplateRegistry().apply { register("welcome", "Hello {{name}}") },
)

class NotifyRoutesTest {
    @Test
    fun `GET templates lists registered templates`() = runTest {
        testApplication {
            application { module(fakeNotifyService()) }
            val client = createClient { install(ContentNegotiation) { json() } }

            val response = client.get("/api/v1/notify/templates")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("welcome"))
        }
    }

    @Test
    fun `POST sms returns success result`() = runTest {
        testApplication {
            application { module(fakeNotifyService()) }
            val client = createClient { install(ContentNegotiation) { json() } }

            val response = client.post("/api/v1/notify/sms") {
                contentType(ContentType.Application.Json)
                setBody("""{"to":["+254700000000"],"message":"hi"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `POST email with unknown template returns 400`() = runTest {
        testApplication {
            application { module(fakeNotifyService()) }
            val client = createClient { install(ContentNegotiation) { json() } }

            val response = client.post("/api/v1/notify/email") {
                contentType(ContentType.Application.Json)
                setBody("""{"to":["a@b.com"],"template":"missing"}""")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }
}
