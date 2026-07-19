package ke.go.moh.icl.notification.config

enum class EmailProviderType { SMTP, SENDGRID }

enum class SmsProviderType { AFRICASTALKING, TEXTSMS }

data class SmtpConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val useTls: Boolean = true,
)

data class SendGridConfig(
    val apiKey: String,
)

data class AfricasTalkingConfig(
    val apiKey: String,
    val username: String,
    val senderId: String? = null,
)

data class TextSmsConfig(
    val apiKey: String,
    val partnerId: String,
    val shortcode: String,
)

data class NotificationConfig(
    val fromEmail: String,
    val fromName: String,
    val emailProvider: EmailProviderType,
    val smsProvider: SmsProviderType,
    val smtp: SmtpConfig? = null,
    val sendGrid: SendGridConfig? = null,
    val africasTalking: AfricasTalkingConfig? = null,
    val textSms: TextSmsConfig? = null,
)
