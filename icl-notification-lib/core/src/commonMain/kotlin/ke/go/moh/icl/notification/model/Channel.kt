package ke.go.moh.icl.notification.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Channel {
    @SerialName("email")
    EMAIL,

    @SerialName("sms")
    SMS,
}
