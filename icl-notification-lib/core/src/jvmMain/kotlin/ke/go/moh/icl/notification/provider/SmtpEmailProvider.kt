package ke.go.moh.icl.notification.provider

import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import ke.go.moh.icl.notification.model.NotificationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties

/**
 * The one genuinely JVM-only notification provider: raw SMTP has no multiplatform
 * library and no business running outside a server, unlike the three REST-based
 * providers in commonMain.
 */
class SmtpEmailProvider(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val fromEmail: String,
    private val fromName: String,
    private val useTls: Boolean = true,
    private val timeoutMillis: Int = 10_000,
) : EmailProvider {
    override val name: String = "smtp"

    private val session: Session by lazy {
        val props = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", useTls.toString())
            put("mail.smtp.connectiontimeout", timeoutMillis.toString())
            put("mail.smtp.timeout", timeoutMillis.toString())
        }
        Session.getInstance(
            props,
            object : jakarta.mail.Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(username, password)
            },
        )
    }

    override suspend fun send(to: List<String>, subject: String, body: String): NotificationResult {
        return withContext(Dispatchers.IO) {
            try {
                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(fromEmail, fromName))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(to.joinToString(",")))
                    setSubject(subject)
                    setContent(body, "text/html; charset=utf-8")
                }
                Transport.send(message)
                NotificationResult(success = true)
            } catch (e: Exception) {
                NotificationResult(success = false, error = e.message)
            }
        }
    }
}
