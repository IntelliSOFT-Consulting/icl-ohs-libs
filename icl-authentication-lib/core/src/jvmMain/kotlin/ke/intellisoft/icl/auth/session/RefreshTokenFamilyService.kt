package ke.intellisoft.icl.auth.session

import ke.intellisoft.icl.auth.persistence.RefreshTokenFamilies
import ke.intellisoft.icl.auth.persistence.Sessions
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/**
 * Tracks refresh-token rotation per Keycloak SSO session (`family_id` == the token's `sid`
 * claim, constant across every rotation) to detect replay of an already-rotated-away
 * refresh token - unambiguous evidence the token was stolen. `checkRotation` is a hard
 * security gate and must run - and be honored - before the presented token is ever
 * forwarded to Keycloak; `completeRotation` only runs after Keycloak has actually issued
 * the next token pair.
 */
class RefreshTokenFamilyService {

    fun createFamily(familyId: UUID, userId: UUID, currentToken: UUID, expiresAt: Instant): Unit = transaction {
        RefreshTokenFamilies.insert {
            it[RefreshTokenFamilies.familyId] = familyId
            it[RefreshTokenFamilies.userId] = userId
            it[RefreshTokenFamilies.currentToken] = currentToken
            it[issuedAt] = Instant.now()
            it[RefreshTokenFamilies.expiresAt] = expiresAt
            it[revoked] = false
        }
    }

    fun createSession(sessionId: UUID, userId: UUID, familyId: UUID, device: String?, ip: String?): Unit = transaction {
        Sessions.insert {
            it[Sessions.sessionId] = sessionId
            it[Sessions.userId] = userId
            it[Sessions.familyId] = familyId
            it[Sessions.device] = device
            it[ipAddress] = ip
            it[createdAt] = Instant.now()
            it[lastSeenAt] = Instant.now()
            it[revoked] = false
        }
    }

    /** Must be called - and its result honored - before the presented token reaches Keycloak. */
    fun checkRotation(familyId: UUID, presentedJti: UUID): RotationResult = transaction {
        val family = RefreshTokenFamilies.selectAll()
            .andWhere { RefreshTokenFamilies.familyId eq familyId }
            .singleOrNull()

        when {
            family == null -> RotationResult.FirstSeen
            family[RefreshTokenFamilies.revoked] -> RotationResult.AlreadyRevoked
            family[RefreshTokenFamilies.currentToken] != presentedJti -> {
                RefreshTokenFamilies.update({ RefreshTokenFamilies.familyId eq familyId }) {
                    it[revoked] = true
                    it[revokedReason] = "TOKEN_REUSE_DETECTED"
                }
                Sessions.update({ Sessions.familyId eq familyId }) { it[revoked] = true }
                RotationResult.ReuseDetected
            }
            else -> RotationResult.Valid
        }
    }

    /** Call only after Keycloak has successfully issued the next refresh token. */
    fun completeRotation(familyId: UUID, newJti: UUID, newExpiresAt: Instant): Unit = transaction {
        RefreshTokenFamilies.update({ RefreshTokenFamilies.familyId eq familyId }) {
            it[currentToken] = newJti
            it[expiresAt] = newExpiresAt
        }
        Sessions.update({ Sessions.familyId eq familyId }) { it[lastSeenAt] = Instant.now() }
    }

    fun revokeFamily(familyId: UUID, reason: String): Unit = transaction {
        RefreshTokenFamilies.update({ RefreshTokenFamilies.familyId eq familyId }) {
            it[revoked] = true
            it[revokedReason] = reason
        }
        Sessions.update({ Sessions.familyId eq familyId }) { it[revoked] = true }
    }

    fun listSessions(userId: UUID): List<SessionRow> = transaction {
        Sessions.selectAll()
            .andWhere { Sessions.userId eq userId }
            .andWhere { Sessions.revoked eq false }
            .orderBy(Sessions.lastSeenAt, SortOrder.DESC)
            .map {
                SessionRow(
                    sessionId = it[Sessions.sessionId],
                    familyId = it[Sessions.familyId],
                    device = it[Sessions.device],
                    ipAddress = it[Sessions.ipAddress],
                    lastSeenAt = it[Sessions.lastSeenAt]
                )
            }
    }
}

data class SessionRow(
    val sessionId: UUID,
    val familyId: UUID,
    val device: String?,
    val ipAddress: String?,
    val lastSeenAt: Instant
)

sealed interface RotationResult {
    data object Valid : RotationResult
    data object FirstSeen : RotationResult
    data object ReuseDetected : RotationResult
    data object AlreadyRevoked : RotationResult
}
