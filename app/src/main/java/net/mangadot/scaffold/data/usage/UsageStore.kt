package net.mangadot.scaffold.data.usage

import android.content.Context
import android.os.SystemClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * How long each app was on screen, day by day, kept on this phone for [KEEP_DAYS] days. Only time is
 * kept, under the app's id, never what was looked at.
 */
object UsageStore {

    private const val PREFS_NAME = "scraffold_usage"
    private const val KEEP_DAYS = 30L
    // A span longer than this is a clock that jumped, not someone reading
    private const val MAX_SPAN_MS = 12L * 60 * 60 * 1000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(day: LocalDate, appId: String) = "$day|$appId"

    /** Adds the time from [startMs] to [endMs] to [appId], split at midnight where it crosses one. */
    fun add(context: Context, appId: String, startMs: Long, endMs: Long) {
        if (appId.isEmpty() || endMs <= startMs || endMs - startMs > MAX_SPAN_MS) return
        val zone = ZoneId.systemDefault()
        val prefs = prefs(context)
        val edit = prefs.edit()
        var from = startMs
        while (from < endMs) {
            val day = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
            val nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = minOf(endMs, nextMidnight)
            val key = key(day, appId)
            edit.putLong(key, prefs.getLong(key, 0L) + (to - from))
            from = to
        }
        edit.apply()
        prune(context)
    }

    /** Each app's time on [day], in milliseconds, longest first. */
    fun forDay(context: Context, day: LocalDate): List<Pair<String, Long>> {
        val prefix = "$day|"
        return prefs(context).all.mapNotNull { (key, value) ->
            if (key.startsWith(prefix) && value is Long && value > 0) key.removePrefix(prefix) to value else null
        }.sortedByDescending { it.second }
    }

    fun totalFor(context: Context, day: LocalDate): Long = forDay(context, day).sumOf { it.second }

    /** Forgets everything. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prune(context: Context) {
        val oldest = LocalDate.now().minusDays(KEEP_DAYS)
        val prefs = prefs(context)
        val stale = prefs.all.keys.filter { key ->
            runCatching { LocalDate.parse(key.substringBefore('|')) }.getOrNull()?.isBefore(oldest) ?: true
        }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach(::remove) }.apply()
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
