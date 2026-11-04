package net.mangadot.scaffold.ui.usage

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import net.mangadot.scaffold.R
import net.mangadot.scaffold.ui.theme.CosmicAccent

/** One bar a day, the chosen day in the accent. Tapping a bar chooses its day. */
class UsageChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    class Day(val label: String, val millis: Long)

    var days: List<Day> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var selected = -1
        set(value) {
            field = value
            invalidate()
        }

    var onSelect: (Int) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10 * density
        textAlign = Paint.Align.CENTER
    }
    private val muted = ContextCompat.getColor(context, R.color.text_muted)
    private val primary = ContextCompat.getColor(context, R.color.text_primary)
    private val rect = RectF()

    private val labelSpace get() = 18 * density

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        val slot = width / days.size.toFloat()
        val barWidth = minOf(slot * 0.55f, 28 * density)
        val top = 4 * density
        val floor = height - labelSpace
        val most = days.maxOf { it.millis }.coerceAtLeast(1L)
        val radius = 6 * density
        val baseline = height - 4 * density
        days.forEachIndexed { index, day ->
            val center = slot * index + slot / 2
            val chosen = index == selected
            // Even an empty day keeps a sliver, so every day has somewhere to tap
            val barHeight = maxOf((floor - top) * day.millis / most, 3 * density)
            rect.set(center - barWidth / 2, floor - barHeight, center + barWidth / 2, floor)
            bar.color = if (chosen) CosmicAccent.color else ColorUtils.setAlphaComponent(CosmicAccent.color, 70)
            canvas.drawRoundRect(rect, radius, radius, bar)
            label.color = if (chosen) primary else muted
            label.isFakeBoldText = chosen
            canvas.drawText(day.label, center, baseline, label)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (days.isEmpty()) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val index = (event.x / (width / days.size.toFloat())).toInt().coerceIn(0, days.size - 1)
            selected = index
            onSelect(index)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
