package com.personal.base.platform.capabilities

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * 19. AlarmTimerCapabilityProvider: exact alarms, timers, and reminders.
 */
class AlarmTimerCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.ALARM_TIMER

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Whether any app on this phone will accept a timer request.
     *
     * The timer is set by handing an intent to the system clock app, so on a phone with no clock —
     * or with one disabled — there is nothing to receive it. Checked up front so the API can say
     * that, rather than reporting a bare "could not start the timer" that points nowhere.
     */
    fun canSetTimer(): Boolean = try {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
        context.packageManager.resolveActivity(intent, 0) != null
    } catch (e: Exception) {
        false
    }

    fun setTimer(seconds: Int, message: String): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            logger.i("AlarmTimerCapability", "Set timer for ${seconds}s with message '$message'")
            true
        } catch (e: Exception) {
            logger.e("AlarmTimerCapability", "Failed to set timer: ${e.message}", e.message)
            false
        }
    }

    override fun checkStatus(): CapabilityStatus {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) CapabilityStatus.Available
            else CapabilityStatus.PermissionRequired(listOf(android.Manifest.permission.SCHEDULE_EXACT_ALARM))
        } else CapabilityStatus.Available
    }

    override fun release() {}
}
