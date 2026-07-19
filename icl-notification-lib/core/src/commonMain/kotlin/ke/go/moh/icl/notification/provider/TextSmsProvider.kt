package ke.go.moh.icl.notification.provider

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import ke.go.moh.icl.notification.model.NotificationResult
import ke.go.moh.icl.notification.model.RecipientResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ASSUMED CONTRACT - verify against TextSMS's live docs before production use.
 * Endpoint, field names (including the `responses`/`response-code` casing below,
 * which mirrors a long-standing typo in TextSMS's own API) and status semantics are
 * based on publicly-documented TextSMS (sms.textsms.co.ke) integrations at the time
 * this provider was written, not a signed contract with TextSMS.
 */
class TextSmsProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val partnerId: String,
    private val shortcode: String,
) : SmsProvider {
    override val name: String = "textsms"

    override suspend fun send(to: List<String>, message: String): NotificationResult {
        return try {
            val response = httpClient.post("https://sms.textsms.co.ke/api/services/sendsms/") {
                contentType(ContentType.Application.Json)
                setBody(
                    TextSmsRequest(
                        apikey = apiKey,
                        partnerID = partnerId,
                        message = message,
                        shortcode = shortcode,
                        mobile = to.joinToString(","),
                    ),
                )
            }
            val parsed = response.body<TextSmsResponse>()
            val recipientResults = parsed.responses.map {
                RecipientResult(
                    recipient = it.mobileno,
                    success = it.responseCode == 200,
                    statusCode = it.responseCode,
                    status = it.responseDescription,
                    messageId = it.messageid,
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
private data class TextSmsRequest(
    val apikey: String,
    val partnerID: String,
    val message: String,
    val shortcode: String,
    val mobile: String,
)

@Serializable
private data class TextSmsResponse(val responses: List<TextSmsRecipientResponse> = emptyList())

@Serializable
private data class TextSmsRecipientResponse(
    @SerialName("respose-code") val responseCode: Int,
    @SerialName("response-description") val responseDescription: String? = null,
    val mobileno: String,
    val messageid: String? = null,
    val networkid: String? = null,
)
