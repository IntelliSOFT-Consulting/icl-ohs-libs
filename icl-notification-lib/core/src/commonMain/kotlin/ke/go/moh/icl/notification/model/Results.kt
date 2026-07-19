package ke.go.moh.icl.notification.model

import kotlinx.serialization.Serializable

@Serializable
data class NotificationResult(
    val success: Boolean,
    val providerMessageId: String? = null,
    val error: String? = null,
    val rawResponse: String? = null,
    val recipientResults: List<RecipientResult>? = null,
)

@Serializable
data class RecipientResult(
    val recipient: String,
    val success: Boolean,
    val statusCode: Int? = null,
    val status: String? = null,
    val messageId: String? = null,
)

@Serializable
data class BroadcastResult(
    val total: Int,
    val sent: Int,
    val failed: Int,
)
