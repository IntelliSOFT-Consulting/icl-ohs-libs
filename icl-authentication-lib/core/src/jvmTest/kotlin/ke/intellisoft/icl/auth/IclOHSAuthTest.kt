package ke.intellisoft.icl.auth

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import ke.intellisoft.icl.auth.audit.AccountLockedException
import ke.intellisoft.icl.auth.audit.AuditLogService
import ke.intellisoft.icl.auth.audit.LoginAttemptService
import ke.intellisoft.icl.auth.client.InvalidCredentialsException
import ke.intellisoft.icl.auth.client.KeycloakAdminClient
import ke.intellisoft.icl.auth.client.KeycloakHttpClient
import ke.intellisoft.icl.auth.client.UsernameAlreadyExistsException
import ke.intellisoft.icl.auth.config.AuthConfig
import ke.intellisoft.icl.auth.registration.OtpNotifier
import ke.intellisoft.icl.auth.registration.RegistrationOtpService
import ke.intellisoft.icl.auth.security.JwtVerifier
import ke.intellisoft.icl.auth.session.RefreshTokenFamilyService
import ke.intellisoft.icl.auth.session.RotationResult
import ke.intellisoft.icl.auth.model.TokenResponse
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IclOHSAuthTest {

    private val config = AuthConfig(
        realm = "icl-realm",
        authServerUrl = "http://test-issuer",
        clientId = "icl-backend",
        clientSecret = "test-secret",
        lockoutMaxAttempts = 3,
        lockoutWindowMinutes = 15
    )

    @BeforeTest
    fun setUp() {
        TestDatabase.ensureReady()
        TestDatabase.clearAll()
    }

    private fun authWith(
        keycloak: KeycloakHttpClient,
        keycloakAdmin: KeycloakAdminClient = fakeKeycloakAdmin(),
        otpNotifier: OtpNotifier = OtpNotifier { _, _ -> }
    ) = IclOHSAuth(
        config = config,
        keycloak = keycloak,
        jwtVerifier = JwtVerifier(TestJwt.jwkProvider(), config.issuerUri),
        auditLogService = AuditLogService(),
        loginAttempts = LoginAttemptService(config.lockoutMaxAttempts, config.lockoutWindowMinutes),
        refreshTokenFamilies = RefreshTokenFamilyService(),
        keycloakAdmin = keycloakAdmin,
        registrationOtps = RegistrationOtpService(config.otpExpiryMinutes, config.otpMaxAttempts, config.otpLength),
        otpNotifier = otpNotifier
    )

    /** Captures the OTP [IclOHSAuth.register] would otherwise send out, so tests can complete the flow. */
    private class CapturingOtpNotifier : OtpNotifier {
        var lastOtp: String? = null
            private set

        override fun send(destination: String, otp: String) {
            lastOtp = otp
        }
    }

    /** Fake Keycloak Admin REST API - createUser succeeds unless [createUserConflicts]. */
    private fun fakeKeycloakAdmin(createUserConflicts: Boolean = false): KeycloakAdminClient {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/token") -> respond(
                    """{"access_token":"admin-token"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                request.method == HttpMethod.Post && path.endsWith("/users") ->
                    if (createUserConflicts) respond("", HttpStatusCode.Conflict)
                    else respond(
                        "",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.Location, listOf("${config.authServerUrl}/admin/realms/${config.realm}/users/${UUID.randomUUID()}"))
                    )
                request.method == HttpMethod.Get && path.contains("/roles/") -> respond(
                    """{"id":"role-id","name":"${path.substringAfterLast("/")}"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                request.method == HttpMethod.Get -> respond(
                    """{"id":"stub","enabled":false}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                else -> respond("", HttpStatusCode.NoContent)
            }
        }
        return KeycloakAdminClient(config, HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } })
    }

    private fun keycloakReturning(vararg tokenResponses: TokenResponse): KeycloakHttpClient {
        val queue = ArrayDeque(tokenResponses.toList())
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/token")) {
                respond(
                    Json.encodeToString(TokenResponse.serializer(), queue.removeFirst()),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                respond("", HttpStatusCode.OK)
            }
        }
        return KeycloakHttpClient(config, HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } })
    }

    private fun keycloakRejectingCredentials(): KeycloakHttpClient {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        return KeycloakHttpClient(config, HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } })
    }

    private fun tokenPair(familyId: String, jti: String, subject: String) = TokenResponse(
        accessToken = TestJwt.signedToken(subject = subject, roles = listOf("FACILITY_NURSE")),
        refreshToken = TestJwt.signedToken(subject = subject, sid = familyId, jti = jti),
        tokenType = "Bearer",
        expiresIn = 300,
        refreshExpiresIn = 3600
    )

    @Test
    fun `successful login creates a token family and session`() {
        val familyId = UUID.randomUUID()
        val jti = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val auth = authWith(keycloakReturning(tokenPair(familyId.toString(), jti.toString(), userId.toString())))

        val token = auth.login("demo.nurse", "password", ip = "127.0.0.1")

        assertEquals(config.realm, token.realm)
        assertEquals(RotationResult.Valid, RefreshTokenFamilyService().checkRotation(familyId, jti))
        assertEquals(1, RefreshTokenFamilyService().listSessions(userId).size)
    }

    @Test
    fun `login lockout after max failed attempts throws AccountLockedException`() {
        val auth = authWith(keycloakRejectingCredentials())

        repeat(config.lockoutMaxAttempts) {
            assertFailsWith<InvalidCredentialsException> { auth.login("demo.nurse", "wrong") }
        }
        assertFailsWith<AccountLockedException> { auth.login("demo.nurse", "wrong") }
    }

    @Test
    fun `refresh happy path completes rotation`() {
        val familyId = UUID.randomUUID()
        val firstJti = UUID.randomUUID()
        val secondJti = UUID.randomUUID()
        val userId = UUID.randomUUID()

        val loginAuth = authWith(keycloakReturning(tokenPair(familyId.toString(), firstJti.toString(), userId.toString())))
        val loginToken = loginAuth.login("demo.nurse", "password")

        val refreshAuth = authWith(keycloakReturning(tokenPair(familyId.toString(), secondJti.toString(), userId.toString())))
        val refreshed = refreshAuth.refresh(loginToken.refreshToken)

        assertEquals(config.realm, refreshed.realm)
        assertEquals(RotationResult.Valid, RefreshTokenFamilyService().checkRotation(familyId, secondJti))
    }

    @Test
    fun `replaying an already-rotated refresh token is detected as reuse and revokes the family`() {
        val familyId = UUID.randomUUID()
        val firstJti = UUID.randomUUID()
        val secondJti = UUID.randomUUID()
        val userId = UUID.randomUUID()

        val loginAuth = authWith(keycloakReturning(tokenPair(familyId.toString(), firstJti.toString(), userId.toString())))
        val loginToken = loginAuth.login("demo.nurse", "password")

        val refreshAuth = authWith(keycloakReturning(tokenPair(familyId.toString(), secondJti.toString(), userId.toString())))
        refreshAuth.refresh(loginToken.refreshToken)

        // firstJti has already been rotated away - replaying it is reuse.
        val replayAuth = authWith(keycloakReturning())
        assertFailsWith<TokenReuseDetectedException> { replayAuth.refresh(loginToken.refreshToken) }
        assertEquals(RotationResult.AlreadyRevoked, RefreshTokenFamilyService().checkRotation(familyId, secondJti))
    }

    @Test
    fun `profile, sessions and auditLog reject an invalid token`() {
        val auth = authWith(keycloakReturning())

        assertFailsWith<InvalidTokenException> { auth.profile("not-a-jwt") }
        assertFailsWith<InvalidTokenException> { auth.sessions("not-a-jwt") }
        assertFailsWith<InvalidTokenException> { auth.auditLog("not-a-jwt") }
    }

    @Test
    fun `profile returns claims from a validly signed token`() {
        val auth = authWith(keycloakReturning())
        val userId = UUID.randomUUID().toString()
        val token = TestJwt.signedToken(subject = userId, issuer = config.issuerUri, audience = config.clientId, roles = listOf("MOH_ADMIN"), username = "demo.admin")

        val profile = auth.profile(token)

        assertEquals(userId, profile.sub)
        assertEquals("demo.admin", profile.username)
        assertEquals(listOf("MOH_ADMIN"), profile.roles)
    }

    @Test
    fun `auditLog rejects a caller without MOH_ADMIN or SUPER_ADMIN`() {
        val auth = authWith(keycloakReturning())
        val token = TestJwt.signedToken(issuer = config.issuerUri, audience = config.clientId, roles = listOf("FACILITY_NURSE"))

        assertFailsWith<InsufficientRoleException> { auth.auditLog(token) }
    }

    @Test
    fun `auditLog succeeds for a caller with MOH_ADMIN`() {
        val auth = authWith(keycloakReturning())
        val token = TestJwt.signedToken(issuer = config.issuerUri, audience = config.clientId, roles = listOf("MOH_ADMIN"))

        assertEquals(emptyList(), auth.auditLog(token))
    }

    @Test
    fun `register then verifyRegistration enables the account`() {
        val notifier = CapturingOtpNotifier()
        val auth = authWith(keycloakReturning(), otpNotifier = notifier)

        auth.register("new.nurse", "password123", "new.nurse@icl.local", "New", "Nurse")

        val otp = requireNotNull(notifier.lastOtp)
        auth.verifyRegistration("new.nurse", otp)   // does not throw
    }

    @Test
    fun `register rejects a username that already exists`() {
        val auth = authWith(keycloakReturning(), fakeKeycloakAdmin(createUserConflicts = true))

        assertFailsWith<UsernameAlreadyExistsException> {
            auth.register("demo.nurse", "password123", "demo.nurse@icl.local", "Demo", "Nurse")
        }
    }

    @Test
    fun `verifyRegistration rejects a wrong OTP`() {
        val auth = authWith(keycloakReturning())
        auth.register("new.nurse2", "password123", "new.nurse2@icl.local", "New", "Nurse")

        assertFailsWith<InvalidOtpException> { auth.verifyRegistration("new.nurse2", "000000") }
    }

    @Test
    fun `updateAccount changes profile fields and password`() {
        val userId = UUID.randomUUID().toString()
        val token = TestJwt.signedToken(subject = userId, issuer = config.issuerUri, audience = config.clientId, roles = listOf("FACILITY_NURSE"), username = "demo.nurse")
        // the password-change path re-verifies currentPassword via a password grant before resetting it
        val auth = authWith(keycloakReturning(tokenPair(UUID.randomUUID().toString(), UUID.randomUUID().toString(), userId)))

        val profile = auth.updateAccount(token, firstName = "Updated", currentPassword = "old-pass", newPassword = "new-pass")

        assertEquals(userId, profile.sub)
    }

    @Test
    fun `updateUserRoles rejects a caller without MOH_ADMIN or SUPER_ADMIN`() {
        val token = TestJwt.signedToken(issuer = config.issuerUri, audience = config.clientId, roles = listOf("FACILITY_NURSE"))
        val auth = authWith(keycloakReturning())

        assertFailsWith<InsufficientRoleException> {
            auth.updateUserRoles(token, UUID.randomUUID().toString(), addRoles = listOf("MOH_ADMIN"))
        }
    }

    @Test
    fun `updateUserRoles succeeds for a caller with MOH_ADMIN`() {
        val token = TestJwt.signedToken(issuer = config.issuerUri, audience = config.clientId, roles = listOf("MOH_ADMIN"))
        val auth = authWith(keycloakReturning())

        auth.updateUserRoles(token, UUID.randomUUID().toString(), addRoles = listOf("SUPER_ADMIN"))   // does not throw
    }
}
