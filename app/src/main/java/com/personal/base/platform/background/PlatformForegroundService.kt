package com.personal.base.platform.background

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.personal.base.MainActivity
import com.personal.base.PersonalBaseApplication
import com.personal.base.platform.capabilities.NotificationCapabilityProvider
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The always-on platform host.
 *
 * This is not "background work" in the abstract — it is the process that keeps the platform alive
 * after you leave the app, and it is what makes the phone reachable from the network.
 *
 * While running it:
 *   - hosts the platform's HTTP server, so Apps that implement `AppServer` answer requests;
 *   - runs periodic App sync so background work still happens with the screen off.
 *
 * Android requires a foreground service to show a permanent notification, which is why the user
 * always sees it. Turning it off in Settings stops the server and background work together.
 */
class PlatformForegroundService : Service() {

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)

    /** Follows the "Keep CPU Awake" switch. Cancelled with the host. */
    private var wakeLockWatch: Job? = null

    private var log: PlatformLogger? = null

    companion object {
        const val ACTION_START = "com.personal.base.action.START_SERVICE"
        const val ACTION_STOP = "com.personal.base.action.STOP_SERVICE"
        const val NOTIFICATION_ID = 8801

        /**
         * Asks Android to start the host. Returns whether the request was accepted.
         *
         * Android refuses to start a foreground service from the background, and on Android 12+ it
         * throws when it does. That is not an edge case here: the app is started in the background
         * whenever the system binds the accessibility service, a WorkManager job fires, or a boot
         * receiver runs. Letting that exception escape `Application.onCreate` killed the whole
         * process — no host, no API server — so it is caught and reported as a plain "no".
         *
         * The host is then started the next time the app is genuinely in the foreground, which is
         * the only moment Android permits.
         */
        fun start(context: Context): Boolean = try {
            val intent = Intent(context, PlatformForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException on Android 12+, and SecurityException if a
            // permission was revoked while the app was away. Both mean "not now", never "broken".
            (context.applicationContext as? PersonalBaseApplication)
                ?.container?.logger
                ?.w("ForegroundService", "Start refused for now: ${e::class.java.simpleName}: ${e.message}")
            false
        }

        fun stop(context: Context) {
            val intent = Intent(context, PlatformForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? PersonalBaseApplication
        val logger = app?.container?.logger
        log = logger

        when (intent?.action) {
            ACTION_STOP -> {
                logger?.i("ForegroundService", "Stopping host on explicit user action")
                stopHost()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                logger?.i("ForegroundService", "Starting foreground service with dataSync type")
                val notification = buildForegroundNotification()

                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }

                try {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
                } catch (e: Exception) {
                    logger?.e("ForegroundService", "Failed to start foreground service", e.message)
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Publish the platform server for as long as this host is alive. Apps that
                // implement AppServer install their routes onto it, and the App manager is told
                // where they live so a newly-started App can publish immediately.
                val container = app?.container
                // Serving is the other reason the runtime exists, so the host brings it up itself.
                container?.ensurePlatformRuntime()
                container?.embeddedServer?.let { server ->
                    server.start()
                    container.appManager.server = server
                    logger?.i("ForegroundService", "Hosting platform server on port ${server.port}")

                    // Whether to hold the CPU awake is the user's own switch, because it was
                    // measured to be unnecessary: with the screen off the host answered 12 of 12
                    // requests in about 20 ms without a lock. The code here used to claim the
                    // phone "stops responding within a minute", and that claim was simply wrong on
                    // a phone with its battery-optimisation exemption granted.
                    //
                    // It still earns its place where that exemption is missing, so it is offered
                    // rather than deleted — and the card says which case you are in.
                    applyCpuWakeLock(
                        container,
                        container.configRepository.config.value.keepCpuAwakeWhileHostRuns
                    )

                    // Watch the switch rather than reading it once. Reading it at startup meant a
                    // user could flip it in Settings, see no change at all, and have no way to
                    // tell whether the switch worked or the app was simply ignoring it.
                    watchCpuWakeLock(container)

                    // The watchdog was built for exactly this and nothing had ever registered with
                    // it, so it had been polling an empty map. If the socket ever closes, the phone
                    // was unreachable until the host was restarted by hand.
                    container.serviceWatchdog.registerService(
                        name = "platform-server",
                        healthCheck = { server.isRunning.value },
                        onRecovery = {
                            logger?.w("ForegroundService", "Server socket is down; reopening it")
                            server.start()
                        }
                    )
                }

                logger?.i(
                    "ForegroundService",
                    "Battery optimisation exemption: " +
                        (container?.wakeLockManager?.isIgnoringBatteryOptimizations ?: false) +
                        ". Without it Android dozes the host far more aggressively."
                )

                // Periodic sync: App hooks, and plugin sync for anything still using the older path.
                scope.launch {
                    var counter = 0
                    while (isActive) {
                        delay(30_000L) // 30 second cycle
                        counter++
                        logger?.d("ForegroundService", "Active host heartbeat tick #$counter")
                        container?.appManager?.runPeriodicSync()
                    }
                }
            }
        }

        return START_STICKY
    }

    /**
     * Puts the wake lock in the state the user asked for.
     *
     * Only acts when the state actually differs, so a repeated call neither re-acquires nor logs.
     */
    private fun applyCpuWakeLock(container: com.personal.base.di.PlatformContainer, wanted: Boolean) {
        val held = container.wakeLockManager.isHeld
        if (wanted == held) return

        if (wanted) {
            container.wakeLockManager.acquireWakeLock()
            log?.i("ForegroundService", "Holding a CPU wake lock as requested.")
        } else {
            container.wakeLockManager.releaseWakeLock()
            log?.i(
                "ForegroundService",
                "Released the CPU wake lock. Reachability relies on the battery-optimisation " +
                    "exemption (${container.wakeLockManager.isIgnoringBatteryOptimizations})."
            )
        }
    }

    /** Follows the user's switch while the host runs, so flipping it takes effect at once. */
    private fun watchCpuWakeLock(container: com.personal.base.di.PlatformContainer) {
        wakeLockWatch?.cancel()
        wakeLockWatch = scope.launch {
            container.configRepository.config
                .map { it.keepCpuAwakeWhileHostRuns }
                .distinctUntilChanged()
                .collect { wanted -> applyCpuWakeLock(container, wanted) }
        }
    }

    private fun buildForegroundNotification(): Notification {
        val stopIntent = Intent(this, PlatformForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationCapabilityProvider.CHANNEL_SYNC)
            .setContentTitle("Android Star is running")
            .setContentText("Serving your apps in the background · port ${hostPort()}")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun hostPort(): Int =
        (application as? PersonalBaseApplication)
            ?.container?.embeddedServer?.port
            ?: com.personal.base.core.platform.EmbeddedServer.DEFAULT_PORT

    /** Tears the host down: the server stops and Apps can no longer be reached. */
    private fun stopHost() {
        val container = (application as? PersonalBaseApplication)?.container ?: return
        container.appManager.server = null
        container.embeddedServer.stop()

        // Released with the host. A wake lock that outlived the thing it was keeping alive would
        // drain the battery of a device the user believes is switched off.
        container.wakeLockManager.releaseWakeLock()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHost()
        serviceJob.cancel()
    }
}
