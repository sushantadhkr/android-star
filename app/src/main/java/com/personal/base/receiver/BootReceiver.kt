package com.personal.base.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.personal.base.PersonalBaseApplication

/**
 * Recovers platform background services upon device restart or package update.
 */
class BootReceiver : BroadcastReceiver {
    constructor() : super()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val app = context.applicationContext as? PersonalBaseApplication
            app?.container?.logger?.i("BootReceiver", "Received $action. Recovering background scheduler...")
            app?.container?.ensurePlatformRuntime()
            app?.container?.backgroundManager?.schedulePeriodicWork()
        }
    }
}
