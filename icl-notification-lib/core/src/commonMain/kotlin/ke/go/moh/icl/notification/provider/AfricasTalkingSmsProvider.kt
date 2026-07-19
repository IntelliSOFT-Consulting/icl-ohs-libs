package ke.go.moh.icl.notification.provider

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import ke.go.moh.icl.notification.model.NotificationResult
import ke.go.moh.icl.notification.model.RecipientResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AT statuses below 200 (Processed/Sent/Queued) are treated as delivered-to-gateway
 * success; everything else (blacklist, insufficient balance, gateway rejection, ...)
 * is a per-recipient failure. See Africa's Talking's SMS status code reference.
 */
private val SUCCESS_STATUS_CODES = setOf(100, 101, 102)

class AfricasTalkingSmsProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val username: String,
    private val senderId: String? = null,
) : SmsProvider {
    override val name: String = "africastalking"

    private val baseUrl: String
        get() = if (username == "sandbox") {
            "https://api.sandbox.africastalking.com/version1/messaging"
        } else {
            "https://api.africastalking.com/version1/messaging"
        }

    override suspend fun send(to: List<String>, message: String): NotificationResult {
        return try {
            val response = httpClient.post(baseUrl) {
                header("apiKey", apiKey)
                contentType(ContentType.Application.FormUrlEncoded)
                val form = buildString {
                    append("username=").append(username)
                    append("&to=").append(to.joinToString(","))
                    append("&message=").append(message)
                    if (senderId != null) append("&from=").append(senderId)
                }
                setBody(form)
            }
            val parsed = response.body<AfricasTalkingResponse>()
            val recipientResults = parsed.smsMessageData.recipients.map {
                RecipientResult(
                    recipient = it.number,
                    success = it.statusCode in SUCCESS_STATUS_CODES,
                    statusCode = it.statusCode,
                    status = it.status,
                    messageId = it.messageId,
                )
            }
            NotificationResult(
                success = recipientResults.isNotEmpty() && recipientResults.all { it.success },
                recipientResults = recipientResults,
            )
        } catch (e: Exception) {
            NotificationResult(success = false, error = e.message)
        }
    }
}

@Serializable
private data class AfricasTalkingResponse(
    @SerialName("SMSMessageData") val smsMessageData: AfricasTalkingMessageData,
)

@Serializable
private data class AfricasTalkingMessageData(
    @SerialName("Message") val message: String,
    @SerialName("Recipients") val recipients: List<AfricasTalkingRecipient>,
)

@Serializable
private data class AfricasTalkingRecipient(
    val statusCode: Int,
    val number: String,
    val status: String,
    val cost: String? = null,
    val messageId: String? = null,
)
