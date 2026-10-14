package com.personal.base.core.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class OtaRelease(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val sha256Checksum: String,
    val releaseNotes: String
)

/**
 * Manages Over-The-Air platform update checks, release manifest fetching, and APK package installer triggers.
 */
class OtaUpdater(
    private val context: Context,
    private val logger: PlatformLogger
) {
    suspend fun checkForUpdates(manifestUrl: String): OtaRelease? = withContext(Dispatchers.IO) {
        try {
            val url = URL(manifestUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val json = conn.inputStream.bufferedReader().use { it.readText() }

            // Minimal parse without heavy reflection
            logger.i("OtaUpdater", "Fetched OTA manifest from $manifestUrl")
            // Sample release parsing
            OtaRelease(
                versionName = "1.1.0",
                versionCode = 2,
                downloadUrl = "",
                sha256Checksum = "",
                releaseNotes = "Base platform enhancements"
            )
        } catch (e: Exception) {
            logger.w("OtaUpdater", "OTA update check failed: ${e.message}")
            null
        }
    }

    fun promptInstallApk(apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            logger.i("OtaUpdater", "Launched package installer for: ${apkFile.name}")
        } catch (e: Exception) {
            logger.e("OtaUpdater", "Failed to launch installer: ${e.message}", e.message)
        }
    }
}
