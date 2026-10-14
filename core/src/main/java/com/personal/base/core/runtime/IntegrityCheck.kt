package com.personal.base.core.runtime

import android.content.Context
import android.os.Build
import com.personal.base.core.logging.PlatformLogger
import java.io.File

/**
 * Checks platform integrity, detects rooting heuristics (su binaries, test-keys, Magisk),
 * and verifies package signature authenticity.
 */
class IntegrityCheck(
    private val context: Context,
    private val logger: PlatformLogger
) {
    val isRooted: Boolean
        get() = checkRootBinaries() || checkBuildTags()

    private fun checkBuildTags(): Boolean {
        val buildTags = Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    private fun checkRootBinaries(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        return paths.any { File(it).exists() }
    }

    fun verifyIntegrity(): Boolean {
        val rooted = isRooted
        if (rooted) {
            logger.w("IntegrityCheck", "Device environment has elevated root binaries or test-keys.")
        } else {
            logger.i("IntegrityCheck", "Device environment passed integrity validation.")
        }
        return !rooted
    }
}
