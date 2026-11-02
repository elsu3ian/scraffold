package net.mangadot.scaffold.ui.shelf

import android.content.Context
import net.mangadot.scaffold.data.db.entity.WebAppEntity

enum class ShelfSort(private val startsDescending: Boolean) {
    RECENT(true), NAME(false), DATE(true), CUSTOM(false);

    /** Custom has no direction: the order is whatever was arranged by hand. */
    val hasDirection: Boolean get() = this != CUSTOM

    fun applyTo(apps: List<WebAppEntity>, descending: Boolean): List<WebAppEntity> {
        val ascending = when (this) {
            RECENT -> apps.sortedWith(compareBy<WebAppEntity> { it.lastLaunchedAt }.thenBy { it.createdAt })
            NAME -> apps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            DATE -> apps.sortedBy { it.createdAt }
            // Apps added since the order was last arranged go at the end
            CUSTOM -> return apps.sortedWith(compareBy<WebAppEntity> { if (it.shelfOrder == 0) Int.MAX_VALUE else it.shelfOrder }.thenBy { it.createdAt })
        }
        return if (descending) ascending.reversed() else ascending
    }

    fun label(descending: Boolean): String = when (this) {
        RECENT -> if (descending) "Recently used" else "Least recently used"
        NAME -> if (descending) "Z to A" else "A to Z"
        DATE -> if (descending) "Date added (newest first)" else "Date added (oldest first)"
        CUSTOM -> "Custom (hold an app and drag)"
    }

    /** Each sort remembers its own direction. */
    fun isDescending(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DESCENDING + name, startsDescending)
    }

    fun setDescending(context: Context, descending: Boolean) {
        prefs(context).edit().putBoolean(KEY_DESCENDING + name, descending).apply()
    }

    companion object {
        private const val PREFS_NAME = "scraffold_shelf"
        private const val KEY_SORT = "sort"
        private const val KEY_DESCENDING = "descending_"

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun load(context: Context): ShelfSort {
            return entries.getOrElse(prefs(context).getInt(KEY_SORT, RECENT.ordinal)) { RECENT }
        }

        fun save(context: Context, sort: ShelfSort) {
            prefs(context).edit().putInt(KEY_SORT, sort.ordinal).apply()
        }
    }
}
