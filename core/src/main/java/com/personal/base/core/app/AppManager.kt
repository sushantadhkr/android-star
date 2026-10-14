package com.personal.base.core.app

import com.personal.base.core.command.CommandResult
import com.personal.base.core.command.RemoteCommand
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.platform.EmbeddedServer
import com.personal.base.core.plugin.PlatformContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the lifecycle of every App.
 *
 * The invariant this class exists to enforce:
 *
 * > An enabled App has exactly one instance. A disabled App has none, executes nothing, and has
 * > its state cleared.
 *
 * Registrations are always known so every App appears on the Apps page with a working toggle.
 * Instances are created only on enable and destroyed on disable.
 */
class AppManager(
    private val platformContext: PlatformContext,
    private val toggleStore: AppToggleStoreContract,
    private val logger: PlatformLogger,
    /**
     * Optional so lifecycle rules stay testable without Android. When supplied, scopes created here
     * post to the App's own notification channel, which is what makes per-App muting work.
     */
    private val channelManager: com.personal.base.core.runtime.NotificationChannelManager? = null
) {

    private val registrations = LinkedHashMap<String, AppRegistration>()

    /** Live instances, keyed by App id. Empty entry means the App is switched off. */
    private val instances = ConcurrentHashMap<String, PlatformApp>()

    /**
     * What each App last declared it contributes. Retained after teardown so a stopped App's
     * dashboard items stay in place, disabled, rather than vanishing.
     */
    private val declarations = ConcurrentHashMap<String, DeclaredContribution>()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _activeAppId = MutableStateFlow<String?>(null)
    val activeAppId: StateFlow<String?> = _activeAppId.asStateFlow()

    /** Live registrars for Apps that currently serve, so their routes can be removed on teardown. */
    private val registrars = ConcurrentHashMap<String, AppRouteRegistrar>()

    /**
     * The platform server Apps publish onto, when it is running.
     *
     * Set by the always-on host. Null while the host is stopped, in which case Apps still run and
     * contribute their UI, but nothing is exposed on the network.
     */
    var server: EmbeddedServer? = null

    /**
     * Installs an App's routes, if the App serves and the platform server is up.
     *
     * Called after the instance is created. Idempotent, so a repeated start does not double-register.
     */
    private fun installRoutes(appId: String) {
        val instance = instances[appId] as? AppServer ?: return
        val activeServer = server ?: return
        if (registrars.containsKey(appId)) return

        val registrar = AppRouteRegistrar(
            appId = appId,
            server = activeServer,
            isEnabled = { instances.containsKey(appId) },
            onUnregister = { path -> logger.i("AppManager", "App '$appId' removed route $path") }
        )
        runCatching { instance.install(registrar) }
            .onFailure { logger.e("AppManager", "App '$appId' failed to install routes: ${it.message}") }
        registrars[appId] = registrar
        if (registrar.routes().isNotEmpty()) {
            logger.i(
                "AppManager",
                "App '$appId' serving ${registrar.routes().size} route(s) under ${registrar.resolve("")}"
            )
        }
    }

    /** Removes an App's routes. Called when the App is switched off. */
    private fun uninstallRoutes(appId: String, instance: PlatformApp? = instances[appId]) {
        registrars.remove(appId)?.let { registrar ->
            runCatching { registrar.uninstall() }
                .onFailure { logger.w("AppManager", "Error removing routes for '$appId': ${it.message}") }
        }
        (instance as? AppServer)?.let { serving ->
            runCatching { serving.uninstall() }
                .onFailure { logger.w("AppManager", "Error in uninstall for '$appId': ${it.message}") }
        }
    }

    /**
     * Registers an App's declaration. This does *not* create an instance.
     *
     * If the user already has this App switched on, the instance is created now — so an App
     * registered after startup still honours persisted toggle state.
     */
    fun register(registration: AppRegistration) {
        registrations[registration.id] = registration

        if (toggleStore.isEnabled(registration.id)) {
            createInstance(registration)
        }
        publish()
        logger.i("AppManager", "Registered app '${registration.name}' [${registration.id}]")
    }

    /** Registers many Apps at once, in declared order. */
    fun registerAll(items: List<AppRegistration>) {
        items.forEach { register(it) }
    }

    /**
     * The user-facing safety switch.
     *
     * On  → one instance is created and initialised.
     * Off → the instance is destroyed and all its state is cleared.
     *
     * The choice is persisted, so it survives restarts.
     */
    fun setEnabled(appId: String, enabled: Boolean) {
        val registration = registrations[appId] ?: run {
            logger.w("AppManager", "Cannot toggle unknown app: $appId")
            return
        }

        toggleStore.setEnabled(appId, enabled)

        if (enabled) {
            if (instances.containsKey(appId)) {
                logger.i("AppManager", "App already running, ignoring enable: $appId")
            } else {
                createInstance(registration)
            }
        } else {
            destroyInstance(appId)
            if (_activeAppId.value == appId) _activeAppId.value = null
        }
        publish()
    }

    /** The live instance, or null when the App is switched off. */
    fun instanceOf(appId: String): PlatformApp? = instances[appId]

    /**
     * Every route Apps currently publish, paired with the App that owns it.
     *
     * Read by the docs endpoint so App documentation is generated from the routes Apps actually
     * registered, rather than maintained separately.
     */
    fun publishedRoutes(): List<Pair<String, List<AppRoute>>> =
        registrars.mapNotNull { (appId, registrar) ->
            registrar.routes().takeIf { it.isNotEmpty() }?.let { appId to it }
        }

    /**
     * A scope for the App, usable from anywhere the manager is reachable.
     *
     * This is how an App posts notifications from `onInitialize`, where no scope has been handed
     * to it yet. It resolves the same live instance the card and the full screen resolve, so there
     * is still exactly one instance per App.
     */
    fun appScope(appId: String): AppScope =
        AppScopeImpl(this, appId, platformContext, channelManager, logger)

    fun isEnabled(appId: String): Boolean = toggleStore.isEnabled(appId)

    /** Opens an App's own screen. Only possible when it is running. */
    fun openApp(appId: String): Boolean {
        if (!instances.containsKey(appId)) {
            logger.w("AppManager", "Cannot open disabled app: $appId")
            return false
        }
        _activeAppId.value = appId
        return true
    }

    fun closeActiveApp() {
        _activeAppId.value = null
    }

    suspend fun dispatchCommandToApp(command: RemoteCommand): CommandResult {
        val app = instances[command.targetPluginId]
            ?: return CommandResult.failure(
                command.id,
                "App '${command.targetPluginId}' is not running",
                "APP_NOT_RUNNING"
            )

        return try {
            app.handleCommand(command)
        } catch (e: Exception) {
            CommandResult.failure(command.id, "Error in app ${app.name}: ${e.message}")
        }
    }

    /** Periodic work runs only for running Apps. */
    suspend fun runPeriodicSync() {
        instances.values.forEach { app ->
            try {
                app.onPeriodicSync()
            } catch (e: Exception) {
                logger.w("AppManager", "Periodic sync error in ${app.name}", e.message)
            }
        }
    }

    /** Tears down every App. Called on platform shutdown. */
    fun releaseAll() {
        instances.keys.toList().forEach { destroyInstance(it) }
        publish()
    }

    private fun createInstance(registration: AppRegistration) {
        try {
            val app = registration.factory()
            app.onInitialize(platformContext)
            instances[registration.id] = app

            // An App that serves gets its endpoints published as soon as it starts.
            installRoutes(registration.id)

            // Remember what it contributes so the arrangement survives teardown.
            val contribution = app.contribution
            declarations[registration.id] = DeclaredContribution(
                hasCard = contribution.dashboardCard != null,
                actionLabels = contribution.dashboardActions.map { it.id to it.label }
            )

            logger.i("AppManager", "Started app '${registration.name}' [${registration.id}]")
        } catch (e: Exception) {
            // A failing App must not take the platform with it. It stays switched on in the user's
            // eyes but is treated as not running, so it cannot be dispatched to.
            logger.e("AppManager", "Failed to start app ${registration.id}: ${e.message}", e.stackTraceToString())
        }
    }

    private fun destroyInstance(appId: String) {
        val app = instances[appId] ?: return
        // Remove routes and let the App release serving resources *before* the instance goes away,
        // so nothing is left answering for a destroyed App.
        uninstallRoutes(appId, app)
        instances.remove(appId)
        try {
            app.onDestroy()
        } catch (e: Exception) {
            logger.w("AppManager", "Error tearing down app $appId: ${e.message}")
        }
        logger.i("AppManager", "Stopped app '$appId' and cleared its state")
    }

    private fun publish() {
        _apps.value = registrations.values
            .sortedBy { it.order }
            .map { registration ->
                AppEntry(
                    registration = registration,
                    isEnabled = toggleStore.isEnabled(registration.id),
                    instance = instances[registration.id],
                    declared = declarations[registration.id] ?: DeclaredContribution()
                )
            }
    }
}