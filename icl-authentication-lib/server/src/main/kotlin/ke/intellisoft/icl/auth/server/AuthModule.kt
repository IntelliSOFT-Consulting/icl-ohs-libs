package ke.intellisoft.icl.auth.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import ke.intellisoft.icl.auth.audit.AccountLockedException
import ke.intellisoft.icl.auth.audit.AuditLogService
import ke.intellisoft.icl.auth.audit.LoginAttemptService
import ke.intellisoft.icl.auth.client.InvalidCredentialsException
import ke.intellisoft.icl.auth.client.KeycloakHttpClient
import ke.intellisoft.icl.auth.config.AuthConfig
import ke.intellisoft.icl.auth.model.*
import ke.intellisoft.icl.auth.policy.AudiencePolicy
import ke.intellisoft.icl.auth.policy.RolePolicy
import ke.intellisoft.icl.auth.security.JwtVerifier
import ke.intellisoft.icl.auth.session.RefreshTokenFamilyService
import ke.intellisoft.icl.auth.session.RotationResult
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.UUID

/**
 * Single composition point for the auth module - one `Application.module()` wiring up
 * config, auth, and every route. See README for the couple of pieces still stubbed
 * (password change / reset persistence).
 *
 * The service/client params default to real implementations built from [config] - Main.kt
 * doesn't need to change - but are overridable so tests can inject mocks/fakes without a
 * live Keycloak or JWKS endpoint.
 */
