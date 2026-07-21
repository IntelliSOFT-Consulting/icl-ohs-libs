package ke.intellisoft.icl.auth.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ke.intellisoft.icl.auth.config.AuthConfig

/**
 * Talks to Keycloak's Admin REST API (create/update/enable users, reset passwords, manage
 * realm-role mappings) using the `icl-backend` client's own service account
 * (client-credentials grant) rather than a user's credentials. Pure Ktor Client, no
 * JVM-only dependency - reusable as-is wherever [KeycloakHttpClient] is.
 *
 * Fetches a fresh admin token per call rather than caching/refreshing one - simplest
 * correct thing today; revisit only if the extra round trip actually shows up as a
 * bottleneck.
 */
class KeycloakAdminClient(
    private val config: AuthConfig,
    private val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
) {

    suspend fun createUser(username: String, email: String, firstName: String, lastName: String, enabled: Boolean): String {
        val response = httpClient.post(config.adminUsersUri) {
            bearerAuth(adminToken())
            contentType(ContentType.Application.Json)
            setBody(CreateUserRequest(username, email, firstName, lastName, enabled))
        }
        if (response.status == HttpStatusCode.Conflict) {
            throw UsernameAlreadyExistsException(username)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Keycloak admin createUser returned ${response.status}")
        }
        val location = response.headers[HttpHeaders.Location]
            ?: throw IllegalStateException("Keycloak admin createUser response had no Location header")
        return location.substringAfterLast("/")
    }

    suspend fun setEnabled(userId: String, enabled: Boolean) {
        val representation = JsonObject(getUserRepresentation(userId) + ("enabled" to JsonPrimitive(enabled)))
        putUserRepresentation(userId, representation)
    }

    suspend fun updateProfile(userId: String, firstName: String?, lastName: String?, email: String?) {
        val updates = buildMap {
            firstName?.let { put("firstName", JsonPrimitive(it)) }
            lastName?.let { put("lastName", JsonPrimitive(it)) }
            email?.let { put("email", JsonPrimitive(it)) }
        }
        putUserRepresentation(userId, JsonObject(getUserRepresentation(userId) + updates))
    }

    suspend fun resetPassword(userId: String, newPassword: String) {
        val response = httpClient.put("${config.adminUsersUri}/$userId/reset-password") {
            bearerAuth(adminToken())
            contentType(ContentType.Application.Json)
            setBody(CredentialRepresentation(value = newPassword))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Keycloak admin resetPassword returned ${response.status}")
        }
    }

    suspend fun addRealmRole(userId: String, role: String) {
        val roleRepresentation = getRoleRepresentation(role)
        val response = httpClient.post("${config.adminUsersUri}/$userId/role-mappings/realm") {
            bearerAuth(adminToken())
            contentType(ContentType.Application.Json)
            setBody(listOf(roleRepresentation))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Keycloak admin addRealmRole returned ${response.status}")
        }
    }

    suspend fun removeRealmRole(userId: String, role: String) {
        val roleRepresentation = getRoleRepresentation(role)
        val response = httpClient.delete("${config.adminUsersUri}/$userId/role-mappings/realm") {
            bearerAuth(adminToken())
            contentType(ContentType.Application.Json)
            setBody(listOf(roleRepresentation))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Keycloak admin removeRealmRole returned ${response.status}")
        }
    }

    private suspend fun getUserRepresentation(userId: String): JsonObject =
        httpClient.get("${config.adminUsersUri}/$userId") { bearerAuth(adminToken()) }.body()

    private suspend fun putUserRepresentation(userId: String, representation: JsonObject) {
        val response = httpClient.put("${config.adminUsersUri}/$userId") {
            bearerAuth(adminToken())
            contentType(ContentType.Application.Json)
            setBody(representation)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Keycloak admin update user returned ${response.status}")
        }
    }

    private suspend fun getRoleRepresentation(role: String): RoleRepresentation =
        httpClient.get("${config.adminRolesUri}/$role") { bearerAuth(adminToken()) }.body()

    private suspend fun adminToken(): String {
        val response = httpClient.submitForm(
            url = config.tokenEndpoint,
            formParameters = Parameters.build {
                append("grant_type", "client_credentials")
                append("client_id", config.clientId)
                append("client_secret", config.clientSecret)
            }
        )
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Keycloak admin token request returned ${response.status}")
        }
        return response.body<AdminTokenResponse>().accessToken
    }
}

class UsernameAlreadyExistsException(username: String) : RuntimeException("Username '$username' already exists")

@Serializable
private data class CreateUserRequest(
    val username: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val enabled: Boolean,
    val emailVerified: Boolean = false
)

@Serializable
private data class CredentialRepresentation(
    val type: String = "password",
    val value: String,
    val temporary: Boolean = false
)

@Serializable
private data class RoleRepresentation(val id: String, val name: String)

@Serializable
private data class AdminTokenResponse(@SerialName("access_token") val accessToken: String)
