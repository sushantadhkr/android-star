package com.personal.base.core.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import com.personal.base.core.logging.PlatformLogger

/**
 * Build & test infrastructure helper detecting debug environment, test modes, and signing states.
 */
class BuildAndTestInfra(
    private val context: Context,
    private val logger: PlatformLogger
) {
    val isDebuggable: Boolean
        get() = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val isRunningInTestEnvironment: Boolean
        get() {
            return try {
                Class.forName("org.junit.Test")
                true
            } catch (_: ClassNotFoundException) {
                false
            }
        }

    fun logEnvironmentReport() {
        logger.i(
            "BuildAndTestInfra",
            "Environment Report: isDebuggable=$isDebuggable, isRunningInTestEnvironment=$isRunningInTestEnvironment, SDK=${android.os.Build.VERSION.SDK_INT}"
        )
    }
}
