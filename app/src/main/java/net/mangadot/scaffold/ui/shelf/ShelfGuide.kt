package net.mangadot.scaffold.ui.shelf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import net.mangadot.scaffold.R
import net.mangadot.scaffold.ui.theme.CosmicAccent
import net.mangadot.scaffold.ui.theme.CosmicCard

/**
 * Walks through the shelf the first time Scraffold opens: each step dims the screen except for the thing it's
 * about, with a note on what it does. A step without a target is a card on its own. Settings can play it again.
 */
class ShelfGuide(private val activity: ComponentActivity, private val steps: List<Step>, private val done: () -> Unit) {

    /**
     * [sticker], when set, is a small label on the corner of the spotlight, like DEMO. A target that is
     * [onlyHere] stays out of sight on every other step.
     */
    class Step(val target: View?, val title: String, val body: String, val sticker: String? = null, val onlyHere: Boolean = false)

    private val density = activity.resources.displayMetrics.density
    private val decor = activity.window.decorView as ViewGroup
    private val spotlight = Spotlight(activity)
    private val card = LinearLayout(activity)
    private val title = CosmicCard.title(activity, "")
    private val body = CosmicCard.body(activity)
    private val count = TextView(activity)
    private val next = CosmicCard.button(activity, "Next", main = true) { advance() }
    private val skip = CosmicCard.button(activity, "Skip", main = false) { finish() }
    private val overlay = FrameLayout(activity)
    private var index = 0
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = finish()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val pad = (16 * density).toInt()
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(pad, pad, pad, (8 * density).toInt())
        card.background = CosmicCard.background(activity)
        card.elevation = 8 * density
        body.setPadding(0, (6 * density).toInt(), 0, (8 * density).toInt())
        count.setTextColor(ContextCompat.getColor(activity, R.color.text_muted))
        count.textSize = 11f
        val buttons = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(skip)
            addView(next)
        }
        card.addView(title)
        card.addView(body)
        card.addView(buttons)

        overlay.addView(spotlight, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.addView(card)
        // Nothing underneath reacts while the guide is up
        overlay.setOnTouchListener { _, _ -> true }
        overlay.elevation = 32 * density
        overlay.alpha = 0f
        decor.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        activity.onBackPressedDispatcher.addCallback(activity, back)
        steps.forEach { if (it.onlyHere) it.target?.alpha = 0f }
        overlay.post {
            showStep()
            overlay.animate().alpha(1f).setDuration(FADE_MS).start()
        }
    }

    private fun advance() {
        if (index == steps.lastIndex) finish() else {
            index++
            showStep()
        }
    }

    private fun showStep() {
        val step = steps[index]
        title.text = step.title
        body.text = step.body
        count.text = "${index + 1} of ${steps.size}"
        next.text = if (index == steps.lastIndex) "Done" else "Next"
        // Nothing is left to skip on the last step
        skip.visibility = if (index == steps.lastIndex) View.GONE else View.VISIBLE
        steps.forEach { other ->
            if (other.onlyHere) other.target?.animate()?.alpha(if (other === step) 1f else 0f)?.setDuration(FADE_MS)?.start()
        }

        val hole = step.target?.takeIf { it.isShown && it.width > 0 }?.let { target ->
            val where = IntArray(2).also { target.getLocationInWindow(it) }
            val grow = HOLE_PADDING_DP * density
            // Kept inside the screen, so a target as wide as the screen still shows its whole ring
            val edge = EDGE_DP * density
            RectF(
                (where[0] - grow).coerceAtLeast(edge),
                where[1] - grow,
                (where[0] + target.width + grow).coerceAtMost(overlay.width - edge),
                where[1] + target.height + grow
            )
        }
        spotlight.sticker = step.sticker
        spotlight.hole = hole

        val margin = (20 * density).toInt()
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.leftMargin = margin
        params.rightMargin = margin
        val gap = (CARD_GAP_DP * density).toInt()
        when {
            hole == null -> params.gravity = Gravity.CENTER_VERTICAL
            // Below what it's about when that's in the top half of the screen, above it otherwise
            hole.centerY() < overlay.height / 2 -> {
                params.gravity = Gravity.TOP
                params.topMargin = hole.bottom.toInt() + gap
            }
            else -> {
                params.gravity = Gravity.BOTTOM
                params.bottomMargin = overlay.height - hole.top.toInt() + gap
            }
        }
        card.layoutParams = params
    }

    private fun finish() {
        back.remove()
        overlay.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            decor.removeView(overlay)
            done()
        }.start()
    }

    /** The screen dimmed, but for a rounded window onto what a step is about, ringed in the accent. */
    private class Spotlight(context: Context) : View(context) {
        var hole: RectF? = null
            set(value) {
                field = value
                invalidate()
            }
        var sticker: String? = null

        private val density = context.resources.displayMetrics.density
        private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(DIM_ALPHA, 0, 0, 0) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            color = CosmicAccent.color
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            val radius = HOLE_RADIUS_DP * density
            hole?.let { path.addRoundRect(it, radius, radius, Path.Direction.CW) }
            canvas.drawPath(path, dim)
            hole?.let { canvas.drawRoundRect(it, radius, radius, ring) }
            val label = sticker
            val around = hole
            if (label != null && around != null) drawSticker(canvas, label, around)
        }

        private val stickerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CosmicAccent.color }
        private val stickerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 10 * density
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }

        // A pill over the top right corner of the window, tilted a little like a sticker
        private fun drawSticker(canvas: Canvas, label: String, around: RectF) {
            val padX = 7 * density
            val padY = 3.5f * density
            val textWidth = stickerText.measureText(label)
            val w = textWidth + padX * 2
            val h = stickerText.textSize + padY * 2
            val cx = (around.right - w / 4).coerceAtMost(width - w / 2 - 2 * density)
            val cy = around.top
            canvas.save()
            canvas.rotate(STICKER_TILT, cx, cy)
            canvas.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h / 2, h / 2, stickerFill)
            canvas.drawText(label, cx - textWidth / 2, cy - (stickerText.ascent() + stickerText.descent()) / 2, stickerText)
            canvas.restore()
        }
    }

    private companion object {
        const val DIM_ALPHA = 190
        const val HOLE_PADDING_DP = 6
        const val HOLE_RADIUS_DP = 14
        const val EDGE_DP = 4
        const val CARD_GAP_DP = 14
        const val FADE_MS = 200L
        const val STICKER_TILT = 12f
    }
}
