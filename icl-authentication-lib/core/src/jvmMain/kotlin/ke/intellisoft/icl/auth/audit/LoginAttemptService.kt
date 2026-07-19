package ke.intellisoft.icl.auth.audit

import ke.intellisoft.icl.auth.persistence.LoginAttempts
import ke.intellisoft.icl.auth.policy.LockoutPolicy
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

class LoginAttemptService(private val maxAttempts: Int, private val windowMinutes: Long) {

    fun assertNotLocked(username: String, realm: String) {
        val normalizedUsername = username.lowercase()
        val decision = transaction {
            val since = Instant.now().minusSeconds(windowMinutes * 60)
            val recentFailures = LoginAttempts
                .selectAll()
                .andWhere { LoginAttempts.username eq normalizedUsername }
                .andWhere { LoginAttempts.realm eq realm }
                .andWhere { LoginAttempts.succeeded eq false }
                .andWhere { LoginAttempts.attemptedAt greaterEq since }
                .orderBy(LoginAttempts.attemptedAt, SortOrder.ASC)
                .toList()

            val oldest = recentFailures.firstOrNull()?.get(LoginAttempts.attemptedAt)?.epochSecond
            LockoutPolicy.evaluate(
                failuresInWindow = recentFailures.size,
                maxAttempts = maxAttempts,
                oldestFailureEpochSeconds = oldest,
                windowSeconds = windowMinutes * 60,
                nowEpochSeconds = Instant.now().epochSecond
            )
        }
        if (decision.locked) {
            throw AccountLockedException(decision.retryAfterSeconds)
        }
    }

    fun record(username: String, realm: String, ipAddress: String?, succeeded: Boolean): Unit = transaction {
        LoginAttempts.insert {
            it[LoginAttempts.username] = username.lowercase()
            it[LoginAttempts.realm] = realm
            it[LoginAttempts.ipAddress] = ipAddress
            it[LoginAttempts.succeeded] = succeeded
            it[LoginAttempts.attemptedAt] = Instant.now()
        }
    }
}

class AccountLockedException(val retryAfterSeconds: Long) : RuntimeException("Account temporarily locked")
