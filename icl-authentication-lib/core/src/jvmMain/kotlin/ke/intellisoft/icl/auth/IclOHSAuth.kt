package ke.intellisoft.icl.auth

import com.auth0.jwt.interfaces.DecodedJWT
import javax.sql.DataSource
import ke.intellisoft.icl.auth.audit.AuditLogService
import ke.intellisoft.icl.auth.audit.LoginAttemptService
import ke.intellisoft.icl.auth.client.InvalidCredentialsException
import ke.intellisoft.icl.auth.client.KeycloakHttpClient
import ke.intellisoft.icl.auth.config.AuthConfig
import ke.intellisoft.icl.auth.model.AuditLogEntryDto
import ke.intellisoft.icl.auth.model.IntrospectResponse
import ke.intellisoft.icl.auth.model.SessionDto
import ke.intellisoft.icl.auth.model.TokenResponse
import ke.intellisoft.icl.auth.model.UserProfile
import ke.intellisoft.icl.auth.persistence.DatabaseFactory
import ke.intellisoft.icl.auth.policy.AudiencePolicy
import ke.intellisoft.icl.auth.policy.RolePolicy
import ke.intellisoft.icl.auth.security.JwtVerifier
import ke.intellisoft.icl.auth.session.RefreshTokenFamilyService
import ke.intellisoft.icl.auth.session.RotationResult
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import java.util.UUID

private val AUDIT_ROLES = listOf("MOH_ADMIN", "SUPER_ADMIN")

/**
 * In-process entry point for a host JVM app (e.g. a Spring Boot backend) that wants to call
 * this library's auth logic directly instead of running `server/` as a separate HTTP
 * service. Owns its own Keycloak calls and Postgres connection - the host app only supplies
 * an [AuthConfig] and a [DataSource].
 *
 * Every method here ports the corresponding route handler from `server/AuthModule.kt`
 * verbatim; that module is the other caller of these same services and should stay in sync
 * with this class rather than duplicate its logic.
 */
