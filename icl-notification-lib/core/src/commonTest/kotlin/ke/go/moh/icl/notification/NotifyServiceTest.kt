package ke.go.moh.icl.notification

import ke.go.moh.icl.notification.model.BroadcastRequest
import ke.go.moh.icl.notification.model.Channel
import ke.go.moh.icl.notification.model.EmailRequest
import ke.go.moh.icl.notification.model.NotificationResult
import ke.go.moh.icl.notification.model.SmsRequest
import ke.go.moh.icl.notification.provider.EmailProvider
import ke.go.moh.icl.notification.provider.SmsProvider
import ke.go.moh.icl.notification.template.TemplateRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeEmailProvider(private val failFor: Set<String> = emptySet()) : EmailProvider {
    override val name = "fake-email"
    val sentBodies = mutableListOf<String>()

    override suspend fun send(to: List<String>, subject: String, body: String): NotificationResult {
        sentBodies.add(body)
        return NotificationResult(success = to.none { it in failFor })
    }
}

private class FakeSmsProvider(private val failFor: Set<String> = emptySet()) : SmsProvider {
    override val name = "fake-sms"

    override suspend fun send(to: List<String>, message: String): NotificationResult {
        return NotificationResult(success = to.none { it in failFor })
    }
}

class NotifyServiceTest {
    private fun registry() = TemplateRegistry().apply {
        register("welcome", "Hello {{name}}")
    }

    @Test
    fun `sendEmail renders template before delegating to provider`() = runTest {
        val emailProvider = FakeEmailProvider()
        val service = NotifyService(emailProvider, FakeSmsProvider(), registry())

        val result = service.sendEmail(EmailRequest(to = listOf("a@b.com"), template = "welcome", data = mapOf("name" to "Asha")))

        assertTrue(result.success)
        assertEquals("Hello Asha", emailProvider.sentBodies.single())
    }

    @Test
    fun `sendSms delegates directly to provider`() = runTest {
        val service = NotifyService(FakeEmailProvider(), FakeSmsProvider(), registry())

        val result = service.sendSms(SmsRequest(to = listOf("+254700000000"), message = "Hi"))

        assertTrue(result.success)
    }

    @Test
    fun `broadcast reports partial failure without throwing`() = runTest {
        val service = NotifyService(
            FakeEmailProvider(),
            FakeSmsProvider(failFor = setOf("+254700000001")),
            registry(),
        )

        val result = service.broadcast(
            BroadcastRequest(
                channel = Channel.SMS,
                recipients = listOf("+254700000000", "+254700000001"),
                message = "Reminder",
            ),
        )

        assertEquals(2, result.total)
        assertEquals(1, result.sent)
        assertEquals(1, result.failed)
    }

    @Test
    fun `templates exposes registered template names`() {
        val service = NotifyService(FakeEmailProvider(), FakeSmsProvider(), registry())

        assertEquals(listOf("welcome"), service.templates())
    }
}
