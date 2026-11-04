package net.mangadot.scaffold.data.usage

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * How much of the battery went while each app was on screen, day by day. Android doesn't say what
 * one app uses, so this is the battery's own drop over that time, the screen and anything running
 * alongside included. Time spent charging isn't counted.
 */
object BatteryUse {

    private const val PREFS_NAME = "scraffold_usage_battery"
    private const val KEEP_DAYS = 366L
    private const val HOURS_PREFS_NAME = "scraffold_usage_battery_hours"

    /** The battery at a moment: its charge in microamp hours where the phone reports it, and its percent. */
    class Reading(val charge: Long, val percent: Int)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun hourPrefs(context: Context) = context.getSharedPreferences(HOURS_PREFS_NAME, Context.MODE_PRIVATE)

    /** Null while charging, or when the phone won't say. */
    fun read(context: Context): Reading? {
        // The battery's last report, kept by the system for anyone who asks, without listening for the next
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        if (status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val percent = level * 100 / scale
        if (percent !in 1..100) return null
        val battery = context.getSystemService(BatteryManager::class.java) ?: return null
        val charge = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        return Reading(if (charge > 0) charge else 0L, percent)
    }

    /** Adds the drop from [start] to [end] to [appId], on the day and hour it was opened at [startMs]. */
    fun add(context: Context, appId: String, startMs: Long, start: Reading, end: Reading) {
        // The charge counter is far finer than whole percents, and the full charge follows from it and the
        // percent. Some phones never move it, so the percent counts then.
        val used = if (start.charge > 0 && end.charge > 0 && start.charge != end.charge) {
            val full = start.charge * 100.0 / start.percent
            (start.charge - end.charge) * 100.0 / full
        } else {
            (start.percent - end.percent).toDouble()
        }
        // A rise is a charger plugged in and out again, and a huge drop a reading gone wrong
        if (used <= 0.0 || used > 50.0) return
        val at = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault())
        val day = at.toLocalDate()
        val amount = Math.round(used * 1000)
        val key = "$day|$appId"
        val prefs = prefs(context)
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + amount).apply()
        val hourKey = "$day|${at.hour}|$appId"
        val hours = hourPrefs(context)
        hours.edit().putLong(hourKey, hours.getLong(hourKey, 0L) + amount).apply()
        prune(context)
    }

    /** Every day kept, each with its apps' battery use in thousandths of a percent. */
    fun byDay(context: Context): Map<LocalDate, Map<String, Long>> {
        val days = HashMap<LocalDate, HashMap<String, Long>>()
        prefs(context).all.forEach { (key, value) ->
            if (value !is Long || value <= 0) return@forEach
            val day = runCatching { LocalDate.parse(key.substringBefore('|')) }.getOrNull() ?: return@forEach
            days.getOrPut(day) { HashMap() }[key.substringAfter('|')] = value
        }
        return days
    }

    /** Each hour of [day] that has any, 0 to 23, with its apps' battery use in thousandths of a percent. */
    fun byHour(context: Context, day: LocalDate): Map<Int, Map<String, Long>> {
        val prefix = "$day|"
        val hours = HashMap<Int, HashMap<String, Long>>()
        hourPrefs(context).all.forEach { (key, value) ->
            if (!key.startsWith(prefix) || value !is Long || value <= 0) return@forEach
            val rest = key.removePrefix(prefix)
            val hour = rest.substringBefore('|').toIntOrNull() ?: return@forEach
            hours.getOrPut(hour) { HashMap() }[rest.substringAfter('|')] = value
        }
        return hours
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        hourPrefs(context).edit().clear().apply()
    }

    private fun prune(context: Context) {
        val oldest = LocalDate.now().minusDays(KEEP_DAYS)
        val prefs = prefs(context)
        val stale = prefs.all.keys.filter { key ->
            runCatching { LocalDate.parse(key.substringBefore('|')) }.getOrNull()?.isBefore(oldest) ?: true
        }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach(::remove) }.apply()
        // Hours are kept as long as screen time's are
        val oldestHours = LocalDate.now().minusDays(UsageStore.KEEP_HOURS_DAYS)
        val hours = hourPrefs(context)
        val staleHours = hours.all.keys.filter { key ->
            runCatching { LocalDate.parse(key.substringBefore('|')) }.getOrNull()?.isBefore(oldestHours) ?: true
        }
        if (staleHours.isNotEmpty()) hours.edit().apply { staleHours.forEach(::remove) }.apply()
    }

    /** "0.4%", "3.2%", "12%", from thousandths of a percent. */
    fun format(thousandths: Long): String = when {
        thousandths < 100 -> "<0.1%"
        thousandths < 10_000 -> String.format(Locale.getDefault(), "%.1f%%", thousandths / 1000.0)
        else -> "${Math.round(thousandths / 1000.0)}%"
    }
}
