package com.personal.base.core.app

import androidx.compose.runtime.Composable

/**
 * A single renderable item on the dashboard, resolved from an App's declaration plus the user's
 * arrangement.
 */
sealed interface DashboardItem {
    val key: String

    /** False when the contributing App is switched off. The item still occupies its place. */
    val isEnabled: Boolean

    /** A card contributed by an App. Tapping it opens the App's own screen. */
    data class Card(
        override val key: String,
        val appId: String,
        val appName: String,
        override val isEnabled: Boolean,
        /** Null when the App is stopped — the item renders as a disabled placeholder instead. */
        val content: (@Composable (AppScope) -> Unit)?
    ) : DashboardItem

    /** An action contributed by an App. Runs a single unit of work. */
    data class Action(
        override val key: String,
        val appId: String,
        val label: String,
        override val isEnabled: Boolean,
        val onInvoke: suspend () -> Unit
    ) : DashboardItem
}

/**
 * Resolves which dashboard items exist and in what order.
 *
 * Resolution rules:
 *  - only Apps that declare a contribution produce items;
 *  - a stopped App still produces its items, flagged disabled, so the arrangement the user built
 *    survives toggling an App off;
 *  - the user's saved order wins; anything never seen before is appended in declaration order;
 *  - hidden items are dropped.
 */
class DashboardComposer(
    private val layoutStore: DashboardLayoutStoreContract
) {

    fun compose(apps: List<AppEntry>): List<DashboardItem> {
        val declared = buildList {
            apps.forEach { entry ->
                val instance = entry.instance
                val snapshot = entry.declared

                // A running App contributes live. A stopped App contributes placeholders built
                // from what it last declared, so the arrangement survives its teardown.
                if (instance != null) {
                    instance.contribution.dashboardCard?.let { content ->
                        add(
                            DashboardItem.Card(
                                key = "card:${entry.id}",
                                appId = entry.id,
                                appName = entry.name,
                                isEnabled = entry.isEnabled,
                                content = content
                            )
                        )
                    }
                    instance.contribution.dashboardActions.forEach { action ->
                        add(
                            DashboardItem.Action(
                                key = "action:${entry.id}:${action.id}",
                                appId = entry.id,
                                label = action.label,
                                isEnabled = entry.isEnabled,
                                onInvoke = action.onInvoke
                            )
                        )
                    }
                } else {
                    if (snapshot.hasCard) {
                        add(
                            DashboardItem.Card(
                                key = "card:${entry.id}",
                                appId = entry.id,
                                appName = entry.name,
                                isEnabled = false,
                                content = null
                            )
                        )
                    }
                    snapshot.actionLabels.forEach { (actionId, label) ->
                        add(
                            DashboardItem.Action(
                                key = "action:${entry.id}:$actionId",
                                appId = entry.id,
                                label = label,
                                isEnabled = false,
                                onInvoke = {}
                            )
                        )
                    }
                }
            }
        }

        val visible = declared.filterNot { layoutStore.isHidden(it.key) }
        val byKey = visible.associateBy { it.key }.toMutableMap()

        // The user's saved order wins; unseen items keep their declaration order at the end.
        val savedOrder = layoutStore.visibleItems()
            .mapNotNull { byKey.remove(it) }
        return savedOrder + byKey.values
    }
}