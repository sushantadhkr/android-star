package com.personal.base.core.runtime

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.view.Choreographer
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PerformanceStats(
    val droppedFrames: Long = 0,
    val trimMemoryLevel: Int = 0,
    val lastTrimMemoryDescription: String = "Normal"
)

/**
 * Tracks Choreographer frame drops, ANRs, and system memory pressure events.
 */
class PerformanceMonitor(
    private val logger: PlatformLogger
) : ComponentCallbacks2 {

    private val _stats = MutableStateFlow(PerformanceStats())
    val stats: StateFlow<PerformanceStats> = _stats.asStateFlow()

    private var frameCount = 0L
    private var lastFrameTimeNanos = 0L

    fun startFrameTracking() {
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastFrameTimeNanos > 0) {
                    val diffMs = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000
                    if (diffMs > 32) { // Dropped frames (> 2 frames at 60Hz)
                        val dropped = (diffMs / 16) - 1
                        _stats.value = _stats.value.copy(droppedFrames = _stats.value.droppedFrames + dropped)
                    }
                }
                lastFrameTimeNanos = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
        logger.i("PerformanceMonitor", "Started Choreographer frame performance monitoring.")
    }

    override fun onTrimMemory(level: Int) {
        val desc = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "Level $level"
        }
        _stats.value = _stats.value.copy(trimMemoryLevel = level, lastTrimMemoryDescription = desc)
        logger.w("PerformanceMonitor", "System onTrimMemory triggered: $desc")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}
    override fun onLowMemory() {
        _stats.value = _stats.value.copy(trimMemoryLevel = 80, lastTrimMemoryDescription = "LOW_MEMORY_CRITICAL")
        logger.e("PerformanceMonitor", "Critical low memory event received from OS!", "onLowMemory")
    }
}
