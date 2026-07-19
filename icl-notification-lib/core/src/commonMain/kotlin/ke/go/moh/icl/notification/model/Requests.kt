package ke.go.moh.icl.notification.model

import kotlinx.serialization.Serializable

@Serializable
data class EmailRequest(
    val to: List<String>,
    val template: String,
    val subject: String? = null,
    val data: Map<String, String> = emptyMap(),
)

@Serializable
data class SmsRequest(
    val to: List<String>,
    val message: String,
)

@Serializable
data class BroadcastRequest(
    val channel: Channel,
    val recipients: List<String>,
    val template: String? = null,
    val subject: String? = null,
    val message: String? = null,
    val data: Map<String, String> = emptyMap(),
)
