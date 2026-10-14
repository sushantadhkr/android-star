package com.personal.base.platform.capabilities

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

data class InstalledAppItem(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val versionName: String
)

/**
 * 13. InstalledAppsCapabilityProvider: list installed packages, launch apps, and usage stats.
 */
class InstalledAppsCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.INSTALLED_APPS

    private val packageManager = context.packageManager

    fun getInstalledApps(includeSystem: Boolean = false): List<InstalledAppItem> {
        val list = mutableListOf<InstalledAppItem>()
        try {
            val packages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
            for (p in packages) {
                val isSystem = (p.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) ?: 0) != 0
                if (!includeSystem && isSystem) continue
                val name = p.applicationInfo?.loadLabel(packageManager)?.toString() ?: p.packageName
                list.add(
                    InstalledAppItem(
                        packageName = p.packageName,
                        appName = name,
                        isSystemApp = isSystem,
                        versionName = p.versionName ?: "1.0"
                    )
                )
            }
        } catch (e: Exception) {
            logger.e("InstalledApps", "Error reading packages: ${e.message}", e.message)
        }
        return list.sortedBy { it.appName.lowercase() }
    }

    fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            logger.i("InstalledApps", "Launched application: $packageName")
            true
        } catch (e: Exception) {
            logger.e("InstalledApps", "Failed to launch $packageName: ${e.message}", e.message)
            false
        }
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
