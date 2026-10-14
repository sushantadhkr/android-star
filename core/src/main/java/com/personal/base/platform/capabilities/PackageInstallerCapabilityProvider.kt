package com.personal.base.platform.capabilities

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import java.io.File
import java.io.FileInputStream

/**
 * 36. PackageInstallerCapabilityProvider: install, update, and commit app package installer sessions.
 */
class PackageInstallerCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.PACKAGE_INSTALLER

    private val packageInstaller = context.packageManager.packageInstaller

    fun installApk(apkFile: File): Int {
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            session.openWrite("package_install", 0, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { `in` ->
                    `in`.copyTo(out)
                    session.fsync(out)
                }
            }

            val intent = Intent("com.personal.base.ACTION_INSTALL_COMMIT")
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            session.commit(pendingIntent.intentSender)
            session.close()
            logger.i("PackageInstaller", "Committed APK install session #$sessionId for ${apkFile.name}")
            return sessionId
        } catch (e: Exception) {
            logger.e("PackageInstaller", "Install session error: ${e.message}", e.message)
            return -1
        }
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
