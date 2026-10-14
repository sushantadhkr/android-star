package com.personal.base.core.runtime

import android.content.Context
import com.personal.base.core.logging.LogEntry
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-backed rotating log storage with auto-rollover, size bounds, and export.
 */
class LogPersistence(
    private val context: Context,
    private val logger: PlatformLogger,
    private val maxFileSizeBytes: Long = 2 * 1024 * 1024L // 2MB
) {
    private val logDir = File(context.filesDir, "logs").apply { mkdirs() }
    private val activeLogFile = File(logDir, "android_star_active.log")

    suspend fun persistLog(entry: LogEntry) = withContext(Dispatchers.IO) {
        try {
            rotateIfNecessary()
            FileWriter(activeLogFile, true).use { writer ->
                val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(entry.timestamp))
                writer.append("[$dateStr] [${entry.level}] [${entry.tag}] ${entry.message}")
                if (entry.details != null) {
                    writer.append(" | Details: ${entry.details}")
                }
                writer.append("\n")
            }
        } catch (e: Exception) {
            // Avoid recursion
        }
    }

    private fun rotateIfNecessary() {
        if (activeLogFile.exists() && activeLogFile.length() > maxFileSizeBytes) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val rotated = File(logDir, "log_$timestamp.log")
            activeLogFile.renameTo(rotated)

            // Prune old logs if more than 5
            val allLogs = logDir.listFiles { _, name -> name.endsWith(".log") }?.toList() ?: emptyList()
            if (allLogs.size > 5) {
                allLogs.sortedBy { it.lastModified() }
                    .take(allLogs.size - 5)
                    .forEach { it.delete() }
            }
        }
    }

    suspend fun exportCombinedLogs(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val files = logDir.listFiles { _, name -> name.endsWith(".log") }?.sortedBy { it.lastModified() } ?: emptyList()
        for (f in files) {
            sb.append("=== LOG FILE: ${f.name} ===\n")
            sb.append(f.readText())
            sb.append("\n\n")
        }
        sb.toString()
    }
}
