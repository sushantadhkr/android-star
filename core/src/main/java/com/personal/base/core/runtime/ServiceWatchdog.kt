package com.personal.base.core.runtime

import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Self-healing watchdog that monitors background services, server sockets,
 * and peer connections, triggering restart if a health check fails.
 */
class ServiceWatchdog(
    private val logger: PlatformLogger
) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val monitoredServices = ConcurrentHashMap<String, () -> Boolean>()
    private val recoveryActions = ConcurrentHashMap<String, () -> Unit>()
    private var watchdogJob: Job? = null

    fun registerService(name: String, healthCheck: () -> Boolean, onRecovery: () -> Unit) {
        monitoredServices[name] = healthCheck
        recoveryActions[name] = onRecovery
        logger.i("ServiceWatchdog", "Registered watchdog for service: $name")
    }

    fun start(intervalMs: Long = 30_000L) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                monitoredServices.forEach { (name, check) ->
                    try {
                        val healthy = check()
                        if (!healthy) {
                            logger.w("ServiceWatchdog", "Service '$name' unhealthy. Triggering recovery action...")
                            recoveryActions[name]?.invoke()
                        }
                    } catch (e: Exception) {
                        logger.e("ServiceWatchdog", "Health check threw error in '$name': ${e.message}", e.message)
                        recoveryActions[name]?.invoke()
                    }
                }
            }
        }
        logger.i("ServiceWatchdog", "Watchdog active with ${intervalMs}ms probe interval.")
    }

    fun stop() {
        watchdogJob?.cancel()
    }
}
