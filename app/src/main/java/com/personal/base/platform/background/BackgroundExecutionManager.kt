package com.personal.base.platform.background

import android.app.ActivityManager
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.personal.base.core.config.ConfigRepository
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

data class BackgroundStatus(
    val isForegroundServiceRunning: Boolean = false,
    val isPeriodicWorkScheduled: Boolean = false,
    val lastSyncState: String = "IDLE",
    val syncIntervalMinutes: Long = 15L
)

class BackgroundExecutionManager(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val logger: PlatformLogger
) {

    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _status = MutableStateFlow(BackgroundStatus())
    val status: StateFlow<BackgroundStatus> = _status.asStateFlow()

    companion object {
        const val PERIODIC_WORK_TAG = "personal_base_periodic_sync"
        const val ONE_TIME_WORK_TAG = "personal_base_immediate_sync"
    }

    fun initialize() {
        schedulePeriodicWork()
        refreshStatus()
        observeWorkStatus()
        if (configRepository.config.value.isForegroundServiceEnabled) {
            startForegroundService()
        }
    }

    fun setForegroundServiceEnabled(enabled: Boolean) {
        configRepository.updateConfig { it.copy(isForegroundServiceEnabled = enabled) }
        if (enabled) {
            startForegroundService()
        } else {
            stopForegroundService()
        }
    }

    fun schedulePeriodicWork() {
        val interval = configRepository.config.value.backgroundSyncIntervalMinutes.coerceAtLeast(15L)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_TAG,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )

        logger.i("BackgroundManager", "Scheduled periodic sync every $interval minutes")
        _status.value = _status.value.copy(
            isPeriodicWorkScheduled = true,
            syncIntervalMinutes = interval
        )
    }

    fun triggerImmediateSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniqueWork(
            ONE_TIME_WORK_TAG,
            ExistingWorkPolicy.REPLACE,
            oneTimeRequest
        )

        logger.i("BackgroundManager", "Enqueued one-time immediate sync job")
    }

    fun startForegroundService() {
        PlatformForegroundService.start(context)
        logger.i("BackgroundManager", "Requested foreground service start")
        _status.value = _status.value.copy(isForegroundServiceRunning = true)
    }

    fun stopForegroundService() {
        PlatformForegroundService.stop(context)
        logger.i("BackgroundManager", "Requested foreground service stop")
        _status.value = _status.value.copy(isForegroundServiceRunning = false)
    }

    fun refreshStatus() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        @Suppress("DEPRECATION")
        val isServiceRunning = am?.getRunningServices(Integer.MAX_VALUE)?.any {
            it.service.className == PlatformForegroundService::class.java.name
        } ?: false

        _status.value = _status.value.copy(
            isForegroundServiceRunning = isServiceRunning,
            syncIntervalMinutes = configRepository.config.value.backgroundSyncIntervalMinutes
        )
    }

    private fun observeWorkStatus() {
        scope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(ONE_TIME_WORK_TAG).collect { workInfos ->
                val state = workInfos.firstOrNull()?.state?.name ?: "IDLE"
                _status.value = _status.value.copy(lastSyncState = state)
            }
        }
    }
}
