package com.personal.base.core.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import com.personal.base.core.logging.PlatformLogger
import org.json.JSONObject

data class PlatformMetrics(
    val uptimeMillis: Long,
    val availableMemoryBytes: Long,
    val totalMemoryBytes: Long,
    val isLowMemory: Boolean,
    val activeThreads: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Exposes device health, uptime, and system metrics for monitoring and remote telemetry.
 */
class MetricsHealthEndpoint(
    private val context: Context,
    private val logger: PlatformLogger
) {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val startTimeMillis = SystemClock.elapsedRealtime()

    fun getMetrics(): PlatformMetrics {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        return PlatformMetrics(
            uptimeMillis = SystemClock.elapsedRealtime() - startTimeMillis,
            availableMemoryBytes = memInfo.availMem,
            totalMemoryBytes = memInfo.totalMem,
            isLowMemory = memInfo.lowMemory,
            activeThreads = Thread.activeCount()
        )
    }

    fun getMetricsJson(): String {
        val m = getMetrics()
        return JSONObject().apply {
            put("uptimeMs", m.uptimeMillis)
            put("availableMemMb", m.availableMemoryBytes / (1024 * 1024))
            put("totalMemMb", m.totalMemoryBytes / (1024 * 1024))
            put("isLowMemory", m.isLowMemory)
            put("activeThreads", m.activeThreads)
            put("timestamp", m.timestamp)
        }.toString()
    }
}
