package ke.intellisoft.icl.auth.server

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
import ke.intellisoft.icl.auth.IclOHSAuth
import ke.intellisoft.icl.auth.InsufficientRoleException
import ke.intellisoft.icl.auth.InvalidTokenException
import ke.intellisoft.icl.auth.SessionRevokedException
import ke.intellisoft.icl.auth.TokenReuseDetectedException
import ke.intellisoft.icl.auth.audit.AccountLockedException
import ke.intellisoft.icl.auth.client.InvalidCredentialsException
import ke.intellisoft.icl.auth.config.AuthConfig
import ke.intellisoft.icl.auth.model.*
import ke.intellisoft.icl.auth.security.JwtVerifier
import ke.intellisoft.icl.auth.policy.AudiencePolicy
import java.util.UUID

/**
 * Single composition point for the auth module - one `Application.module()` wiring up
 * config, auth, and every route. Every route here is a thin HTTP adapter over [auth]; the
 * actual orchestration (Keycloak calls, lockout/audit/session-rotation logic) lives in
 * [IclOHSAuth] so it's shared verbatim with in-process (non-HTTP) callers of the library.
 *
 * See README for the couple of pieces still stubbed (password change / reset persistence).
 */
fun Application.authModule(
    config: AuthConfig,
    auth: IclOHSAuth,
    jwtVerifier: JwtVerifier = JwtVerifier(config)
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
        exception<TokenReuseDetectedException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("token_reuse_detected", cause.message ?: "Token reuse detected."))
        }
        exception<SessionRevokedException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("session_revoked", cause.message ?: "Session revoked."))
        }
        exception<InvalidTokenException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token", cause.message ?: "Invalid or expired token."))
        }
        exception<InsufficientRoleException> { call, cause ->
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("insufficient_role", cause.message ?: "Insufficient role."))
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
                val token = auth.login(
                    username = request.username,
                    password = request.password,
                    ip = call.request.origin.remoteHost,
                    userAgent = call.request.headers[HttpHeaders.UserAgent]
                )
                call.respond(token)
            }

            post("/refresh") {
                val request = call.receive<RefreshRequest>()
                val token = auth.refresh(
                    refreshToken = request.refreshToken,
                    ip = call.request.origin.remoteHost,
                    userAgent = call.request.headers[HttpHeaders.UserAgent]
                )
                call.respond(token)
            }

            post("/introspect") {
                // Outside the `keycloak-jwt` auth plugin by design (Section 12: a downstream
                // service posts a bearer token here rather than needing its own JWT stack).
                val request = call.receive<IntrospectRequest>()
                call.respond(auth.introspect(request.token))
            }

            authenticate("keycloak-jwt") {
                get("/profile") {
                    call.respond(auth.profile(call.bearerToken()))
                }

                get("/roles") { call.respond(auth.roles()) }

                post("/logout") {
                    val request = call.receive<RefreshRequest>()
                    auth.logout(request.refreshToken, ip = call.request.origin.remoteHost)
                    call.respond(HttpStatusCode.NoContent)
                }

                get("/sessions") {
                    call.respond(auth.sessions(call.bearerToken()))
                }

                get("/audit") {
                    val userIdFilter = call.request.queryParameters["userId"]?.let { UUID.fromString(it) }
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                    val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                    call.respond(auth.auditLog(call.bearerToken(), userIdFilter, limit, offset))
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

/** The raw bearer token string already verified by the `keycloak-jwt` auth plugin above. */
private fun ApplicationCall.bearerToken(): String =
    request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        ?: throw InvalidTokenException()
