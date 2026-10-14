package com.personal.base.core.platform

/**
 * A text field rendered inside a notification button, for reply-style actions.
 *
 * [key] is the field's identity; the platform reads the typed value back under the same key.
 */
data class NotificationInput(
    val key: String,
    val hint: String
) {
    init {
        require(key.isNotBlank()) { "A notification input needs a key" }
    }
}

/**
 * A button rendered on a notification.
 *
 * Pressing one delivers a broadcast rather than launching anything, so the action is handled whether
 * or not the app is already running — Android starts the process to deliver it.
 *
 * @param icon one of [NotificationActionContract.ICONS]; anything else falls back to a neutral icon.
 * @param input an optional reply field. Only meaningful together with [invoke].
 * @param invoke the capability action to run, written `CAPABILITY:action`, or
 *   `CAPABILITY:action:param` when [input] is present so the typed text has somewhere to go.
 *   Omitting it makes the button purely a record of the press.
 * @param dismissesNotification whether the notification clears once the button has run.
 * @param params fixed arguments merged into the invoke payload. Without these a button could only
 *   pass typed text, so it could never act on the thing it was attached to — a Cancel button on
 *   one download would not know which download it meant.
 */
data class NotificationButton(
    val id: String,
    val label: String,
    val icon: String? = null,
    val input: NotificationInput? = null,
    val invoke: String? = null,
    val dismissesNotification: Boolean = true,
    val params: Map<String, String> = emptyMap()
) {
    init {
        require(id.isNotBlank()) { "A notification button needs an id" }
        require(label.isNotBlank()) { "Notification button '$id' needs a label" }
        require(invoke == null || NotificationActionContract.parseInvoke(invoke) != null) {
            "Notification button '$id' has invoke '$invoke', which is not CAPABILITY:action"
        }
        // Text the user typed has to land somewhere. Refusing the button here is better than
        // silently discarding what they wrote.
        require(
            input == null || NotificationActionContract.parseInvoke(invoke ?: "")?.field != null
        ) {
            "Notification button '$id' takes text, so its invoke must name where it goes: " +
                "\"CAPABILITY:action:param\""
        }
    }
}

/** What a posted notification actually became. */
data class PostedNotification(
    val id: Int,
    val buttonsShown: Int,
    val buttonsDropped: List<String>
)

/**
 * The wire format between a button and the receiver that handles it.
 *
 * Lives in one place because two modules have to agree on it: the capability builds the intents,
 * the shell's receiver reads them. Keeping the parsing here also means it can be tested without a
 * device.
 */
object NotificationActionContract {

    const val INTENT_ACTION = "com.personal.base.action.NOTIFICATION_BUTTON"

    const val EXTRA_BUTTON_ID = "buttonId"
    const val EXTRA_LABEL = "label"
    const val EXTRA_NOTIFICATION_ID = "notificationId"
    const val EXTRA_NOTIFICATION_TITLE = "notificationTitle"
    const val EXTRA_DISMISS = "dismiss"
    const val EXTRA_INVOKE = "invoke"
    const val EXTRA_INPUT_KEY = "inputKey"

    /**
     * Fixed arguments for the invoke, as JSON object text.
     *
     * JSON rather than parallel string arrays so a parameter's value can contain any character a
     * value could plausibly hold.
     */
    const val EXTRA_PARAMS = "params"

    /** Icons the platform can draw, so a caller never has to ship a drawable for a button. */
    const val ICON_CHECK = "check"
    const val ICON_ALARM = "alarm"
    const val ICON_REPLY = "reply"
    const val ICON_DELETE = "delete"
    const val ICON_REFRESH = "refresh"
    const val ICON_OPEN = "open"

    val ICONS = setOf(ICON_CHECK, ICON_ALARM, ICON_REPLY, ICON_DELETE, ICON_REFRESH, ICON_OPEN)

    /** A parsed `CAPABILITY:action[:param]` target. */
    data class InvokeTarget(
        val capability: String,
        val action: String,
        val field: String?
    )

    /**
     * Reads an invoke target.
     *
     * Returns null for anything malformed rather than throwing, because the string arrives over
     * HTTP from a caller who may have guessed. A blank field is treated as absent.
     */
    fun parseInvoke(spec: String): InvokeTarget? {
        val parts = spec.split(':')
        if (parts.size !in 2..3) return null
        val capability = parts[0].trim()
        val action = parts[1].trim()
        if (capability.isEmpty() || action.isEmpty()) return null
        return InvokeTarget(capability, action, parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() })
    }
}