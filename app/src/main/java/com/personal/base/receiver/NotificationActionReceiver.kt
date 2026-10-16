package com.personal.base.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.personal.base.PersonalBaseApplication
import com.personal.base.core.capability.NotificationActionRecord
import com.personal.base.core.platform.NotificationActionContract
import com.personal.base.core.platform.ServerResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Executors

/**
 * Handles a press of a notification button.
 *
 * Android delivers the press as a broadcast rather than launching anything, which is what makes the
 * buttons work when the app is not running — the process is started purely to handle the press.
 *
 * Everything here obeys the same rules as the HTTP API, because it is the same way in:
 *
 *  - the capability gate is checked, so a button cannot reach a capability the user switched off;
 *  - the press is recorded, so a button is never a silent side effect;
 *  - what it typed, if anything, is logged rather than swallowed.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // A button press is an explicit request, so the runtime comes up.
        (context.applicationContext as? com.personal.base.PersonalBaseApplication)
            ?.container?.ensurePlatformRuntime()
        if (intent.action != NotificationActionContract.INTENT_ACTION) return

        val container = (context.applicationContext as? PersonalBaseApplication)?.container
        if (container == null) {
            android.util.Log.e(TAG, "No container; cannot handle notification action")
            return
        }

        val buttonId = intent.getStringExtra(NotificationActionContract.EXTRA_BUTTON_ID) ?: return
        val label = intent.getStringExtra(NotificationActionContract.EXTRA_LABEL) ?: buttonId
        val notificationId = intent.getIntExtra(NotificationActionContract.EXTRA_NOTIFICATION_ID, 0)
        val title = intent.getStringExtra(NotificationActionContract.EXTRA_NOTIFICATION_TITLE) ?: ""
        val invokeTarget = intent.getStringExtra(NotificationActionContract.EXTRA_INVOKE)
        val inputKey = intent.getStringExtra(NotificationActionContract.EXTRA_INPUT_KEY)
        val dismiss = intent.getBooleanExtra(NotificationActionContract.EXTRA_DISMISS, true)

        val typedText = inputKey?.let { key ->
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(key)?.toString()
        }

        // Fixed arguments travel as JSON so a button can act on the thing it is attached to.
        val params = intent.getStringExtra(NotificationActionContract.EXTRA_PARAMS)
            ?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull() }
            ?: kotlinx.serialization.json.JsonObject(emptyMap())

        container.logger.i(TAG, "Button '$buttonId' pressed on \"$title\"")

        // Running a capability is not instant, and a receiver has a short window before the system
        // may kill the process, so the work goes off the main thread and holds the process awake
        // until it finishes.
        val pending = goAsync()
        WORK.execute {
            try {
                handle(
                    container = container,
                    invokeTarget = invokeTarget,
                    typedText = typedText,
                    params = params,
                    record = NotificationActionRecord(
                        buttonId = buttonId,
                        label = label,
                        notificationId = notificationId,
                        notificationTitle = title,
                        typedText = typedText,
                        invokeTarget = invokeTarget,
                        invokeStatus = null,
                        invokeBody = null,
                        atEpochMillis = System.currentTimeMillis()
                    )
                )
            } finally {
                if (dismiss) context.notificationManager()?.cancel(notificationId)
                pending.finish()
            }
        }
    }

    private fun handle(
        container: com.personal.base.di.PlatformContainer,
        invokeTarget: String?,
        typedText: String?,
        params: kotlinx.serialization.json.JsonObject,
        record: NotificationActionRecord
    ) {
        var response: ServerResponse? = null

        if (invokeTarget != null) {
            response = runInvoke(container, invokeTarget, typedText, params)
        }

        val stored = record.copy(
            invokeStatus = response?.status,
            invokeBody = response?.body
        )
        container.notificationActionLog.record(stored)

        val outcome = when {
            invokeTarget == null -> "${record.label} — recorded"
            response == null -> "${record.label} — nothing ran"
            response.status in 200..299 -> "${record.label} — done" +
                (typedText?.let { ": $it" } ?: "")
            else -> "${record.label} — refused (${response.status}): ${response.body}"
        }

        container.logger.i(TAG, outcome)
        container.platformContext.postNotification(record.notificationTitle, outcome)
    }

    /**
     * Runs the capability the button named.
     *
     * Goes through [com.personal.base.core.capability.CapabilityApi.invokeGated], which is the same
     * door the HTTP API uses. A button therefore cannot do anything the API would refuse, and a
     * capability the user switched off stays switched off even when pressed directly.
     */
    private fun runInvoke(
        container: com.personal.base.di.PlatformContainer,
        invokeTarget: String,
        typedText: String?,
        params: kotlinx.serialization.json.JsonObject
    ): ServerResponse? {
        val target = NotificationActionContract.parseInvoke(invokeTarget)
        if (target == null) {
            container.logger.w(TAG, "Malformed invoke target: $invokeTarget")
            return ServerResponse(400, "application/json", """{"error":"Malformed invoke target"}""")
        }

        val type = container.capabilityApi.resolveType(target.capability)
            ?: return ServerResponse(
                404, "application/json",
                """{"error":"No such capability '${target.capability}'"}"""
            )

        val payload = buildJsonObject {
            // Fixed arguments first, so a button always knows what it is acting on.
            params.forEach { (k, v) -> put(k, v) }
            // The typed text goes where the button said it should. Without that it would be read
            // and thrown away, which is worse than not offering the field at all.
            typedText?.let { put(target.field ?: DEFAULT_TEXT_FIELD, it) }
        }

        return container.capabilityApi.invokeGated(type, target.action, payload)
    }

    private fun Context.notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager

    private companion object {
        const val TAG = "NotificationAction"
        const val DEFAULT_TEXT_FIELD = "text"

        /** One thread: presses are rare, and serialising them keeps the log order true. */
        val WORK = Executors.newSingleThreadExecutor()
    }
}