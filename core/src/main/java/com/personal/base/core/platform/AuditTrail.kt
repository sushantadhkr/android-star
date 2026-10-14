package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.runtime.LocalDatabase
import java.security.MessageDigest

/**
 * Append-only persistent execution ledger with SHA-256 hash chaining for tamper evidence.
 */
class AuditTrail(
    private val localDatabase: LocalDatabase,
    private val logger: PlatformLogger
) {
    private var lastHash: String = "0000000000000000000000000000000000000000000000000000000000000000"

    suspend fun record(peerId: String?, action: String, status: String, details: String?) {
        val now = System.currentTimeMillis()
        val dataToHash = "$lastHash:$now:$peerId:$action:$status:$details"
        val digest = MessageDigest.getInstance("SHA-256")
        val currentHash = digest.digest(dataToHash.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        lastHash = currentHash

        localDatabase.insertAudit(peerId, action, status, details, currentHash)
        logger.i("AuditTrail", "Audit recorded: action=$action, status=$status, peer=$peerId")
    }

    suspend fun getRecentAuditEntries(limit: Int = 100): List<Map<String, Any>> {
        return localDatabase.getRecentAudits(limit)
    }
}
