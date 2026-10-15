package com.personal.base.core.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.command.CommandResult
import com.personal.base.core.command.RemoteCommand
import com.personal.base.core.plugin.PlatformContext

/**
 * What an App declares it contributes to the platform.
 *
 * Every field except [id]/[name] is optional. An App is never forced to use the dashboard or the
 * settings — a headless App simply declares nothing here.
 */
data class AppContribution(
    /** The dashboard Card this App shows. Null means no card. */
    val dashboardCard: (@Composable (AppScope) -> Unit)? = null,

    /** Actions offered by this App on the dashboard. May be empty. */
    val dashboardActions: List<DashboardAction> = emptyList(),

    /** The App's settings section. Null means no settings. */
    val settingsSection: (@Composable (AppScope) -> Unit)? = null,

    /**
     * The App's home-screen widget.
     *
     * Declared as data rather than as a view, so the platform's one widget provider renders it.
     * An App therefore still needs nothing but this file — no RemoteViews, no layout XML, and no
     * manifest entry in the shell.
     */
    val widget: WidgetSpec? = null
)

/**
 * A single dashboard Action contributed by an App.
 */
data class DashboardAction(
    val id: String,
    val label: String,
    val icon: ImageVector? = null,
    val onInvoke: suspend () -> Unit = {}
)

/**
 * The scope handed to a contributed composable.
 *
 * This is the *same instance* the App registered — the card, the settings section, and the App's
 * own screen all read one live object. There is never a second copy.
 */
interface AppScope {
    /** The App's id. */
    val appId: String

    /** Whether the user currently has this App switched on. */
    val isEnabled: Boolean

    /** Safe platform facade for capability access. */
    val context: PlatformContext

    /** Switch the App on or off. Off means: torn down, no instance, state cleared. */
    fun setEnabled(enabled: Boolean)

    /**
     * Posts a notification to this App's own channel.
     *
     * An App must use this rather than `context.postNotification`, which targets a platform
     * channel. Posting to the App channel is what makes the per-App mute control in Settings work;
     * a notification sent to a platform channel cannot be silenced per App.
     *
     * The channel is created on first use, so no setup is required.
     */
    fun postNotification(title: String, message: String, priorityHigh: Boolean = false)

    /**
     * Shows or updates this App's home-screen widget.
     *
     * The App's toggle governs it like everything else: switching the App off withdraws the widget
     * rather than leaving a tile on someone's home screen that no longer works.
     */
    fun publishWidget(spec: WidgetSpec?)

    /** Updates the status line of the widget already published, if there is one. */
    fun updateWidgetStatus(status: String)

    /**
     * Report a long-running job in the shade, with the same bar and controls the platform uses for
     * its own work.
     *
     * An App writing its own progress notification ends up with a subtly different one, and puts
     * notification policy in every App. This is the platform's, so a retry button obeys the same
     * capability gate as everything else.
     *
     * @param percent ignored when [totalBytes] is 0, which makes the bar indeterminate rather than
     *   showing 0% for something that is nearly finished.
     */
    fun showProgress(
        id: String,
        title: String,
        detail: String,
        percent: Int = 0,
        totalBytes: Long = 0
    )

    /** The job finished. Left in the shade so the result is still readable. */
    fun showProgressComplete(id: String, title: String, detail: String)

    /** The job failed, with the reason shown. */
    fun showProgressFailed(id: String, title: String, reason: String)
}

/**
 * The contract every App implements.
 *
 * An App has no size limit. A single file and a full system are equally ordinary — this is a
 * boundary, not a template for how much code follows it.
 *
 * An instance exists only while the user has the App switched on. Switching it off destroys the
 * instance and clears its state; switching it on creates exactly one.
 */
interface PlatformApp {
    val id: String
    val name: String
    val icon: ImageVector
    val description: String
    val version: String
    val category: String get() = "Tools"
    val requiredCapabilities: Set<CapabilityType> get() = emptySet()

    /** What this App contributes to the dashboard and settings. */
    val contribution: AppContribution get() = AppContribution()

    /** Called once when the instance is created, i.e. when the user switches the App on. */
    fun onInitialize(context: PlatformContext) {}

    /** Optional periodic work invoked by the platform background worker. */
    suspend fun onPeriodicSync() {}

    /** Optional command handler. */
    suspend fun handleCommand(command: RemoteCommand): CommandResult {
        return CommandResult.success(command.id, "Action executed by $name")
    }

    /** Called when the instance is destroyed, i.e. when the user switches the App off. */
    fun onDestroy() {}

    /**
     * The App's own screen, opened by tapping its dashboard card. Optional: an App that returns
     * null simply has no full screen.
     */
    @Composable
    fun AppContent(scope: AppScope, onBack: () -> Unit): Unit = Unit
}