package net.mangadot.scaffold.ui.folder

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Grows the folder's panel out of the widget that was tapped and shrinks it back into it on close,
 * dimming and blurring the home screen alongside, the way HyperOS opens its folders.
 */
internal class FolderOpening(private val window: Window, private val panel: View, private val content: List<View>) {

    private val density = panel.resources.displayMetrics.density
    private val panelRadius = PANEL_RADIUS_DP * density
    private val background = panel.background.mutate()

    /** The tapped widget, as a square in the panel's own coordinates once the panel is at full size. */
    private var start = RectF()
    private var startRadius = 0f
    private var startScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var clip = RectF()
    private var clipRadius = 0f
    private var progress = 0f
    private var animator: ValueAnimator? = null

    init {
        // Hidden until the apps are in, so the panel grows at its real size
        panel.alpha = 0f
        setWindow(0f)
        panel.clipToOutline = true
        panel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(clip.left.toInt(), clip.top.toInt(), clip.right.toInt(), clip.bottom.toInt(), clipRadius)
            }
        }
    }

    /** [source] is where the launcher drew what was tapped, in screen pixels, when it says. */
    fun open(source: Rect?) {
        val location = IntArray(2).also { panel.getLocationOnScreen(it) }
        val width = panel.width.toFloat()
        val height = panel.height.toFloat()
        val from = if (source != null && !source.isEmpty) {
            // Widgets keep their icons square inside whatever cell the launcher gives them
            val side = min(source.width(), source.height()).toFloat()
            RectF(source.exactCenterX() - side / 2, source.exactCenterY() - side / 2, source.exactCenterX() + side / 2, source.exactCenterY() + side / 2)
        } else {
            val inset = (1 - FALLBACK_SCALE) / 2
            RectF(location[0] + width * inset, location[1] + height * inset, location[0] + width * (1 - inset), location[1] + height * (1 - inset))
        }

        startScale = max(from.width() / width, from.height() / height)
        startRadius = min(from.width(), from.height()) * WIDGET_CORNER
        offsetX = from.centerX() - (location[0] + width / 2)
        offsetY = from.centerY() - (location[1] + height / 2)
        val halfWidth = from.width() / startScale / 2
        val halfHeight = from.height() / startScale / 2
        start = RectF(width / 2 - halfWidth, height / 2 - halfHeight, width / 2 + halfWidth, height / 2 + halfHeight)

        panel.pivotX = width / 2
        panel.pivotY = height / 2
        draw(0f)
        panel.alpha = 1f
        animateTo(1f, OPEN_MS, Spring) {}
    }

    /** Shrinks back into the widget, then [done] closes the screen. */
    fun close(done: () -> Unit) {
        if (panel.alpha == 0f) {
            done()
            return
        }
        animateTo(0f, CLOSE_MS, DecelerateInterpolator(1.5f), done)
    }

    private fun animateTo(target: Float, duration: Long, interpolator: TimeInterpolator, done: () -> Unit) {
        // Forgotten first, so the one being cut short doesn't finish itself
        animator?.let {
            animator = null
            it.cancel()
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { draw(it.animatedValue as Float) }
            doOnEnd {
                if (animator === this) {
                    draw(target)
                    done()
                }
            }
            start()
        }
    }

    /** [value] runs from the widget at 0 to the open folder at 1, and a little past 1 as the spring overshoots. */
    private fun draw(value: Float) {
        progress = value
        val settled = value.coerceIn(0f, 1f)
        val scale = startScale + (1 - startScale) * value
        panel.scaleX = scale
        panel.scaleY = scale
        panel.translationX = offsetX * (1 - value)
        panel.translationY = offsetY * (1 - value)

        clip = RectF(
            lerp(start.left, 0f, settled),
            lerp(start.top, 0f, settled),
            lerp(start.right, panel.width.toFloat(), settled),
            lerp(start.bottom, panel.height.toFloat(), settled)
        )
        // Rounded the same on screen as the widget, whatever the scale
        clipRadius = lerp(startRadius, panelRadius, settled) / scale
        panel.invalidateOutline()

        background.alpha = (lerp(WIDGET_OPACITY, 1f, settled) * 255).toInt()
        val shown = (settled / CONTENT_FADE).coerceAtMost(1f)
        content.forEach { it.alpha = shown }
        setWindow(settled)
    }

    private fun setWindow(value: Float) {
        window.attributes = window.attributes.apply {
            dimAmount = DIM * value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blurBehindRadius = (BLUR_DP * density * value).toInt()
        }
    }

    private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction

    /** Starts fast and settles with a slight overshoot, like a spring let go. */
    private object Spring : TimeInterpolator {
        override fun getInterpolation(input: Float) = 1 - exp(-SPRING_DAMPING * input) * cos(SPRING_FREQUENCY * input)
    }

    private companion object {
        const val OPEN_MS = 480L
        const val CLOSE_MS = 260L
        const val SPRING_DAMPING = 5.5f
        const val SPRING_FREQUENCY = 4f
        const val PANEL_RADIUS_DP = 32
        const val WIDGET_CORNER = 0.22f
        // The widget's backdrop is more see-through than the panel's (bg_widget_folder against bg_folder_panel)
        const val WIDGET_OPACITY = 0x8C / 217f
        const val FALLBACK_SCALE = 0.8f
        const val CONTENT_FADE = 0.6f
        const val DIM = 0.45f
        const val BLUR_DP = 24
    }
}
