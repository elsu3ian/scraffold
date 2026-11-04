package net.mangadot.scaffold.ui.usage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import net.mangadot.scaffold.R
import net.mangadot.scaffold.ui.theme.CosmicAccent

/** A ring split into each app's share of the time, in the apps' colors, with the total in the middle. */
class UsageRing @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    class Part(val millis: Long, val color: Int)

    private var parts: List<Part> = emptyList()
    private var total = ""
    private var caption = ""

    fun set(parts: List<Part>, total: String, caption: String) {
        this.parts = parts.filter { it.millis > 0 }
        this.total = total
        this.caption = caption
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private val stroke = 16 * density
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.BUTT
    }
    private val totalText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 30 * density
        typeface = Typeface.DEFAULT_BOLD
        color = ContextCompat.getColor(context, R.color.text_primary)
    }
    private val captionText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12 * density
        color = ContextCompat.getColor(context, R.color.text_muted)
    }
    private val bounds = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // As tall as it is wide
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val size = minOf(width, (220 * density).toInt())
        setMeasuredDimension(width, size)
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val left = (width - size) / 2 + stroke / 2
        val top = (height - size) / 2 + stroke / 2
        bounds.set(left, top, left + size - stroke, top + size - stroke)

        // The empty ring, so a day with nothing still has its shape
        arc.color = ColorUtils.setAlphaComponent(CosmicAccent.color, 36)
        canvas.drawArc(bounds, 0f, 360f, false, arc)

        val sum = parts.sumOf { it.millis }.toFloat()
        if (sum > 0) {
            // A small gap between parts keeps neighbors apart, unless there is only one
            val gap = if (parts.size > 1) GAP_DEGREES else 0f
            var start = -90f
            parts.forEach { part ->
                val sweep = 360f * part.millis / sum
                arc.color = part.color
                canvas.drawArc(bounds, start + gap / 2, maxOf(sweep - gap, 0.8f), false, arc)
                start += sweep
            }
        }

        val middle = height / 2f
        canvas.drawText(total, width / 2f, middle + totalText.textSize * 0.25f, totalText)
        canvas.drawText(caption, width / 2f, middle + totalText.textSize * 0.25f + captionText.textSize * 1.8f, captionText)
    }

    private companion object {
        const val GAP_DEGREES = 2.5f
    }
}
