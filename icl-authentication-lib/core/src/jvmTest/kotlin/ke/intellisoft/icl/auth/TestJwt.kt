package ke.intellisoft.icl.auth

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * A local RSA keypair + matching fake [JwkProvider] so tests can produce genuinely
 * signature-verifiable JWTs without a real Keycloak/JWKS endpoint - the exact trick
 * [ke.intellisoft.icl.auth.security.JwtVerifier]'s test-friendly constructor is meant for.
 */
object TestJwt {
    const val KEY_ID = "test-key"

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    fun jwkProvider(): JwkProvider {
        val publicKey = keyPair.public as RSAPublicKey
        val jwk = Jwk.fromValues(
            mapOf(
                "kid" to KEY_ID,
                "kty" to "RSA",
                "alg" to "RS256",
                "use" to "sig",
                "n" to publicKey.modulus.toBase64Url(),
                "e" to publicKey.publicExponent.toBase64Url()
            )
        )
        return JwkProvider { jwk }
    }

    fun signedToken(
        subject: String = UUID.randomUUID().toString(),
        sid: String = UUID.randomUUID().toString(),
        jti: String = UUID.randomUUID().toString(),
        issuer: String = "http://test-issuer/realms/icl-realm",
        audience: String = "icl-backend",
        roles: List<String> = listOf("FACILITY_NURSE"),
        username: String = "demo.nurse",
        expiresAt: Instant = Instant.now().plusSeconds(3600)
    ): String {
        val algorithm = Algorithm.RSA256(keyPair.public as RSAPublicKey, keyPair.private as RSAPrivateKey)
        return JWT.create()
            .withKeyId(KEY_ID)
            .withSubject(subject)
            .withClaim("sid", sid)
            .withJWTId(jti)
            .withIssuer(issuer)
            .withAudience(audience)
            .withClaim("realm_access", mapOf("roles" to roles))
            .withClaim("preferred_username", username)
            .withExpiresAt(Date.from(expiresAt))
            .sign(algorithm)
    }

    private fun BigInteger.toBase64Url(): String {
        var bytes = toByteArray()
        if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes = bytes.copyOfRange(1, bytes.size)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
