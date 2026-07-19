package ke.intellisoft.icl.auth.audit

import ke.intellisoft.icl.auth.model.AuditLogEntryDto
import ke.intellisoft.icl.auth.persistence.AuditLog
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Hash-chain design: every row's hash is SHA-256(prevHash + canonical fields), so
 * editing or deleting a row breaks every hash computed after it.
 */
class AuditLogService {

    fun record(
        event: String,
        userId: UUID?,
        realm: String,
        ipAddress: String? = null,
        userAgent: String? = null,
        metadataJson: String = "{}"
    ): Unit = transaction {
        val previousHash = AuditLog
            .select(AuditLog.hash)
            .orderBy(AuditLog.id, SortOrder.DESC)
            .limit(1)
            .map { it[AuditLog.hash] }
            .firstOrNull() ?: ""

        val occurredAt = Instant.now()
        val hash = computeHash(previousHash, event, userId, realm, ipAddress, metadataJson, occurredAt)

        AuditLog.insert {
            it[AuditLog.event] = event
            it[AuditLog.userId] = userId
            it[AuditLog.realm] = realm
            it[AuditLog.ipAddress] = ipAddress
            it[AuditLog.userAgent] = userAgent
            it[AuditLog.metadata] = metadataJson
            it[AuditLog.prevHash] = previousHash.ifEmpty { null }
            it[AuditLog.hash] = hash
            it[AuditLog.occurredAt] = occurredAt
        }
    }

    /** Paginated, optionally filtered by [userId]; [limit] is hard-capped by the caller. */
    fun list(userId: UUID?, limit: Int, offset: Long): List<AuditLogEntryDto> = transaction {
        var query = AuditLog.selectAll()
        if (userId != null) query = query.andWhere { AuditLog.userId eq userId }
        query.orderBy(AuditLog.occurredAt, SortOrder.DESC)
            .limit(limit, offset)
            .map {
                AuditLogEntryDto(
                    id = it[AuditLog.id],
                    event = it[AuditLog.event],
                    userId = it[AuditLog.userId]?.toString(),
                    realm = it[AuditLog.realm],
                    ipAddress = it[AuditLog.ipAddress],
                    occurredAt = it[AuditLog.occurredAt].toString()
                )
            }
    }

    fun verifyChainIntegrity(): Boolean = transaction {
        var prevHash = ""
        AuditLog.selectAll().orderBy(AuditLog.id, SortOrder.ASC).forEach { row ->
            val expected = computeHash(
                prevHash, row[AuditLog.event], row[AuditLog.userId], row[AuditLog.realm],
                row[AuditLog.ipAddress], row[AuditLog.metadata] ?: "{}", row[AuditLog.occurredAt]
            )
            if (expected != row[AuditLog.hash]) return@transaction false
            prevHash = row[AuditLog.hash]
        }
        true
    }

    private fun computeHash(
        prevHash: String, event: String, userId: UUID?, realm: String,
        ipAddress: String?, metadataJson: String, occurredAt: Instant
    ): String {
        val canonical = prevHash + event + (userId?.toString() ?: "") + realm +
                (ipAddress ?: "") + metadataJson + occurredAt.toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
