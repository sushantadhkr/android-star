package com.personal.base.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.personal.base.PersonalBaseApplication
import com.personal.base.core.platform.PlatformEvent

/**
 * System BroadcastReceiver listening for OS battery, network, incoming SMS, and package events
 * and feeding them into the central EventBus.
 */
class PlatformSystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PersonalBaseApplication ?: return
        val eventBus = app.container.eventBus
        val action = intent.action ?: return

        when (action) {
            Intent.ACTION_BATTERY_LOW -> {
                eventBus.publish(PlatformEvent("system.battery.low", mapOf("level" to "low")))
            }
            Intent.ACTION_BATTERY_OKAY -> {
                eventBus.publish(PlatformEvent("system.battery.okay", mapOf("level" to "okay")))
            }
            Intent.ACTION_POWER_CONNECTED -> {
                eventBus.publish(PlatformEvent("system.power.connected", mapOf("charging" to true)))
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                eventBus.publish(PlatformEvent("system.power.disconnected", mapOf("charging" to false)))
            }
            "android.net.conn.CONNECTIVITY_CHANGE" -> {
                eventBus.publish(PlatformEvent("system.network.changed", emptyMap()))
            }
            "android.provider.Telephony.SMS_RECEIVED" -> {
                eventBus.publish(PlatformEvent("system.telephony.sms_received", emptyMap()))
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                eventBus.publish(PlatformEvent("system.package.updated", emptyMap()))
            }
        }
    }
}
