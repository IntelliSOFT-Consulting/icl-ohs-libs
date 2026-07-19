package ke.go.moh.icl.notification

import ke.go.moh.icl.notification.model.BroadcastRequest
import ke.go.moh.icl.notification.model.BroadcastResult
import ke.go.moh.icl.notification.model.Channel
import ke.go.moh.icl.notification.model.EmailRequest
import ke.go.moh.icl.notification.model.NotificationResult
import ke.go.moh.icl.notification.model.SmsRequest
import ke.go.moh.icl.notification.provider.EmailProvider
import ke.go.moh.icl.notification.provider.SmsProvider
import ke.go.moh.icl.notification.template.TemplateRegistry

/**
 * Facade resolving the active email/SMS provider (chosen at construction time from
 * config) and the shared template registry into a single call surface for routes.
 */
class NotifyService(
    private val emailProvider: EmailProvider,
    private val smsProvider: SmsProvider,
    private val templateRegistry: TemplateRegistry,
) {
    suspend fun sendEmail(request: EmailRequest): NotificationResult {
        val body = templateRegistry.render(request.template, request.data)
        return emailProvider.send(request.to, request.subject.orEmpty(), body)
    }

    suspend fun sendSms(request: SmsRequest): NotificationResult {
        return smsProvider.send(request.to, request.message)
    }

    suspend fun broadcast(request: BroadcastRequest): BroadcastResult {
        var sent = 0
        var failed = 0
        for (recipient in request.recipients) {
            val result = try {
                when (request.channel) {
                    Channel.EMAIL -> {
                        val template = requireNotNull(request.template) { "template is required for an email broadcast" }
                        val body = templateRegistry.render(template, request.data)
                        emailProvider.send(listOf(recipient), request.subject.orEmpty(), body)
                    }
                    Channel.SMS -> {
                        val message = requireNotNull(request.message) { "message is required for an sms broadcast" }
                        smsProvider.send(listOf(recipient), message)
                    }
                }
            } catch (e: Exception) {
                NotificationResult(success = false, error = e.message)
            }
            if (result.success) sent++ else failed++
        }
        return BroadcastResult(total = request.recipients.size, sent = sent, failed = failed)
    }

    fun templates(): List<String> = templateRegistry.names()
}
