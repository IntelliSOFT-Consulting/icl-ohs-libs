package ke.intellisoft.icl.auth.policy

/**
 * Pure lockout decision logic, framework-free so it's trivially unit-testable in
 * commonTest. The JVM server supplies the actual "recent attempts" list from Postgres;
 * this object only decides whether that list means "locked."
 */
object LockoutPolicy {

    data class Decision(val locked: Boolean, val retryAfterSeconds: Long)

    fun evaluate(
        failuresInWindow: Int,
        maxAttempts: Int,
        oldestFailureEpochSeconds: Long?,
        windowSeconds: Long,
        nowEpochSeconds: Long
    ): Decision {
        if (failuresInWindow < maxAttempts || oldestFailureEpochSeconds == null) {
            return Decision(locked = false, retryAfterSeconds = 0)
        }
        val retryAfter = (oldestFailureEpochSeconds + windowSeconds) - nowEpochSeconds
        return Decision(locked = true, retryAfterSeconds = retryAfter.coerceAtLeast(1))
    }
}
