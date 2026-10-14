package com.personal.base.core.runtime

import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Handles Android 14+ Foreground Service timeout restrictions (e.g. dataSync 6-hour limit)
 * by cycling or transitioning foreground service contexts before OS termination.
 */
class FgsTimeoutHandler(
    private val logger: PlatformLogger
) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var cycleJob: Job? = null

    fun startTimeoutWatcher(maxRuntimeMs: Long = 5 * 60 * 60 * 1000L, onCycleRequired: () -> Unit) {
        cycleJob?.cancel()
        cycleJob = scope.launch {
            while (isActive) {
                delay(maxRuntimeMs)
                logger.w("FgsTimeoutHandler", "Approaching Android 14 FGS dataSync limit. Notifying renewal...")
                onCycleRequired()
            }
        }
    }

    fun stop() {
        cycleJob?.cancel()
    }
}
