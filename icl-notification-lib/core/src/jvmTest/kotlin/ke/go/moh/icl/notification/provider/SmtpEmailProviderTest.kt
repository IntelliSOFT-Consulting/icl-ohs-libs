package ke.go.moh.icl.notification.provider

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmtpEmailProviderTest {
    private val greenMail = GreenMail(ServerSetupTest.SMTP)

    @BeforeTest
    fun startServer() {
        greenMail.start()
        greenMail.setUser("notify@icl.go.ke", "notify", "test-pass")
    }

    @AfterTest
    fun stopServer() {
        greenMail.stop()
    }

    @Test
    fun `delivers message through a fake smtp server`() = runTest {
        val provider = SmtpEmailProvider(
            host = "127.0.0.1",
            port = greenMail.smtp.serverSetup.port,
            username = "notify",
            password = "test-pass",
            fromEmail = "notify@icl.go.ke",
            fromName = "ICL OHS",
            useTls = false,
        )

        val result = provider.send(listOf("nurse@facility.go.ke"), "Reminder", "<p>Appointment tomorrow</p>")

        assertTrue(result.success)
        val received = greenMail.receivedMessages
        assertEquals(1, received.size)
        assertEquals("Reminder", received.first().subject)
    }
}
