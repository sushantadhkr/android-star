package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import java.util.concurrent.ConcurrentHashMap

data class SyncRecord(
    val entityId: String,
    val version: Long,
    val dataJson: String,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Multi-peer data synchronization engine with conflict resolution based on vector versions.
 */
class SyncEngine(
    private val logger: PlatformLogger
) {
    private val store = ConcurrentHashMap<String, SyncRecord>()

    fun applyRemoteRecord(remote: SyncRecord): Boolean {
        val local = store[remote.entityId]
        return if (local == null || remote.version > local.version) {
            store[remote.entityId] = remote
            logger.i("SyncEngine", "Applied remote sync update for entity ${remote.entityId} (v${remote.version})")
            true
        } else {
            logger.w("SyncEngine", "Conflict resolved: keeping local entity ${remote.entityId} (local v${local.version} >= remote v${remote.version})")
            false
        }
    }

    fun putLocalRecord(entityId: String, dataJson: String): SyncRecord {
        val currentVersion = store[entityId]?.version ?: 0L
        val updated = SyncRecord(entityId, currentVersion + 1L, dataJson)
        store[entityId] = updated
        logger.i("SyncEngine", "Updated local entity $entityId (v${updated.version})")
        return updated
    }

    fun getAllRecords(): List<SyncRecord> = store.values.toList()
}
