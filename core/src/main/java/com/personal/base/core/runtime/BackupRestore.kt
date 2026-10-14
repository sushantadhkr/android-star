package com.personal.base.core.runtime

import android.content.Context
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Platform configuration and state export and import utility.
 */
class BackupRestore(
    private val context: Context,
    private val dataStoreSettings: DataStoreSettings,
    private val localDatabase: LocalDatabase,
    private val logger: PlatformLogger
) {
    suspend fun createBackupJson(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("version", 1)
        root.put("timestamp", System.currentTimeMillis())

        val settingsObj = JSONObject()
        dataStoreSettings.settingsMap.value.forEach { (k, v) ->
            settingsObj.put(k, v)
        }
        root.put("settings", settingsObj)

        val audits = localDatabase.getRecentAudits(50)
        root.put("auditCount", audits.size)

        logger.i("BackupRestore", "Generated platform backup JSON.")
        root.toString(2)
    }

    suspend fun restoreFromJson(backupJson: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(backupJson)
            val settings = root.optJSONObject("settings")
            if (settings != null) {
                val keys = settings.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = settings.get(k)
                    when (v) {
                        is Boolean -> dataStoreSettings.setBoolean(k, v)
                        is Int -> dataStoreSettings.setInt(k, v)
                        is Long -> dataStoreSettings.setLong(k, v)
                        else -> dataStoreSettings.setString(k, v.toString())
                    }
                }
            }
            logger.i("BackupRestore", "Successfully restored platform state from backup.")
            true
        } catch (e: Exception) {
            logger.e("BackupRestore", "Failed to restore backup: ${e.message}", e.message)
            false
        }
    }
}
