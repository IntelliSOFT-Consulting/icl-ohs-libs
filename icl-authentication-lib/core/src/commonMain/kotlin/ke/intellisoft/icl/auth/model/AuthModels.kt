package ke.intellisoft.icl.auth.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_expires_in") val refreshExpiresIn: Long,
    val roles: List<String> = emptyList(),
    val realm: String? = null
)

@Serializable
data class UserProfile(
    val sub: String,
    val username: String,
    val realm: String,
    val roles: List<String>,
    @SerialName("facility_id") val facilityId: String? = null,
    @SerialName("org_hierarchy") val orgHierarchy: List<String> = emptyList()
)

@Serializable
data class SessionDto(
    val sessionId: String,
    val device: String? = null,
    val ip: String? = null,
    val lastSeenAt: String,
    val current: Boolean
)

@Serializable
data class PasswordResetRequestDto(val username: String)

@Serializable
data class PasswordResetConfirmDto(
    val username: String,
    val code: String,
    @SerialName("new_password") val newPassword: String
)

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class ErrorResponse(val error: String, val message: String)

@Serializable
data class IntrospectRequest(val token: String)

@Serializable
data class IntrospectResponse(val active: Boolean, val sub: String? = null, val roles: List<String> = emptyList())

@Serializable
data class AuditLogEntryDto(
    val id: Long,
    val event: String,
    val userId: String? = null,
    val realm: String,
    val ipAddress: String? = null,
    val occurredAt: String
)
