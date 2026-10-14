package com.personal.base.core.runtime

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Robust zero-dependency SQLite-backed structured persistent database for
 * audit records, peer states, offline command outbox, and key-value state.
 */
class LocalDatabase(
    context: Context,
    private val logger: PlatformLogger
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS audit_trail (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                peer_id TEXT,
                command_action TEXT NOT NULL,
                status TEXT NOT NULL,
                details TEXT,
                hash TEXT NOT NULL
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS trusted_peers (
                peer_id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                public_key TEXT NOT NULL,
                paired_timestamp INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                allowed_capabilities TEXT NOT NULL
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS outbox_commands (
                command_id TEXT PRIMARY KEY,
                target_peer_id TEXT NOT NULL,
                action TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_timestamp INTEGER NOT NULL,
                retry_count INTEGER DEFAULT 0,
                next_retry_timestamp INTEGER NOT NULL
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS platform_state (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            );
            """.trimIndent()
        )

        logger.i("LocalDatabase", "Database created successfully with version $DATABASE_VERSION")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        logger.w("LocalDatabase", "Upgrading database from $oldVersion to $newVersion")
    }

    suspend fun insertAudit(peerId: String?, action: String, status: String, details: String?, hash: String) =
        withContext(Dispatchers.IO) {
            val cv = ContentValues().apply {
                put("timestamp", System.currentTimeMillis())
                put("peer_id", peerId ?: "local")
                put("command_action", action)
                put("status", status)
                put("details", details ?: "")
                put("hash", hash)
            }
            writableDatabase.insert("audit_trail", null, cv)
        }

    suspend fun getRecentAudits(limit: Int = 100): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            "SELECT * FROM audit_trail ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    mapOf(
                        "id" to cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        "timestamp" to cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                        "peerId" to cursor.getString(cursor.getColumnIndexOrThrow("peer_id")),
                        "action" to cursor.getString(cursor.getColumnIndexOrThrow("command_action")),
                        "status" to cursor.getString(cursor.getColumnIndexOrThrow("status")),
                        "details" to cursor.getString(cursor.getColumnIndexOrThrow("details")),
                        "hash" to cursor.getString(cursor.getColumnIndexOrThrow("hash"))
                    )
                )
            }
        }
        list
    }

    suspend fun setState(key: String, value: String) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("platform_state", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun getState(key: String): String? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            "platform_state",
            arrayOf("value"),
            "key = ?",
            arrayOf(key),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow("value"))
            } else null
        }
    }

    companion object {
        const val DATABASE_NAME = "android_star_platform.db"
        const val DATABASE_VERSION = 1
    }
}
