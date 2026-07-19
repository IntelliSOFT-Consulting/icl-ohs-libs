package ke.intellisoft.icl.auth

import ke.intellisoft.icl.auth.policy.LockoutPolicy
import ke.intellisoft.icl.auth.policy.RolePolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure-function policy logic needs no server, no database, no mocked HTTP - runs on every target. */
class PolicyTest {

    @Test
    fun `role policy allows when any required role matches`() {
        assertTrue(RolePolicy.isAllowed(listOf("MOH_ADMIN", "SUPER_ADMIN"), setOf("SUPER_ADMIN")))
        assertFalse(RolePolicy.isAllowed(listOf("MOH_ADMIN"), setOf("FACILITY_NURSE")))
    }

    @Test
    fun `lockout policy locks once failure threshold is reached within the window`() {
        val decision = LockoutPolicy.evaluate(
            failuresInWindow = 5,
            maxAttempts = 5,
            oldestFailureEpochSeconds = 1_000L,
            windowSeconds = 900L,
            nowEpochSeconds = 1_100L
        )
        assertTrue(decision.locked)
        assertTrue(decision.retryAfterSeconds > 0)
    }

    @Test
    fun `lockout policy does not lock below threshold`() {
        val decision = LockoutPolicy.evaluate(
            failuresInWindow = 2,
            maxAttempts = 5,
            oldestFailureEpochSeconds = 1_000L,
            windowSeconds = 900L,
            nowEpochSeconds = 1_100L
        )
        assertFalse(decision.locked)
    }
}
