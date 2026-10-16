package com.personal.base.shell

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.personal.base.MainActivity
import com.personal.base.PersonalBaseApplication
import com.personal.base.R
import com.personal.base.core.app.PublishedWidget
import com.personal.base.core.app.WidgetSpec

/**
 * The one widget provider, for every App.
 *
 * Declared once by the shell and rendering whatever Apps publish, so adding a widget App is still
 * a single Kotlin file with no manifest entry. That is the whole reason this exists rather than
 * each App bringing its own [AppWidgetProvider] — an App must never require editing the shell.
 *
 * RemoteViews can only render a short list of views, which is why a widget is declared as data
 * ([WidgetSpec]) rather than as a Card. A Compose card cannot be reused here, and pretending
 * otherwise would mean every widget written twice.
 */
class PlatformWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // A widget redraw means someone is looking at a capability, so the runtime comes up.
        (context.applicationContext as? com.personal.base.PersonalBaseApplication)
            ?.container?.ensurePlatformRuntime()
        render(context, manager, ids)
    }

    override fun onEnabled(context: Context) {
        // A widget added to the home screen before any App published one should show something
        // honest rather than a stale tile.
        refresh(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) = Unit

    private fun registry(context: Context) =
        (context.applicationContext as? PersonalBaseApplication)?.container?.widgetRegistry

    /** Redraws every tile the launcher is currently showing. */
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(
            android.content.ComponentName(context, PlatformWidgetProvider::class.java)
        )
        if (ids.isNotEmpty()) render(context, manager, ids)
    }

    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val widgets = registry(context)?.visible().orEmpty()

        // Every tile the launcher holds is redrawn, and a tile whose App has stopped publishing
        // falls back to the empty state. That is what stops a switched-off App leaving a dead
        // icon behind — it is greyed and honest rather than showing stale numbers.
        val byWidgetId = widgets.associateBy { it.spec.id }

        ids.forEach { id ->
            manager.updateAppWidget(id, buildViews(context, byWidgetId[id.toString()]))
        }

        if (widgets.isEmpty() && ids.isNotEmpty()) {
            logger(context)?.i(
                "PlatformWidget",
                "No App is publishing a widget; ${ids.size} tile(s) show the empty state"
            )
        }
    }

    private fun buildViews(context: Context, widget: PublishedWidget?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_platform)

        if (widget == null) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_empty))
            views.setTextViewText(R.id.widget_subtitle, "")
            views.setTextViewText(R.id.widget_status, "")
            views.setViewVisibility(R.id.widget_icon, android.view.View.INVISIBLE)
            views.setViewVisibility(R.id.widget_progress, android.view.View.GONE)
            views.setOnClickPendingIntent(R.id.widget_root, openApp(context, null))
            return views
        }

        val spec = widget.spec
        views.setTextViewText(R.id.widget_title, spec.title)
        views.setTextViewText(R.id.widget_subtitle, spec.subtitle)
        views.setTextViewText(R.id.widget_status, spec.status)
        views.setImageViewResource(R.id.widget_icon, iconFor(spec.icon))

        views.setViewVisibility(
            R.id.widget_icon,
            if (spec.icon == null) android.view.View.INVISIBLE else android.view.View.VISIBLE
        )

        val progress = spec.progress
        if (progress == null) {
            views.setViewVisibility(R.id.widget_progress, android.view.View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_progress, android.view.View.VISIBLE)
            views.setProgressBar(R.id.widget_progress, 100, progress, false)
        }

        // Tapping goes straight into the App — which is the whole point of having a tile.
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context, widget))
        return views
    }

    private fun openApp(context: Context, widget: PublishedWidget?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            widget?.let {
                putExtra(MainActivity.EXTRA_OPEN_APP, it.appId)
                it.spec.route?.let { route -> putExtra(MainActivity.EXTRA_OPEN_ROUTE, route) }
            }
        }
        // A per-widget request code, or every tile would reuse one PendingIntent and the last
        // one written would win — so tapping any tile would open the last App published.
        val requestCode = widget?.spec?.id?.hashCode() ?: 0
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Framework icons, because an App cannot contribute a drawable resource to the shell.
     * The same reasoning as notification button icons.
     */
    private fun iconFor(name: String?): Int = when (name?.lowercase()) {
        WidgetSpec.ICON_CHECK -> android.R.drawable.ic_menu_send
        WidgetSpec.ICON_ALARM -> android.R.drawable.ic_menu_recent_history
        WidgetSpec.ICON_SYNC -> android.R.drawable.ic_popup_sync
        WidgetSpec.ICON_DOWNLOAD -> android.R.drawable.stat_sys_download
        WidgetSpec.ICON_CAMERA -> android.R.drawable.ic_menu_camera
        WidgetSpec.ICON_MIC -> android.R.drawable.ic_btn_speak_now
        WidgetSpec.ICON_LOCATION -> android.R.drawable.ic_menu_mylocation
        WidgetSpec.ICON_SMS -> android.R.drawable.ic_dialog_email
        WidgetSpec.ICON_STORAGE -> android.R.drawable.ic_menu_save
        WidgetSpec.ICON_NETWORK -> android.R.drawable.ic_menu_upload
        else -> android.R.drawable.ic_menu_more
    }

    private fun logger(context: Context) =
        (context.applicationContext as? PersonalBaseApplication)?.container?.logger
}