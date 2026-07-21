package ke.intellisoft.icl.auth.security

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.Payload
import ke.intellisoft.icl.auth.config.AuthConfig
import java.net.URL
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/**
 * Supplies the cached JWK provider + issuer that Ktor's built-in `jwt { verifier(jwkProvider,
 * issuer) }` uses to verify a token's signature. Signature verification itself is delegated
 * to Ktor/java-jwt; this class exists so the JWKS endpoint and issuer are resolved from
 * AuthConfig in exactly one place.
 *
 * The primary constructor takes the provider/issuer directly so tests can supply a fake
 * JwkProvider + local RSA keypair without a real JWKS endpoint; the AuthConfig-based
 * secondary constructor is what production code (AuthModule's default) actually uses.
 */
class JwtVerifier(val jwkProvider: JwkProvider, val issuer: String) {

    constructor(config: AuthConfig) : this(
        jwkProvider = JwkProviderBuilder(URL(config.jwksUri))
            .cached(10, 24, TimeUnit.HOURS)
            .rateLimited(10, 1, TimeUnit.MINUTES)
            .build(),
        issuer = config.issuerUri
    )

    /** Unverified decode - Keycloak's own token-endpoint call remains the real validity check. */
    fun decodeUnverified(token: String): DecodedJWT = JWT.decode(token)

    /** Full signature + issuer verification against this realm's JWKS - null if invalid/expired. */
    fun verify(token: String): DecodedJWT? = runCatching {
        val unverified = JWT.decode(token)
        val jwk = jwkProvider.get(unverified.keyId)
        val algorithm = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)
        JWT.require(algorithm).withIssuer(issuer).build().verify(token)
    }.getOrNull()

    fun rolesOf(payload: Payload): Set<String> {
        val realmAccess = payload.getClaim("realm_access").asMap() ?: return emptySet()
        @Suppress("UNCHECKED_CAST")
        val roles = realmAccess["roles"] as? List<String> ?: return emptySet()
        return roles.toSet()
    }
}
