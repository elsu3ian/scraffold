package net.mangadot.scaffold.data.usage

import android.content.Context
import android.os.SystemClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * How long each app was on screen, day by day for [KEEP_DAYS] days and hour by hour for
 * [KEEP_HOURS_DAYS], kept on this phone. Only time is kept, under the app's id, never what was looked at.
 */
object UsageStore {

    private const val PREFS_NAME = "scraffold_usage"
    private const val KEEP_DAYS = 366L
    private const val HOURS_PREFS_NAME = "scraffold_usage_hours"
    // Hours make many more entries, so fewer days of them are kept
    const val KEEP_HOURS_DAYS = 31L
    // A span longer than this is a clock that jumped, not someone reading
    private const val MAX_SPAN_MS = 12L * 60 * 60 * 1000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun hourPrefs(context: Context) = context.getSharedPreferences(HOURS_PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(day: LocalDate, appId: String) = "$day|$appId"

    private fun hourKey(day: LocalDate, hour: Int, appId: String) = "$day|$hour|$appId"

    /** Adds the time from [startMs] to [endMs] to [appId], split at each hour it crosses. */
    fun add(context: Context, appId: String, startMs: Long, endMs: Long) {
        if (appId.isEmpty() || endMs <= startMs || endMs - startMs > MAX_SPAN_MS) return
        val zone = ZoneId.systemDefault()
        val prefs = prefs(context)
        val hours = hourPrefs(context)
        val edit = prefs.edit()
        val hourEdit = hours.edit()
        var from = startMs
        while (from < endMs) {
            val at = Instant.ofEpochMilli(from).atZone(zone)
            val day = at.toLocalDate()
            val nextHour = at.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val to = minOf(endMs, nextHour)
            val key = key(day, appId)
            edit.putLong(key, prefs.getLong(key, 0L) + (to - from))
            val hourKey = hourKey(day, at.hour, appId)
            hourEdit.putLong(hourKey, hours.getLong(hourKey, 0L) + (to - from))
            from = to
        }
        edit.apply()
        hourEdit.apply()
        prune(context)
    }

    /** Each hour of [day] that has any, 0 to 23, with its apps' time in milliseconds. */
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

    /** Each app's time on [day], in milliseconds, longest first. */
    fun forDay(context: Context, day: LocalDate): List<Pair<String, Long>> {
        val prefix = "$day|"
        return prefs(context).all.mapNotNull { (key, value) ->
            if (key.startsWith(prefix) && value is Long && value > 0) key.removePrefix(prefix) to value else null
        }.sortedByDescending { it.second }
    }

    fun totalFor(context: Context, day: LocalDate): Long = forDay(context, day).sumOf { it.second }

    /** Every day kept, each with its apps' time in milliseconds, read in one go for a long range. */
    fun byDay(context: Context): Map<LocalDate, Map<String, Long>> {
        val days = HashMap<LocalDate, HashMap<String, Long>>()
        prefs(context).all.forEach { (key, value) ->
            if (value !is Long || value <= 0) return@forEach
            val day = runCatching { LocalDate.parse(key.substringBefore('|')) }.getOrNull() ?: return@forEach
            days.getOrPut(day) { HashMap() }[key.substringAfter('|')] = value
        }
        return days
    }

    /** Forgets everything. */
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
        val oldestHours = LocalDate.now().minusDays(KEEP_HOURS_DAYS)
        val hours = hourPrefs(context)
        val staleHours = hours.all.keys.filter { key ->
            runCatching { LocalDate.parse(key.substringBefore('|')) }.getOrNull()?.isBefore(oldestHours) ?: true
        }
        if (staleHours.isNotEmpty()) hours.edit().apply { staleHours.forEach(::remove) }.apply()
    }

    /** Short and readable: "45 s", "12 min", "1 h 5 min". */
    fun format(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            ms < 60_000 -> "${ms / 1000} s"
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    /**
     * Times one screen while it's in front. Call [start] when it resumes and [stop] when it pauses;
     * what [appId] returns at [stop] gets the time.
     */
    class Timer(private val context: Context, private val appId: () -> String?) {
        private var startedWall = 0L
        private var startedClock = 0L

        fun start() {
            startedWall = System.currentTimeMillis()
            startedClock = SystemClock.elapsedRealtime()
        }

        fun stop() {
            if (startedClock == 0L) return
            // The span comes from a clock that can't be set back, and its day from the wall clock
            val span = SystemClock.elapsedRealtime() - startedClock
            startedClock = 0L
            val id = appId() ?: return
            add(context.applicationContext, id, startedWall, startedWall + span)
        }
    }
}
