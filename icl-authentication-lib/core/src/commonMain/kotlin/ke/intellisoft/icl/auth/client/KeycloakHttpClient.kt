package ke.intellisoft.icl.auth.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import ke.intellisoft.icl.auth.config.AuthConfig
import ke.intellisoft.icl.auth.model.TokenResponse

/**
 * Thin wrapper over the project's own Keycloak realm token endpoint. Pure Ktor Client +
 * kotlinx.serialization - no JVM-only dependency here, so this class is reusable as-is
 * from a future Android/iOS client module, not just the JVM server.
 *
 * The module never stores credentials; every call is forwarded straight through and
 * Keycloak remains the single source of truth for password verification.
 */
class KeycloakHttpClient(
    private val config: AuthConfig,
    private val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
) {
    suspend fun passwordGrant(username: String, password: String): TokenResponse =
        tokenRequest(
            "grant_type" to "password",
            "client_id" to config.clientId,
            "client_secret" to config.clientSecret,
            "username" to username,
            "password" to password
        )

    suspend fun refreshGrant(refreshToken: String): TokenResponse =
        tokenRequest(
            "grant_type" to "refresh_token",
            "client_id" to config.clientId,
            "client_secret" to config.clientSecret,
            "refresh_token" to refreshToken
        )

    suspend fun logout(refreshToken: String) {
        runCatching {
            httpClient.submitForm(
                url = config.logoutEndpoint,
                formParameters = Parameters.build {
                    append("client_id", config.clientId)
                    append("client_secret", config.clientSecret)
                    append("refresh_token", refreshToken)
                }
            )
        }
        // Best-effort: local session/audit revocation proceeds even if Keycloak has
        // already expired the session server-side.
    }

    private suspend fun tokenRequest(vararg params: Pair<String, String>): TokenResponse {
        val response: HttpResponse = httpClient.submitForm(
            url = config.tokenEndpoint,
            formParameters = Parameters.build { params.forEach { (k, v) -> append(k, v) } }
        )
        if (!response.status.isSuccess()) {
            throw InvalidCredentialsException("Keycloak token endpoint returned ${response.status}")
        }
        return response.body()
    }
}

class InvalidCredentialsException(message: String) : RuntimeException(message)
