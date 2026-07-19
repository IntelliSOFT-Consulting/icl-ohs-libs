package ke.go.moh.icl.notification.provider

import ke.go.moh.icl.notification.model.NotificationResult

interface EmailProvider {
    val name: String

    suspend fun send(to: List<String>, subject: String, body: String): NotificationResult
}

interface SmsProvider {
    val name: String

    suspend fun send(to: List<String>, message: String): NotificationResult
}
