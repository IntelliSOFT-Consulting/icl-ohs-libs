package ke.go.moh.icl.notification.provider

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import ke.go.moh.icl.notification.model.NotificationResult
import kotlinx.serialization.Serializable

/**
 * Pure Ktor Client REST call to SendGrid's v3 mail-send API - deliberately not the
 * JVM-only `sendgrid-java` SDK, so this stays usable from commonMain.
 */
class SendGridEmailProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val fromEmail: String,
    private val fromName: String,
) : EmailProvider {
    override val name: String = "sendgrid"

    override suspend fun send(to: List<String>, subject: String, body: String): NotificationResult {
        return try {
            val response = httpClient.post("https://api.sendgrid.com/v3/mail/send") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(
                    SendGridMailRequest(
                        personalizations = listOf(
                            SendGridPersonalization(to = to.map { SendGridEmailAddress(it) }),
                        ),
                        from = SendGridEmailAddress(email = fromEmail, name = fromName),
                        subject = subject,
                        content = listOf(SendGridContent(value = body)),
                    ),
                )
            }
            if (response.status == HttpStatusCode.Accepted || response.status.value in 200..299) {
                NotificationResult(
                    success = true,
                    providerMessageId = response.headers["X-Message-Id"],
                )
            } else {
                NotificationResult(
                    success = false,
                    error = "SendGrid returned ${response.status}",
                    rawResponse = response.body<String>(),
                )
            }
        } catch (e: Exception) {
            NotificationResult(success = false, error = e.message)
        }
    }
}

@Serializable
private data class SendGridMailRequest(
    val personalizations: List<SendGridPersonalization>,
    val from: SendGridEmailAddress,
    val subject: String,
    val content: List<SendGridContent>,
)

@Serializable
private data class SendGridPersonalization(val to: List<SendGridEmailAddress>)

@Serializable
private data class SendGridEmailAddress(val email: String, val name: String? = null)

@Serializable
private data class SendGridContent(val type: String = "text/html", val value: String)
