package com.personal.base.core.app

import android.content.Context
import android.content.SharedPreferences

/**
 * The user's dashboard arrangement: which items are visible, and in what order.
 *
 * The dashboard starts empty and stays empty until Apps contribute. The user alone controls what is
 * shown and where, so that arrangement is persisted and restored.
 */
interface DashboardLayoutStoreContract {
    /** Visible item ids in display order. Items never seen before are appended by declaration order. */
    fun visibleItems(): List<String>
    fun setVisibleItems(order: List<String>)

    fun isHidden(itemId: String): Boolean
    fun setHidden(itemId: String, hidden: Boolean)

    /** Moves an item to a new index among the visible items. */
    fun move(itemId: String, toIndex: Int)
}

/**
 * Persisted dashboard arrangement backed by SharedPreferences.
 */
class DashboardLayoutStore(context: Context) : DashboardLayoutStoreContract {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun visibleItems(): List<String> =
        prefs.getString(KEY_ORDER, "")
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    override fun setVisibleItems(order: List<String>) {
        prefs.edit().putString(KEY_ORDER, order.joinToString(SEPARATOR)).apply()
    }

    override fun isHidden(itemId: String): Boolean =
        prefs.getStringSet(KEY_HIDDEN, emptySet())?.contains(itemId) == true

    override fun setHidden(itemId: String, hidden: Boolean) {
        val hiddenSet = prefs.getStringSet(KEY_HIDDEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (hidden) hiddenSet.add(itemId) else hiddenSet.remove(itemId)
        prefs.edit().putStringSet(KEY_HIDDEN, hiddenSet).apply()
    }

    override fun move(itemId: String, toIndex: Int) {
        val current = visibleItems().toMutableList()
        val fromIndex = current.indexOf(itemId)
        if (fromIndex < 0 || toIndex < 0 || toIndex >= current.size) return
        current.removeAt(fromIndex)
        current.add(toIndex, itemId)
        setVisibleItems(current)
    }

    private companion object {
        const val PREFS_NAME = "personal_base_dashboard_layout"
        const val KEY_ORDER = "visible_order"
        const val KEY_HIDDEN = "hidden_items"
        const val SEPARATOR = ""
    }
}