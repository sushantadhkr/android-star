package com.personal.base.platform.capabilities

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.TimeUnit

/** One piece of work the platform can be asked to do later instead of inside an HTTP request. */
data class BackgroundTask(
    /** Unique, stable name. Also the WorkManager unique-work name, so enqueueing twice is not a pile-up. */
    val name: String,
    val description: String,
    val handler: suspend (JsonObject) -> String
)

/** A unit of work as the caller sees it: what it is, what state it reached, what it produced. */
data class BackgroundWorkInfo(
    val name: String,
    val id: String,
    val state: String,
    val runAttemptCount: Int,
    val lastResult: String,
    val lastError: String
)

/**
 * Work that outlives the request that asked for it.
 *
 * Some capability work is wrong to do inside an HTTP call: it is slow, it needs the network, it
 * should not start until the phone is charging, or it should survive the caller disconnecting.
 * This provider exists so an action can hand that work over and get an id back, then ask later
 * what happened.
 *
 * A task is only ever a registered [BackgroundTask], never an arbitrary code string. That is the
 * whole reason this is safe to expose over an API: there is nothing to inject, because nothing is
 * supplied at call time.
 */
class BackgroundWorkCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.BACKGROUND_WORK

    private val workManager: WorkManager? = runCatching { WorkManager.getInstance(context) }.getOrNull()

    private val tasks = mutableMapOf<String, BackgroundTask>()

    /** Lets a capability offer work without the API layer knowing what that work is. */
    @Synchronized
    fun register(task: BackgroundTask) {
        tasks[task.name] = task
        logger.i("BackgroundWork", "Registered background task '${task.name}'")
    }

    @Synchronized
    fun taskNames(): List<String> = tasks.keys.sorted()

    /** One registered task by name, or null. The worker looks tasks up through this. */
    @Synchronized
    fun taskNamed(name: String): BackgroundTask? = tasks[name]

    /**
     * Hands work to WorkManager.
     *
     * [REPLACE] rather than [APPEND]: asking for the same task twice means "make sure this gets
     * done", not "do it twice". Queuing duplicates would let one API call become an unbounded number
     * of real actions against a real device.
     */
    fun enqueue(
        name: String,
        payload: JsonObject,
        requireNetwork: Boolean,
        requireCharging: Boolean,
        delaySeconds: Long
    ): String? {
        val task = tasks[name] ?: return null
        val wm = workManager ?: return null

        val request = OneTimeWorkRequestBuilder<CapabilityWorkWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (requireNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED
                    )
                    .setRequiresCharging(requireCharging)
                    .build()
            )
            // Expedited is deliberately not requested. Android rejects an expedited job that also
            // carries a storage or device-idling constraint, so asking for it whenever the caller
            // said requireCharging turned a perfectly reasonable documented call into a 500.
            // Queued work that respects the constraints is the honest trade for never failing.
            .setInputData(
                workDataOf(
                    CapabilityWorkWorker.KEY_TASK to name,
                    CapabilityWorkWorker.KEY_PAYLOAD to payload.toString()
                )
            )
            .addTag(TAG_ALL)
            .addTag(TAG_PREFIX + name)
            .build()

        wm.enqueueUniqueWork(WORK_PREFIX + name, ExistingWorkPolicy.REPLACE, request)
        logger.i("BackgroundWork", "Enqueued '$name' (network=$requireNetwork, charging=$requireCharging)")
        return request.id.toString()
    }

    fun cancel(name: String): Boolean {
        val wm = workManager ?: return false
        // Blocking, deliberately: every action handler already runs on a worker thread, and pulling
        // in a futures bridge for one call would be a dependency bought for no other reason.
        val queued = runCatching { wm.getWorkInfosForUniqueWork(WORK_PREFIX + name).get() }
            .getOrElse {
                logger.e("BackgroundWork", "Could not read state of '$name': ${it.message}")
                return false
            }
        if (queued.isEmpty()) return false
        wm.cancelUniqueWork(WORK_PREFIX + name)
        logger.i("BackgroundWork", "Cancelled '$name'")
        return true
    }

    fun list(): List<BackgroundWorkInfo> {
        val wm = workManager ?: return emptyList()
        val infos: List<WorkInfo> = runCatching { wm.getWorkInfosByTag(TAG_ALL).get() }
            .getOrElse {
                logger.e("BackgroundWork", "Could not read work list: ${it.message}")
                return emptyList()
            }
        return infos.map { info ->
            BackgroundWorkInfo(
                name = info.tags.firstOrNull { it.startsWith(TAG_PREFIX) }
                    ?.removePrefix(TAG_PREFIX) ?: "unknown",
                state = info.state.name,
                runAttemptCount = info.runAttemptCount,
                // WorkInfo exposes no enqueue timestamp, so the id is offered instead: it orders
                // nothing, but it is what WorkManager's own tooling shows and it round-trips.
                id = info.id.toString(),
                lastResult = info.outputData.getString(CapabilityWorkWorker.KEY_RESULT) ?: "",
                lastError = info.outputData.getString(CapabilityWorkWorker.KEY_ERROR) ?: ""
            )
        }
    }

    override fun checkStatus(): CapabilityStatus =
        if (workManager != null) CapabilityStatus.Available
        else CapabilityStatus.Unavailable("WorkManager is not available in this process")

    override fun release() {
        synchronized(tasks) { tasks.clear() }
    }

    private companion object {
        const val TAG_ALL = "capability-work"
        const val TAG_PREFIX = "capability-task:"
        const val WORK_PREFIX = "capability-work:"
    }
}

/**
 * Runs one registered task on a WorkManager thread.
 *
 * The worker reaches the task table through the process's own service locator, because a WorkManager
 * worker is constructed by the framework and has nowhere to be handed a provider.
 */
class CapabilityWorkWorker(
    appContext: Context,
    params: androidx.work.WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val name = inputData.getString(KEY_TASK) ?: return Result.failure(
            errorOutput("No task name was supplied")
        )

        val registry = taskRegistry ?: return Result.failure(
            errorOutput("The background work table is not available in this process")
        )

        val task = registry.taskNamed(name) ?: return Result.failure(
            errorOutput("No task named '$name' is registered")
        )

        val raw = inputData.getString(KEY_PAYLOAD) ?: "{}"
        val payload = runCatching { Json.parseToJsonElement(raw) as JsonObject }.getOrElse { JsonObject(emptyMap()) }

        return try {
            val result = task.handler(payload)
            Result.success(workDataOf(KEY_RESULT to result))
        } catch (e: Exception) {
            // Failure is reported, never swallowed: a task that could not run must not look like
            // one that ran and had nothing to say.
            Result.failure(errorOutput(e.message ?: e::class.java.simpleName))
        }
    }

    private fun errorOutput(message: String): Data = workDataOf(KEY_ERROR to message)

    companion object {
        const val KEY_TASK = "task"
        const val KEY_PAYLOAD = "payload"
        const val KEY_RESULT = "result"
        const val KEY_ERROR = "error"

        /** Set by the platform container at startup. */
        @Volatile
        @JvmStatic
        var taskRegistry: BackgroundWorkCapabilityProvider? = null

        /** The task table, or null when a task name was not registered. */
        fun taskNamed(name: String): BackgroundTask? = taskRegistry?.taskNamed(name)
    }
}
