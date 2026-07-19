package ke.intellisoft.icl.auth

import ke.intellisoft.icl.auth.persistence.AuditLog
import ke.intellisoft.icl.auth.persistence.LoginAttempts
import ke.intellisoft.icl.auth.persistence.RefreshTokenFamilies
import ke.intellisoft.icl.auth.persistence.Sessions
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

private class KPostgresContainer : PostgreSQLContainer<KPostgresContainer>(DockerImageName.parse("postgres:16-alpine"))

/**
 * One shared Postgres container + Exposed connection for the whole `:core:jvmTest` suite.
 * Schema is generated straight from the Exposed table objects in `AuthTables.kt`
 * (`SchemaUtils.create`), not Flyway - this module has no dependency on `server`'s
 * migration resources, and Exposed can build compatible DDL from the same table
 * definitions the production code already uses.
 */
object TestDatabase {
    private val container = KPostgresContainer().apply { start() }

    init {
        Database.connect(
            url = container.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = container.username,
            password = container.password
        )
        transaction {
            exec("CREATE SCHEMA IF NOT EXISTS ohs_auth")
            SchemaUtils.create(RefreshTokenFamilies, Sessions, LoginAttempts, AuditLog)
        }
    }

    /** No-op - referencing this object is enough to trigger the lazy container/schema init above. */
    fun ensureReady() {}

    fun clearAll(): Unit = transaction {
        AuditLog.deleteAll()
        Sessions.deleteAll()
        RefreshTokenFamilies.deleteAll()
        LoginAttempts.deleteAll()
    }
}
