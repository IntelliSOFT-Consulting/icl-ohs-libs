package ke.go.moh.icl.notification.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import ke.go.moh.icl.notification.NotifyService
import ke.go.moh.icl.notification.config.EmailProviderType
import ke.go.moh.icl.notification.config.NotificationConfig
import ke.go.moh.icl.notification.config.SmsProviderType
import ke.go.moh.icl.notification.provider.AfricasTalkingSmsProvider
import ke.go.moh.icl.notification.provider.EmailProvider
import ke.go.moh.icl.notification.provider.SendGridEmailProvider
import ke.go.moh.icl.notification.provider.SmsProvider
import ke.go.moh.icl.notification.provider.SmtpEmailProvider
import ke.go.moh.icl.notification.provider.TextSmsProvider
import ke.go.moh.icl.notification.template.TemplateRegistry

/**
 * Picks the concrete provider per channel from config - this is the one place that
 * needs both the JVM-only SmtpEmailProvider and the shared REST providers, so it lives
 * in server/ rather than core/jvmMain.
 */
fun buildNotifyService(config: NotificationConfig, httpClient: HttpClient = defaultHttpClient()): NotifyService {
    val emailProvider: EmailProvider = when (config.emailProvider) {
        EmailProviderType.SMTP -> {
            val smtp = requireNotNull(config.smtp) { "SMTP config missing" }
            SmtpEmailProvider(
                host = smtp.host,
                port = smtp.port,
                username = smtp.username,
                password = smtp.password,
                fromEmail = config.fromEmail,
                fromName = config.fromName,
                useTls = smtp.useTls,
            )
        }
        EmailProviderType.SENDGRID -> {
            val sendGrid = requireNotNull(config.sendGrid) { "SendGrid config missing" }
            SendGridEmailProvider(httpClient, sendGrid.apiKey, config.fromEmail, config.fromName)
        }
    }

    val smsProvider: SmsProvider = when (config.smsProvider) {
        SmsProviderType.AFRICASTALKING -> {
            val at = requireNotNull(config.africasTalking) { "Africa's Talking config missing" }
            AfricasTalkingSmsProvider(httpClient, at.apiKey, at.username, at.senderId)
        }
        SmsProviderType.TEXTSMS -> {
            val textSms = requireNotNull(config.textSms) { "TextSMS config missing" }
            TextSmsProvider(httpClient, textSms.apiKey, textSms.partnerId, textSms.shortcode)
        }
    }

    return NotifyService(emailProvider, smsProvider, defaultTemplateRegistry())
}

fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json() }
}

// MVP set of starter templates. Known gap: these are hardcoded rather than loaded from
// a store, so adding/editing a template today means a code change and redeploy - see
// README "Notes / known gaps".
private fun defaultTemplateRegistry(): TemplateRegistry = TemplateRegistry().apply {
    register("otp", "Your ICL OHS verification code is {{code}}. It expires in {{minutes}} minutes.")
    register("password-reset", "Hi {{name}}, reset your password using this link: {{resetLink}}")
    register("appointment-reminder", "Hi {{name}}, this is a reminder for your appointment at {{facility}} on {{date}}.")
}
