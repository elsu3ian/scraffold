package net.mangadot.scaffold.ui.usage

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.data.repository.WebAppRepository
import net.mangadot.scaffold.data.usage.UsageStore
import net.mangadot.scaffold.databinding.ActivityUsageBinding
import net.mangadot.scaffold.engine.AppIcons
import net.mangadot.scaffold.ui.browser.QuickBrowserActivity
import net.mangadot.scaffold.ui.theme.CosmicAccent
import net.mangadot.scaffold.ui.theme.CosmicActivity
import net.mangadot.scaffold.ui.theme.CosmicThemeStore
import net.mangadot.scaffold.ui.theme.applyHeader
import net.mangadot.scaffold.ui.theme.showCosmic
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * How long each app was on screen: a ring of each app's share with the total in the middle, a day
 * hour by hour or a span day by day or month by month as bars, and the apps in order of time.
 */
class UsageActivity : CosmicActivity() {

    private enum class Range(val label: String, val title: String, val days: Int, val chartTitle: String) {
        DAY("Day", "", 1, "HOUR BY HOUR"),
        WEEK("Week", "Last 7 days", 7, "DAY BY DAY"),
        TWO_WEEKS("14 days", "Last 14 days", 14, "DAY BY DAY"),
        MONTH("Month", "Last 30 days", 30, "DAY BY DAY"),
        YEAR("Year", "Last 12 months", 0, "MONTH BY MONTH")
    }

    /** One bar: an hour of the day, a day, or in the year a month. */
    private class Bucket(val label: String, val title: String, val byApp: Map<String, Long>)

