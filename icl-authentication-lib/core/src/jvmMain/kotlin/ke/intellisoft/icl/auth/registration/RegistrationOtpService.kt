package ke.intellisoft.icl.auth.registration

import ke.intellisoft.icl.auth.persistence.RegistrationOtps
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/** Generates, stores (hashed), and verifies the one-time codes used to activate a registered account. */
class RegistrationOtpService(
    private val expiryMinutes: Long,
    private val maxAttempts: Int,
    private val otpLength: Int
) {

    /** Generates a new OTP, stores its hash, and returns the raw code for the caller to deliver. */
    fun generateAndStore(keycloakUserId: UUID, username: String): String {
        val otp = (1..otpLength).joinToString("") { Random.nextInt(0, 10).toString() }
        transaction {
            RegistrationOtps.insert {
                it[RegistrationOtps.keycloakUserId] = keycloakUserId
                it[RegistrationOtps.username] = username.lowercase()
                it[otpHash] = hash(otp)
                it[expiresAt] = Instant.now().plusSeconds(expiryMinutes * 60)
                it[attempts] = 0
                it[consumed] = false
                it[createdAt] = Instant.now()
            }
        }
        return otp
    }

    /** True if [presentedOtp] matches the most recent unconsumed OTP for [username] and isn't expired/exhausted. */
    fun verify(username: String, presentedOtp: String): Boolean = transaction {
        val row = RegistrationOtps.selectAll()
            .andWhere { RegistrationOtps.username eq username.lowercase() }
            .andWhere { RegistrationOtps.consumed eq false }
            .orderBy(RegistrationOtps.id, SortOrder.DESC)
            .limit(1)
            .singleOrNull() ?: return@transaction false

        if (row[RegistrationOtps.attempts] >= maxAttempts) return@transaction false
        if (row[RegistrationOtps.expiresAt].isBefore(Instant.now())) return@transaction false

        val matches = row[RegistrationOtps.otpHash] == hash(presentedOtp)
        RegistrationOtps.update({ RegistrationOtps.id eq row[RegistrationOtps.id] }) {
            if (matches) it[consumed] = true else it[attempts] = row[RegistrationOtps.attempts] + 1
        }
        matches
    }

    /** The Keycloak user id tied to the most recent OTP row for [username] - call after [verify] succeeds. */
    fun keycloakUserIdFor(username: String): UUID = transaction {
        RegistrationOtps.selectAll()
            .andWhere { RegistrationOtps.username eq username.lowercase() }
            .orderBy(RegistrationOtps.id, SortOrder.DESC)
            .limit(1)
            .single()[RegistrationOtps.keycloakUserId]
    }

    private fun hash(otp: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(otp.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