class IclOHSAuth(
    private val config: AuthConfig,
    private val keycloak: KeycloakHttpClient = KeycloakHttpClient(config),
    private val jwtVerifier: JwtVerifier = JwtVerifier(config),
    private val auditLogService: AuditLogService = AuditLogService(),
    private val loginAttempts: LoginAttemptService = LoginAttemptService(config.lockoutMaxAttempts, config.lockoutWindowMinutes),
    private val refreshTokenFamilies: RefreshTokenFamilyService = RefreshTokenFamilyService()
) {

    /** Runs this library's Flyway migrations against [dataSource], then connects Exposed to it. */
    constructor(config: AuthConfig, dataSource: DataSource) : this(config) {
        Flyway.configure()
            .dataSource(dataSource)
            .schemas("ohs_auth")
            .load()
            .migrate()
        DatabaseFactory.connect(dataSource)
    }

    fun login(username: String, password: String, ip: String? = null, userAgent: String? = null): TokenResponse {
        loginAttempts.assertNotLocked(username, config.realm)

        val token = try {
            runBlocking { keycloak.passwordGrant(username, password) }
        } catch (e: InvalidCredentialsException) {
            loginAttempts.record(username, config.realm, ip, succeeded = false)
            auditLogService.record("LOGIN_FAILED", null, config.realm, ip, userAgent)
            throw e
        }
        loginAttempts.record(username, config.realm, ip, succeeded = true)
        auditLogService.record("LOGIN_SUCCESS", null, config.realm, ip, userAgent)

        val refreshClaims = jwtVerifier.decodeUnverified(token.refreshToken)
        val familyId = UUID.fromString(refreshClaims.getClaim("sid").asString())
        val userId = UUID.fromString(refreshClaims.subject)
        refreshTokenFamilies.createFamily(
            familyId = familyId,
            userId = userId,
            currentToken = UUID.fromString(refreshClaims.id),
            expiresAt = refreshClaims.expiresAt.toInstant()
        )
        refreshTokenFamilies.createSession(
            sessionId = UUID.randomUUID(),
            userId = userId,
            familyId = familyId,
            device = userAgent,
            ip = ip
        )

        return token.copy(realm = config.realm)
    }

    fun refresh(refreshToken: String, ip: String? = null, userAgent: String? = null): TokenResponse {
        val presentedClaims = jwtVerifier.decodeUnverified(refreshToken)
        val familyId = UUID.fromString(presentedClaims.getClaim("sid").asString())
        val presentedJti = UUID.fromString(presentedClaims.id)

        return when (val result = refreshTokenFamilies.checkRotation(familyId, presentedJti)) {
            RotationResult.ReuseDetected -> {
                val userId = runCatching { UUID.fromString(presentedClaims.subject) }.getOrNull()
                auditLogService.record("TOKEN_REUSE_DETECTED", userId, config.realm, ip)
                runCatching { runBlocking { keycloak.logout(refreshToken) } }
                throw TokenReuseDetectedException()
            }
            RotationResult.AlreadyRevoked -> throw SessionRevokedException()
            RotationResult.Valid, RotationResult.FirstSeen -> {
                val token = runBlocking { keycloak.refreshGrant(refreshToken) }
                val newClaims = jwtVerifier.decodeUnverified(token.refreshToken)
                val newJti = UUID.fromString(newClaims.id)
                val newExpiresAt = newClaims.expiresAt.toInstant()

                if (result == RotationResult.FirstSeen) {
                    val userId = UUID.fromString(newClaims.subject)
                    refreshTokenFamilies.createFamily(familyId, userId, newJti, newExpiresAt)
                    refreshTokenFamilies.createSession(
                        sessionId = UUID.randomUUID(),
                        userId = userId,
                        familyId = familyId,
                        device = userAgent,
                        ip = ip
                    )
                } else {
                    refreshTokenFamilies.completeRotation(familyId, newJti, newExpiresAt)
                }

                auditLogService.record("TOKEN_REFRESH", null, config.realm, ip)
                token.copy(realm = config.realm)
            }
        }
    }

    fun logout(refreshToken: String, ip: String? = null) {
        runBlocking { keycloak.logout(refreshToken) }
        runCatching {
            val claims = jwtVerifier.decodeUnverified(refreshToken)
            val familyId = UUID.fromString(claims.getClaim("sid").asString())
            refreshTokenFamilies.revokeFamily(familyId, "LOGOUT")
        }
        auditLogService.record("LOGOUT", null, config.realm, ip)
    }

    /** Never throws - answers `active = false` for any invalid/expired/malformed token. */
    fun introspect(token: String): IntrospectResponse {
        val decoded = jwtVerifier.verify(token)
        return if (decoded == null) IntrospectResponse(active = false)
        else IntrospectResponse(active = true, sub = decoded.subject, roles = jwtVerifier.rolesOf(decoded).toList())
    }

    fun profile(accessToken: String): UserProfile {
        val principal = verifiedPrincipal(accessToken)
        return UserProfile(
            sub = principal.subject,
            username = principal.getClaim("preferred_username").asString(),
            realm = config.realm,
            roles = jwtVerifier.rolesOf(principal).toList()
        )
    }

    fun sessions(accessToken: String): List<SessionDto> {
        val principal = verifiedPrincipal(accessToken)
        val userId = UUID.fromString(principal.subject)
        val currentFamilyId = principal.getClaim("sid").asString()?.let { UUID.fromString(it) }
        return refreshTokenFamilies.listSessions(userId).map {
            SessionDto(
                sessionId = it.sessionId.toString(),
                device = it.device,
                ip = it.ipAddress,
                lastSeenAt = it.lastSeenAt.toString(),
                current = it.familyId == currentFamilyId
            )
        }
    }

    fun auditLog(accessToken: String, userIdFilter: UUID? = null, limit: Int = 50, offset: Long = 0): List<AuditLogEntryDto> {
        val principal = verifiedPrincipal(accessToken)
        val roles = jwtVerifier.rolesOf(principal)
        if (!RolePolicy.isAllowed(AUDIT_ROLES, roles)) throw InsufficientRoleException(AUDIT_ROLES)

        return auditLogService.list(userIdFilter, limit.coerceIn(1, 100), offset.coerceAtLeast(0))
    }

    fun roles(): List<String> = config.defaultRoles

    /** Signature + issuer + audience check - the in-process equivalent of Ktor's `keycloak-jwt` auth plugin. */
    private fun verifiedPrincipal(accessToken: String): DecodedJWT {
        val decoded = jwtVerifier.verify(accessToken) ?: throw InvalidTokenException()
        if (!AudiencePolicy.isValid(decoded.audience, config.clientId)) throw InvalidTokenException()
        return decoded
    }
}
