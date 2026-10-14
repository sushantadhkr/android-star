package com.personal.base.core.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.personal.base.core.logging.PlatformLogger

enum class OemVendor {
    XIAOMI, SAMSUNG, HUAWEI, OPPO, VIVO, ONEPLUS, GENERIC
}

/**
 * Detects aggressive OEM background process killers (MIUI/OneUI/ColorOS) and directs user to autostart exemptions.
 */
class OemKeepAlive(
    private val context: Context,
    private val logger: PlatformLogger
) {
    val detectedVendor: OemVendor = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" -> OemVendor.XIAOMI
        "samsung" -> OemVendor.SAMSUNG
        "huawei", "honor" -> OemVendor.HUAWEI
        "oppo", "realme" -> OemVendor.OPPO
        "vivo", "iqoo" -> OemVendor.VIVO
        "oneplus" -> OemVendor.ONEPLUS
        else -> OemVendor.GENERIC
    }

    val hasAggressiveKillPolicy: Boolean = detectedVendor != OemVendor.GENERIC

    fun openAutostartSettings(): Boolean {
        logger.i("OemKeepAlive", "Opening autostart settings for vendor: $detectedVendor")
        return try {
            val intent = Intent()
            when (detectedVendor) {
                OemVendor.XIAOMI -> {
                    intent.setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                }
                OemVendor.OPPO -> {
                    intent.setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                }
                OemVendor.VIVO -> {
                    intent.setClassName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
                }
                OemVendor.HUAWEI -> {
                    intent.setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                }
                else -> {
                    intent.action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    intent.data = Uri.parse("package:${context.packageName}")
                }
            }
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            logger.w("OemKeepAlive", "Could not launch direct OEM screen, opening App Details fallback: ${e.message}")
            try {
                val fallback = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
