package ke.intellisoft.icl.auth.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed table definitions mirroring the ohs_auth schema. Migrations themselves are
 * plain SQL run by Flyway (see server/src/main/resources/db/migration) - Exposed here is
 * used only as a typed query layer, not as a schema-generation tool, so the SQL and the
 * Kotlin table objects must be kept in sync by hand.
 */
object RefreshTokenFamilies : Table("ohs_auth.refresh_token_family") {
    val familyId = uuid("family_id")
    val userId = uuid("user_id")
    val currentToken = uuid("current_token")
    val issuedAt = timestamp("issued_at")
    val expiresAt = timestamp("expires_at")
    val revoked = bool("revoked")
    val revokedReason = text("revoked_reason").nullable()
    override val primaryKey = PrimaryKey(familyId)
}

object Sessions : Table("ohs_auth.sessions") {
    val sessionId = uuid("session_id")
    val userId = uuid("user_id")
    val familyId = uuid("family_id")
    val device = text("device").nullable()
    val ipAddress = text("ip_address").nullable()
    val createdAt = timestamp("created_at")
    val lastSeenAt = timestamp("last_seen_at")
    val revoked = bool("revoked")
    override val primaryKey = PrimaryKey(sessionId)
}

object LoginAttempts : Table("ohs_auth.login_attempts") {
    val id = long("id").autoIncrement()
    val username = text("username")
    val realm = text("realm")
    val ipAddress = text("ip_address").nullable()
    val succeeded = bool("succeeded")
    val attemptedAt = timestamp("attempted_at")
    override val primaryKey = PrimaryKey(id)
}

object AuditLog : Table("ohs_auth.audit_log") {
    val id = long("id").autoIncrement()
    val event = text("event")
    val userId = uuid("user_id").nullable()
    val realm = text("realm")
    val ipAddress = text("ip_address").nullable()
    val userAgent = text("user_agent").nullable()
    val metadata = text("metadata").nullable() // stores a JSON string; no JSON-native querying needed today
    val prevHash = text("prev_hash").nullable()
    val hash = text("hash")
    val occurredAt = timestamp("occurred_at")
    override val primaryKey = PrimaryKey(id)
}
