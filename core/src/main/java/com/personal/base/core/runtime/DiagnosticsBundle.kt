package com.personal.base.core.runtime

import android.content.Context
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 1-tap ZIP export utility bundling logs, platform metrics, config, and audit trail for diagnostics.
 */
class DiagnosticsBundle(
    private val context: Context,
    private val logPersistence: LogPersistence,
    private val metricsHealthEndpoint: MetricsHealthEndpoint,
    private val backupRestore: BackupRestore,
    private val logger: PlatformLogger
) {
    suspend fun generateDiagnosticsZip(): File = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(context.cacheDir, "android_star_diagnostics_$timestamp.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // 1. Logs
            val logs = logPersistence.exportCombinedLogs()
            zos.putNextEntry(ZipEntry("logs.txt"))
            zos.write(logs.toByteArray())
            zos.closeEntry()

            // 2. Metrics
            val metricsJson = metricsHealthEndpoint.getMetricsJson()
            zos.putNextEntry(ZipEntry("metrics.json"))
            zos.write(metricsJson.toByteArray())
            zos.closeEntry()

            // 3. Backup Config
            val backupJson = backupRestore.createBackupJson()
            zos.putNextEntry(ZipEntry("backup_config.json"))
            zos.write(backupJson.toByteArray())
            zos.closeEntry()
        }

        logger.i("DiagnosticsBundle", "Exported diagnostics bundle: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
        zipFile
    }
}
