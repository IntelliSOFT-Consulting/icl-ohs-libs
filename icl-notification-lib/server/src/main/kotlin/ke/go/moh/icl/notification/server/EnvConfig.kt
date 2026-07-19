package ke.go.moh.icl.notification.server

import ke.go.moh.icl.notification.config.AfricasTalkingConfig
import ke.go.moh.icl.notification.config.EmailProviderType
import ke.go.moh.icl.notification.config.NotificationConfig
import ke.go.moh.icl.notification.config.SendGridConfig
import ke.go.moh.icl.notification.config.SmsProviderType
import ke.go.moh.icl.notification.config.SmtpConfig
import ke.go.moh.icl.notification.config.TextSmsConfig

class MissingConfigException(envVar: String) : Exception("Missing required environment variable: $envVar")

private fun env(name: String): String = System.getenv(name) ?: throw MissingConfigException(name)
private fun envOrNull(name: String): String? = System.getenv(name)
private fun envOrDefault(name: String, default: String): String = System.getenv(name) ?: default

fun loadNotificationConfigFromEnv(): NotificationConfig {
    val emailProvider = EmailProviderType.valueOf(envOrDefault("NOTIFY_EMAIL_PROVIDER", "smtp").uppercase())
    val smsProvider = SmsProviderType.valueOf(envOrDefault("NOTIFY_SMS_PROVIDER", "africastalking").uppercase())

    return NotificationConfig(
        fromEmail = env("NOTIFY_FROM_EMAIL"),
        fromName = envOrDefault("NOTIFY_FROM_NAME", "ICL OHS"),
        emailProvider = emailProvider,
        smsProvider = smsProvider,
        smtp = if (emailProvider == EmailProviderType.SMTP) {
            SmtpConfig(
                host = envOrDefault("SMTP_HOST", "localhost"),
                port = envOrDefault("SMTP_PORT", "1025").toInt(),
                username = envOrDefault("SMTP_USER", ""),
                password = envOrDefault("SMTP_PASS", ""),
                useTls = envOrDefault("SMTP_USE_TLS", "false").toBoolean(),
            )
        } else {
            null
        },
        sendGrid = if (emailProvider == EmailProviderType.SENDGRID) {
            SendGridConfig(apiKey = env("SENDGRID_API_KEY"))
        } else {
            null
        },
        africasTalking = if (smsProvider == SmsProviderType.AFRICASTALKING) {
            AfricasTalkingConfig(
                apiKey = env("AT_API_KEY"),
                username = envOrDefault("AT_USERNAME", "sandbox"),
                senderId = envOrNull("SMS_SENDER_ID"),
            )
        } else {
            null
        },
        textSms = if (smsProvider == SmsProviderType.TEXTSMS) {
            TextSmsConfig(
                apiKey = env("TEXTSMS_API_KEY"),
                partnerId = env("TEXTSMS_PARTNER_ID"),
                shortcode = env("TEXTSMS_SHORTCODE"),
            )
        } else {
            null
        },
    )
}

fun loadPortFromEnv(): Int = envOrDefault("PORT", "8080").toInt()
