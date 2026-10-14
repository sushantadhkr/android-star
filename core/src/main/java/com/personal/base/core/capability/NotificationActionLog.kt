package com.personal.base.core.capability

/**
 * One press of a notification button.
 *
 * Recorded so a button press is not invisible: it survives the broadcast, it can be read back over
 * the API, and it is in the platform log like everything else.
 *
 * @param typedText what the user typed into the reply field, if there was one.
 * @param invokeStatus the HTTP status of the capability action that ran, or null when the button ran
 *   nothing — which is itself worth reporting, because it means a button did not do what it said.
 */
data class NotificationActionRecord(
    val buttonId: String,
    val label: String,
    val notificationId: Int,
    val notificationTitle: String,
    val typedText: String?,
    val invokeTarget: String?,
    val invokeStatus: Int?,
    val invokeBody: String?,
    val atEpochMillis: Long
)

/**
 * The recent history of notification button presses.
 *
 * Deliberately bounded and in memory: this is a live view of what was just pressed, not an audit
 * trail. Anything that needs to persist goes to the log sink, which is compulsory anyway.
 */
class NotificationActionLog(
    private val capacity: Int = DEFAULT_CAPACITY
) {

    private val records = ArrayDeque<NotificationActionRecord>(capacity)

    @Synchronized
    fun record(record: NotificationActionRecord) {
        if (records.size >= capacity) records.removeFirst()
        records.addLast(record)
    }

    /** Newest first. */
    @Synchronized
    fun recent(limit: Int = capacity): List<NotificationActionRecord> =
        records.toList().reversed().take(limit.coerceAtLeast(0))

    @Synchronized
    fun size(): Int = records.size

    private companion object {
        const val DEFAULT_CAPACITY = 50
    }
}