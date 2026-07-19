package ke.intellisoft.icl.auth.audit

import ke.intellisoft.icl.auth.TestDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LoginAttemptServiceTest {

    private val service = LoginAttemptService(maxAttempts = 3, windowMinutes = 15)

    @BeforeTest
    fun setUp() {
        TestDatabase.ensureReady()
        TestDatabase.clearAll()
    }

    @Test
    fun `locks out after max attempts within the window`() {
        repeat(3) {
            service.assertNotLocked("demo.nurse", "icl-realm")
            service.record("demo.nurse", "icl-realm", "127.0.0.1", succeeded = false)
        }
        assertFailsWith<AccountLockedException> {
            service.assertNotLocked("demo.nurse", "icl-realm")
        }
    }

    @Test
    fun `lockout tracking is case-insensitive`() {
        repeat(3) {
            service.record("Demo.Nurse", "icl-realm", "127.0.0.1", succeeded = false)
        }
        assertFailsWith<AccountLockedException> {
            service.assertNotLocked("demo.nurse", "icl-realm")
        }
    }

    @Test
    fun `does not lock a different username`() {
        repeat(3) {
            service.record("demo.nurse", "icl-realm", "127.0.0.1", succeeded = false)
        }
        service.assertNotLocked("other.user", "icl-realm")
    }
}