    private lateinit var binding: ActivityUsageBinding
    private var apps: Map<String, WebAppEntity> = emptyMap()
    private var usage: Map<LocalDate, Map<String, Long>> = emptyMap()
    private var range = Range.DAY
    // The day Day shows, stepped back with the arrows as far as hours are kept
    private var day = LocalDate.now()
    private var buckets: List<Bucket> = emptyList()
    // A bar picked out, or -1 for all of them
    private var chosen = -1
    private val locale get() = Locale.getDefault()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUsageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CosmicThemeStore.load(this).let { theme ->
            theme.applyBackground(binding.usageRootLayout)
            theme.applyHeader(binding.usageHeaderLayout, binding.usageScroll, overlay = true)
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.usageRootLayout) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val header = binding.usageHeaderLayout
            header.setPadding(header.paddingLeft, top + dp(12), header.paddingRight, dp(12))
            insets
        }

        binding.usageBtnBack.setOnClickListener { finish() }
        binding.usageBtnClear.setOnClickListener { confirmClear() }
        binding.usageChart.onSelect = { index ->
            // Tapping the picked bar again goes back to all of them
            chosen = if (index == chosen) -1 else index
            binding.usageChart.selected = chosen
            showChosen()
        }
        binding.usageRange.setOnClickListener { pickRange() }
        binding.usageDayBack.setOnClickListener { stepDay(-1) }
        binding.usageDayForward.setOnClickListener { stepDay(1) }

        lifecycleScope.launch {
            apps = WebAppRepository(AppDatabase.getInstance(this@UsageActivity).webAppDao())
                .allWebApps.first().associateBy { it.id }
            show()
        }
    }

    /** The ranges in a small menu under the button, in the same card as the rest of the screen. */
    private fun pickRange() {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_solid)
            setPadding(0, dp(6), 0, dp(6))
        }
        val popup = PopupWindow(list, dp(MENU_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = dp(8).toFloat()
        }
        Range.values().forEach { option ->
            list.addView(TextView(this).apply {
                text = option.label
                textSize = 14f
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setTextColor(if (option == range) CosmicAccent.color else ContextCompat.getColor(context, R.color.text_primary))
                paint.isFakeBoldText = option == range
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    popup.dismiss()
                    if (range == option) return@setOnClickListener
                    range = option
                    day = LocalDate.now()
                    show()
                }
            })
        }
        popup.showAsDropDown(binding.usageRange, binding.usageRange.width - dp(MENU_WIDTH_DP), dp(4))
    }

    private fun stepDay(by: Long) {
        val next = day.plusDays(by)
        if (next.isAfter(LocalDate.now()) || next.isBefore(oldestDay())) return
        day = next
        show()
    }

    private fun oldestDay() = LocalDate.now().minusDays(UsageStore.KEEP_HOURS_DAYS - 1)

    private fun show() {
        usage = UsageStore.byDay(this)
        val today = LocalDate.now()
        binding.usageRange.text = "${range.label}  ▾"
        binding.usageChartTitle.text = range.chartTitle
        val dayView = range == Range.DAY
        binding.usageDayBack.visibility = if (dayView) View.VISIBLE else View.INVISIBLE
        binding.usageDayForward.visibility = if (dayView) View.VISIBLE else View.INVISIBLE
        binding.usageDayBack.alpha = if (day.isAfter(oldestDay())) 1f else 0.3f
        binding.usageDayForward.alpha = if (day.isBefore(today)) 1f else 0.3f
        binding.usageTvDay.text = if (dayView) dayTitle(day) else range.title

        buckets = when (range) {
            Range.DAY -> {
                val hours = UsageStore.byHour(this, day)
                (0..23).map { hour ->
                    val time = LocalTime.of(hour, 0)
                    // Every sixth hour is labelled, which is as many as fit
                    val label = if (hour % 6 == 0) time.format(HOUR.withLocale(locale)) else ""
                    val span = "${time.format(HOUR.withLocale(locale))} to ${time.plusHours(1).format(HOUR.withLocale(locale))}"
                    Bucket(label, span, hours[hour].orEmpty())
                }
            }
            Range.YEAR -> {
                val thisMonth = YearMonth.from(today)
                (11 downTo 0).map { back ->
                    val month = thisMonth.minusMonths(back.toLong())
                    val days = (1..month.lengthOfMonth()).map(month::atDay).filter { !it.isAfter(today) }
                    val name = month.month.getDisplayName(TextStyle.SHORT, locale)
                    Bucket(name, "${month.month.getDisplayName(TextStyle.FULL, locale)} ${month.year}", merged(days))
                }
            }
            else -> (range.days - 1 downTo 0).map { back ->
                val each = today.minusDays(back.toLong())
                // Many thin bars only label some, counted back from today
                val label = when (range) {
                    Range.WEEK -> each.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                    Range.MONTH -> if (back % 5 == 0) each.dayOfMonth.toString() else ""
                    else -> each.dayOfMonth.toString()
                }
                Bucket(label, dayTitle(each), usage[each].orEmpty())
            }
        }
        chosen = -1
        binding.usageChart.days = buckets.map { bucket -> UsageChart.Day(bucket.label, bucket.byApp.values.sum()) }
        binding.usageChart.selected = chosen
        showChosen()
    }

    private fun dayTitle(day: LocalDate): String {
        val today = LocalDate.now()
        return when {
            day == today -> "Today"
            day == today.minusDays(1) -> "Yesterday"
            day.isAfter(today.minusDays(7)) -> day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
        }
    }

    private fun merged(days: List<LocalDate>): Map<String, Long> {
        val byApp = HashMap<String, Long>()
        days.forEach { each -> usage[each]?.forEach { (id, ms) -> byApp[id] = (byApp[id] ?: 0L) + ms } }
        return byApp
    }

    private fun spanDays(): List<LocalDate> {
        val today = LocalDate.now()
        val count = when (range) {
            Range.DAY -> 7
            Range.YEAR -> 365
            else -> range.days
        }
        return (0 until count).map { today.minusDays(it.toLong()) }
    }

    private fun showChosen() {
        val picked = buckets.getOrNull(chosen)
        val dayView = range == Range.DAY
        // A whole day comes from the day's own count, which also covers time from before hours were kept
        val byApp = picked?.byApp ?: if (dayView) usage[day].orEmpty() else HashMap<String, Long>().also { all ->
            buckets.forEach { bucket -> bucket.byApp.forEach { (id, ms) -> all[id] = (all[id] ?: 0L) + ms } }
        }
        val sorted = byApp.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key to it.value }
        val total = sorted.sumOf { it.second }

        // The first few apps get a color each, and the rest share one as Other
        val colors = palette()
        val parts = sorted.take(colors.size).mapIndexed { index, (_, ms) -> UsageRing.Part(ms, colors[index]) } +
            UsageRing.Part(sorted.drop(colors.size).sumOf { it.second }, OTHER_COLOR)
        val caption = picked?.title ?: if (dayView) dayTitle(day) else range.title
        binding.usageRing.set(parts, UsageStore.format(total), caption)

        // Days before anything was counted are left out of the average
        val first = usage.keys.minOrNull()
        val days = spanDays()
        val counted = days.count { first != null && !it.isBefore(first) }.coerceAtLeast(1)
        val average = UsageStore.format(days.sumOf { usage[it]?.values?.sum() ?: 0L } / counted)
        binding.usageTvAverage.text = if (dayView) "Daily average this week: $average" else "Daily average: $average"

        binding.usageList.removeAllViews()
        binding.usageList.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
        binding.usageTvEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        sorted.forEachIndexed { index, (id, millis) ->
            if (index > 0) binding.usageList.addView(divider())
            binding.usageList.addView(row(id, millis, total, colors.getOrElse(index) { OTHER_COLOR }))
        }
    }

    private fun palette() = listOf(CosmicAccent.color) + EXTRA_COLORS.filter { !close(it, CosmicAccent.color) }.take(4)

    // An accent near one of the set colors would make two apps look the same
    private fun close(a: Int, b: Int): Boolean {
        val one = FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(a, it) }
        val two = FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(b, it) }
        val apart = Math.abs(one[0] - two[0]).let { minOf(it, 360 - it) }
        return apart < 25
    }

    /** An app's icon and name, with its color and time under the name, and its share on the right. */
    private fun row(id: String, millis: Long, total: Long, color: Int): View {
        val app = apps[id]
        val name = when {
            id == QuickBrowserActivity.BROWSER_ID -> "Browser"
            app != null -> app.name
            else -> "Removed app"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        row.addView(icon(id, app, name), LinearLayout.LayoutParams(dp(38), dp(38)))

        val middle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        middle.addView(TextView(this).apply {
            text = name
            textSize = 14f
            maxLines = 1
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })
        val time = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        time.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
        time.addView(TextView(this).apply {
            text = UsageStore.format(millis)
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_muted))
        })
        middle.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
        })
        row.addView(middle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(14)
            marginEnd = dp(12)
        })

        val share = if (total > 0) (100 * millis / total).toInt() else 0
        row.addView(TextView(this).apply {
            text = if (share < 1) "<1%" else "$share%"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        })
        return row
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(ContextCompat.getColor(context, R.color.border_primary))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(66)
        }
    }

    private fun icon(id: String, app: WebAppEntity?, name: String): View {
        val frame = FrameLayout(this).apply { setBackgroundResource(R.drawable.bg_squircle_emerald) }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val monogram = TextView(this).apply {
            text = name.take(1).uppercase()
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.accent_emerald))
        }
        frame.addView(monogram, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        when {
            app != null -> AppIcons.bind(image, monogram, app)
            id == QuickBrowserActivity.BROWSER_ID -> {
                image.setImageResource(R.drawable.ic_globe)
                image.scaleType = ImageView.ScaleType.CENTER_INSIDE
                image.setPadding(dp(8), dp(8), dp(8), dp(8))
                monogram.visibility = View.GONE
            }
        }
        frame.clipToOutline = true
        return frame
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear usage?")
            .setMessage("This forgets the time counted for every app.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                UsageStore.clear(this)
                show()
            }
            .showCosmic()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("h a")
        const val MENU_WIDTH_DP = 160
        // Violet, amber, cyan, rose and lime, for the apps after the first, which takes the accent
        val EXTRA_COLORS = listOf(
            Color.parseColor("#8B5CF6"), Color.parseColor("#F59E0B"), Color.parseColor("#06B6D4"),
            Color.parseColor("#F43F5E"), Color.parseColor("#84CC16"), Color.parseColor("#10B981")
        )
        val OTHER_COLOR = Color.parseColor("#52525B")
    }
}
