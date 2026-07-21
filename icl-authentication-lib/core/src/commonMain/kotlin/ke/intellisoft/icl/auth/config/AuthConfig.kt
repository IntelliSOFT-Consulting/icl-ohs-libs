package ke.intellisoft.icl.auth.config

/**
 * Everything the module needs to talk to one project's own Keycloak realm. Always
 * constructed from environment variables by the host application - never hardcoded.
 * This is intentionally a plain data class with no framework annotations so it is usable
 * from commonMain, a future Android/iOS client, and the JVM server alike.
 */
data class AuthConfig(
    val realm: String,
    val authServerUrl: String,
    val clientId: String,
    val clientSecret: String,
    val defaultRoles: List<String> = listOf("MOH_ADMIN", "FACILITY_NURSE", "SUPER_ADMIN"),
    val lockoutMaxAttempts: Int = 5,
    val lockoutWindowMinutes: Long = 15,
    val otpLength: Int = 6,
    val otpExpiryMinutes: Long = 10,
    val otpMaxAttempts: Int = 5
) {
    val issuerUri: String get() = "$authServerUrl/realms/$realm"
    val tokenEndpoint: String get() = "$issuerUri/protocol/openid-connect/token"
    val jwksUri: String get() = "$issuerUri/protocol/openid-connect/certs"
    val logoutEndpoint: String get() = "$issuerUri/protocol/openid-connect/logout"
    val adminUsersUri: String get() = "$authServerUrl/admin/realms/$realm/users"
    val adminRolesUri: String get() = "$authServerUrl/admin/realms/$realm/roles"

    companion object {
        /** Fails fast with a clear message if any required env key is missing or blank. */
        fun fromEnv(env: Map<String, String>): AuthConfig {
            fun require(key: String) = env[key]?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(
                    "Missing required env key: $key - copy .env.sample to .env and fill in real values."
                )
            return AuthConfig(
                realm = require("KEYCLOAK_REALM"),
                authServerUrl = require("KEYCLOAK_AUTH_SERVER_URL"),
                clientId = require("KEYCLOAK_CLIENT_ID"),
                clientSecret = require("KEYCLOAK_CLIENT_SECRET")
            )
        }
    }
}
