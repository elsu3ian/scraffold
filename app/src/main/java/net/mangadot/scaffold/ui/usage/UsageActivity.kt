package net.mangadot.scaffold.ui.usage

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
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
import java.time.format.TextStyle
import java.util.Locale

/** How long each app was on screen: the last week as bars, and the chosen day app by app. */
class UsageActivity : CosmicActivity() {

    private lateinit var binding: ActivityUsageBinding
    private var apps: Map<String, WebAppEntity> = emptyMap()
    private val week = (WEEK_DAYS - 1 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
    private var chosen = WEEK_DAYS - 1

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
            chosen = index
            showDay()
        }

        lifecycleScope.launch {
            apps = WebAppRepository(AppDatabase.getInstance(this@UsageActivity).webAppDao())
                .allWebApps.first().associateBy { it.id }
            show()
        }
    }

    private fun show() {
        binding.usageChart.days = week.map { day ->
            UsageChart.Day(day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), UsageStore.totalFor(this, day))
        }
        binding.usageChart.selected = chosen
        showDay()
    }

    private fun showDay() {
        val day = week[chosen]
        binding.usageTvDay.text = when (day) {
            LocalDate.now() -> "TODAY"
            LocalDate.now().minusDays(1) -> "YESTERDAY"
            else -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()).uppercase()
        }
        val byApp = UsageStore.forDay(this, day)
        val total = byApp.sumOf { it.second }
        binding.usageTvTotal.text = UsageStore.format(total)
        val average = week.sumOf { UsageStore.totalFor(this, it) } / WEEK_DAYS
        binding.usageTvAverage.text = "Daily average this week: ${UsageStore.format(average)}"

        binding.usageList.removeAllViews()
        binding.usageTvEmpty.visibility = if (byApp.isEmpty()) View.VISIBLE else View.GONE
        val most = byApp.firstOrNull()?.second ?: 1L
        byApp.forEach { (id, millis) -> binding.usageList.addView(row(id, millis, most)) }
    }

    /** An app's icon, name and time, over a bar as long as its share of the day's longest. */
    private fun row(id: String, millis: Long, most: Long): View {
        val app = apps[id]
        val name = when {
            id == QuickBrowserActivity.BROWSER_ID -> "Browser"
            app != null -> app.name
            else -> "Removed app"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
        }
        row.addView(icon(id, app, name), LinearLayout.LayoutParams(dp(32), dp(32)))

        val middle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = name
            textSize = 13f
            maxLines = 1
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
        val track = FrameLayout(this).apply {
            background = rounded(ColorUtils.setAlphaComponent(CosmicAccent.color, 40))
        }
        val fill = View(this).apply { background = rounded(CosmicAccent.color) }
        track.addView(fill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        track.post {
            fill.layoutParams = fill.layoutParams.apply { width = maxOf((track.width * millis / most).toInt(), dp(4)) }
        }
        middle.addView(title)
        middle.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(6) })
        row.addView(middle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
        })

        row.addView(TextView(this).apply {
            text = UsageStore.format(millis)
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        })
        return row
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
                image.setPadding(dp(7), dp(7), dp(7), dp(7))
                monogram.visibility = View.GONE
            }
        }
        frame.clipToOutline = true
        return frame
    }

    private fun rounded(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(2).toFloat()
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
        const val WEEK_DAYS = 7
    }
}
