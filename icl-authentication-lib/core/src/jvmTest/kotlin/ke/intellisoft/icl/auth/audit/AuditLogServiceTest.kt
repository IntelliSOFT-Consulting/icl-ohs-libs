package ke.intellisoft.icl.auth.audit

import ke.intellisoft.icl.auth.TestDatabase
import ke.intellisoft.icl.auth.persistence.AuditLog
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuditLogServiceTest {

    private val service = AuditLogService()

    @BeforeTest
    fun setUp() {
        TestDatabase.ensureReady()
        TestDatabase.clearAll()
    }

    @Test
    fun `hash chain verifies intact after several records`() {
        service.record("LOGIN_SUCCESS", null, "icl-realm", "127.0.0.1")
        service.record("TOKEN_REFRESH", null, "icl-realm", "127.0.0.1")
        service.record("LOGOUT", null, "icl-realm", "127.0.0.1")

        assertTrue(service.verifyChainIntegrity())
    }

    @Test
    fun `tampering with a row breaks chain verification`() {
        service.record("LOGIN_SUCCESS", null, "icl-realm", "127.0.0.1")
        service.record("TOKEN_REFRESH", null, "icl-realm", "127.0.0.1")

        transaction {
            AuditLog.update({ AuditLog.event eq "LOGIN_SUCCESS" }) {
                it[AuditLog.event] = "TAMPERED"
            }
        }

        assertFalse(service.verifyChainIntegrity())
    }
}