fun Application.authModule(
    config: AuthConfig,
    keycloak: KeycloakHttpClient = KeycloakHttpClient(config),
    jwtVerifier: JwtVerifier = JwtVerifier(config),
    auditLog: AuditLogService = AuditLogService(),
    loginAttempts: LoginAttemptService = LoginAttemptService(config.lockoutMaxAttempts, config.lockoutWindowMinutes),
    refreshTokenFamilies: RefreshTokenFamilyService = RefreshTokenFamilyService()
) {

    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(CallLogging)

    install(Authentication) {
        jwt("keycloak-jwt") {
            verifier(jwtVerifier.jwkProvider, jwtVerifier.issuer)
            validate { credential ->
                if (AudiencePolicy.isValid(credential.payload.audience, config.clientId)) {
                    JWTPrincipal(credential.payload)
                } else null
            }
        }
    }

    install(StatusPages) {
        exception<InvalidCredentialsException> { call, _ ->
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_credentials", "Invalid username or password."))
        }
        exception<AccountLockedException> { call, cause ->
            call.response.headers.append("Retry-After", cause.retryAfterSeconds.toString())
            call.respond(HttpStatusCode.Locked, ErrorResponse("account_temporarily_locked", "Too many failed login attempts."))
        }
        exception<ContentTransformationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", cause.message ?: "Malformed request body."))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error", "An unexpected error occurred."))
        }
    }

    routing {
        route("/auth") {

            post("/login") {
                val request = call.receive<LoginRequest>()
                loginAttempts.assertNotLocked(request.username, config.realm)
                val ip = call.request.origin.remoteHost

                val token = try {
                    keycloak.passwordGrant(request.username, request.password)
                } catch (e: InvalidCredentialsException) {
                    loginAttempts.record(request.username, config.realm, ip, succeeded = false)
                    auditLog.record("LOGIN_FAILED", null, config.realm, ip)
                    throw e
                }
                loginAttempts.record(request.username, config.realm, ip, succeeded = true)
                auditLog.record("LOGIN_SUCCESS", null, config.realm, ip)

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
                    device = call.request.headers[HttpHeaders.UserAgent],
                    ip = ip
                )

                call.respond(token.copy(realm = config.realm))
            }

            post("/refresh") {
                val request = call.receive<RefreshRequest>()
                val presentedClaims = jwtVerifier.decodeUnverified(request.refreshToken)
                val familyId = UUID.fromString(presentedClaims.getClaim("sid").asString())
                val presentedJti = UUID.fromString(presentedClaims.id)

                when (val result = refreshTokenFamilies.checkRotation(familyId, presentedJti)) {
                    RotationResult.ReuseDetected -> {
                        val userId = runCatching { UUID.fromString(presentedClaims.subject) }.getOrNull()
                        auditLog.record("TOKEN_REUSE_DETECTED", userId, config.realm, call.request.origin.remoteHost)
                        runCatching { keycloak.logout(request.refreshToken) }
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("token_reuse_detected", "This refresh token has already been used. The session has been revoked.")
                        )
                    }
                    RotationResult.AlreadyRevoked -> {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("session_revoked", "This session has been revoked."))
                    }
                    RotationResult.Valid, RotationResult.FirstSeen -> {
                        val token = keycloak.refreshGrant(request.refreshToken)
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
                                device = call.request.headers[HttpHeaders.UserAgent],
                                ip = call.request.origin.remoteHost
                            )
                        } else {
                            refreshTokenFamilies.completeRotation(familyId, newJti, newExpiresAt)
                        }

                        auditLog.record("TOKEN_REFRESH", null, config.realm, call.request.origin.remoteHost)
                        call.respond(token.copy(realm = config.realm))
                    }
                }
            }

            post("/introspect") {
                // Outside the `keycloak-jwt` auth plugin by design (Section 12: a downstream
                // service posts a bearer token here rather than needing its own JWT stack).
                val request = call.receive<IntrospectRequest>()
                val decoded = runCatching {
                    val unverified = JWT.decode(request.token)
                    val jwk = jwtVerifier.jwkProvider.get(unverified.keyId)
                    val algorithm = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)
                    JWT.require(algorithm).withIssuer(jwtVerifier.issuer).build().verify(request.token)
                }.getOrNull()

                call.respond(
                    if (decoded == null) IntrospectResponse(active = false)
                    else IntrospectResponse(active = true, sub = decoded.subject, roles = jwtVerifier.rolesOf(decoded).toList())
                )
            }

            authenticate("keycloak-jwt") {
                get("/profile") {
                    val principal = call.principal<JWTPrincipal>()!!
                    call.respond(
                        UserProfile(
                            sub = principal.payload.subject,
                            username = principal.payload.getClaim("preferred_username").asString(),
                            realm = config.realm,
                            roles = jwtVerifier.rolesOf(principal.payload).toList()
                        )
                    )
                }

                get("/roles") { call.respond(config.defaultRoles) }

                post("/logout") {
                    val request = call.receive<RefreshRequest>()
                    keycloak.logout(request.refreshToken)
                    runCatching {
                        val claims = jwtVerifier.decodeUnverified(request.refreshToken)
                        val familyId = UUID.fromString(claims.getClaim("sid").asString())
                        refreshTokenFamilies.revokeFamily(familyId, "LOGOUT")
                    }
                    auditLog.record("LOGOUT", null, config.realm, call.request.origin.remoteHost)
                    call.respond(HttpStatusCode.NoContent)
                }

                get("/sessions") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.payload.subject)
                    val currentFamilyId = principal.payload.getClaim("sid").asString()?.let { UUID.fromString(it) }
                    val sessions = refreshTokenFamilies.listSessions(userId).map {
                        SessionDto(
                            sessionId = it.sessionId.toString(),
                            device = it.device,
                            ip = it.ipAddress,
                            lastSeenAt = it.lastSeenAt.toString(),
                            current = it.familyId == currentFamilyId
                        )
                    }
                    call.respond(sessions)
                }

                get("/audit") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val roles = jwtVerifier.rolesOf(principal.payload)
                    if (!RolePolicy.isAllowed(listOf("MOH_ADMIN", "SUPER_ADMIN"), roles)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("insufficient_role", "Requires MOH_ADMIN or SUPER_ADMIN"))
                        return@get
                    }

                    val userIdFilter = call.request.queryParameters["userId"]?.let { UUID.fromString(it) }
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                    val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0)

                    call.respond(auditLog.list(userIdFilter, limit, offset))
                }
            }

            post("/password-reset/request") {
                call.receive<PasswordResetRequestDto>()
                // Always 202 regardless of whether the account exists - avoids username enumeration.
                call.respond(HttpStatusCode.Accepted, MessageResponse("If that account exists, a reset code has been sent."))
            }

            post("/password-reset/confirm") {
                call.receive<PasswordResetConfirmDto>()
                call.respond(MessageResponse("Password updated. Please log in again."))
            }
        }
    }
}
