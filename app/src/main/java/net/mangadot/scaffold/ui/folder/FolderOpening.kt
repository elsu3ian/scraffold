package net.mangadot.scaffold.ui.folder

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import androidx.recyclerview.widget.RecyclerView
import net.mangadot.scaffold.R
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Opens the folder the way HyperOS does: the backdrop grows out of the tapped widget while each app's
 * icon flies from where the widget drew it to its place in the folder. Closing sends them all back.
 */
internal class FolderOpening(
    private val window: Window,
    private val backdrop: View,
    private val panel: View,
    private val grid: RecyclerView,
    private val fading: List<View>
) {

    /** One app's icon on its way between the widget and the folder. */
    private class Flight(val icon: View, val label: View?, val fromX: Float, val fromY: Float, val fromScale: Float, val fades: Boolean) {
        var progress = 0f
    }

    private val density = backdrop.resources.displayMetrics.density
    private val panelRadius = PANEL_RADIUS_DP * density
    private val background = backdrop.background.mutate()

    /** The tapped widget, in the backdrop's own coordinates once the backdrop is at full size. */
    private var start = RectF()
    private var startRadius = 0f
    private var startScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var clip = RectF()
    private var clipRadius = 0f
    private var backdropProgress = 0f
    private var flights = emptyList<Flight>()
    private var animator: ValueAnimator? = null
    private var opened = false
    private var closing = false

    init {
        // Hidden until the apps are in, so everything flies to its real place
        panel.alpha = 0f
        backdrop.alpha = 0f
        setWindow(0f)
        backdrop.clipToOutline = true
        backdrop.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(clip.left.toInt(), clip.top.toInt(), clip.right.toInt(), clip.bottom.toInt(), clipRadius)
            }
        }
    }

    /**
     * [source] is where the launcher drew what was tapped, in screen pixels, when it says, and [spots]
     * where the widget drew each app inside it, in the folder's order.
     */
    fun open(source: Rect?, spots: List<RectF>) {
        val location = IntArray(2).also { backdrop.getLocationOnScreen(it) }
        val width = backdrop.width.toFloat()
        val height = backdrop.height.toFloat()
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
        backdrop.pivotX = width / 2
        backdrop.pivotY = height / 2

        flights = (0 until grid.childCount).map { grid.getChildAt(it) }.sortedBy { grid.getChildAdapterPosition(it) }.map { item ->
            // The icon leaves its cell on the way, so the cell mustn't cut it off, its padding included
            (item as? ViewGroup)?.apply {
                clipChildren = false
                clipToPadding = false
            }
            val icon = item.findViewById<View>(R.id.icon_container)
            val iconLocation = IntArray(2).also { icon.getLocationOnScreen(it) }
            val centerX = iconLocation[0] + icon.width / 2f
            val centerY = iconLocation[1] + icon.height / 2f
            val spot = spots.getOrNull(grid.getChildAdapterPosition(item))
            val label = item.findViewById<View>(R.id.tv_app_name)
            when {
                spot != null -> Flight(icon, label, spot.centerX() - centerX, spot.centerY() - centerY, spot.width() / icon.width, fades = false)
                // Apps the widget had no room for come out of its middle
                source != null -> Flight(icon, label, from.centerX() - centerX, from.centerY() - centerY, HIDDEN_SCALE, fades = true)
                else -> Flight(icon, label, 0f, 0f, FALLBACK_SCALE, fades = true)
            }
        }

        drawBackdrop(0f)
        flights.forEach { drawFlight(it, 0f) }
        panel.alpha = 1f
        backdrop.alpha = 1f
        opened = true

        val stagger = if (flights.size > 1) min(STAGGER_MS, MAX_STAGGER_MS / (flights.size - 1)) else 0L
        val total = max(OPEN_MS, ICON_MS + stagger * (flights.size - 1))
        run(total, LinearInterpolator(), { fraction ->
            val elapsed = fraction * total
            drawBackdrop(Spring.getInterpolation((elapsed / OPEN_MS).coerceAtMost(1f)))
            flights.forEachIndexed { index, flight ->
                drawFlight(flight, Spring.getInterpolation(((elapsed - index * stagger) / ICON_MS).coerceIn(0f, 1f)))
            }
        }) {
            drawBackdrop(1f)
            flights.forEach { drawFlight(it, 1f) }
            // Landed, so a long folder's grid is cut at its edges again and can scroll without its
            // icons drawing over the title. A view's clipping cuts its children, so it's the panel's
            (panel as? ViewGroup)?.clipChildren = true
        }
    }

    /** Sends everything back into the widget, then [done] closes the screen. */
    fun close(done: () -> Unit) {
        if (!opened) {
            done()
            return
        }
        if (closing) return
        closing = true
        (panel as? ViewGroup)?.clipChildren = false
        // Each piece heads home from wherever the opening left it
        val backdropFrom = backdropProgress
        val flightsFrom = flights.map { it.progress }
        run(CLOSE_MS, DecelerateInterpolator(1.5f), { fraction ->
            drawBackdrop(backdropFrom * (1 - fraction))
            flights.forEachIndexed { index, flight -> drawFlight(flight, flightsFrom[index] * (1 - fraction)) }
        }, done)
    }

    private fun run(duration: Long, interpolator: TimeInterpolator, frame: (Float) -> Unit, done: () -> Unit) {
        // Forgotten first, so the one being cut short doesn't finish itself
        animator?.let {
            animator = null
            it.cancel()
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { frame(it.animatedValue as Float) }
            doOnEnd {
                if (animator === this) {
                    frame(1f)
                    done()
                }
            }
            start()
        }
    }

    /** [value] runs from the widget at 0 to the open folder at 1, and a little past 1 as the spring overshoots. */
    private fun drawBackdrop(value: Float) {
        backdropProgress = value
        val settled = value.coerceIn(0f, 1f)
        val scale = startScale + (1 - startScale) * value
        backdrop.scaleX = scale
        backdrop.scaleY = scale
        backdrop.translationX = offsetX * (1 - value)
        backdrop.translationY = offsetY * (1 - value)

        clip = RectF(
            lerp(start.left, 0f, settled),
            lerp(start.top, 0f, settled),
            lerp(start.right, backdrop.width.toFloat(), settled),
            lerp(start.bottom, backdrop.height.toFloat(), settled)
        )
        // Rounded the same on screen as the widget, whatever the scale
        clipRadius = lerp(startRadius, panelRadius, settled) / scale
        backdrop.invalidateOutline()

        background.alpha = (lerp(WIDGET_OPACITY, 1f, settled) * 255).toInt()
        val shown = fadeIn(settled)
        fading.forEach { it.alpha = shown }
        setWindow(settled)
    }

    private fun drawFlight(flight: Flight, value: Float) {
        flight.progress = value
        val settled = value.coerceIn(0f, 1f)
        val scale = lerp(flight.fromScale, 1f, value)
        flight.icon.scaleX = scale
        flight.icon.scaleY = scale
        flight.icon.translationX = flight.fromX * (1 - value)
        flight.icon.translationY = flight.fromY * (1 - value)
        flight.icon.alpha = if (flight.fades) settled else 1f
        flight.label?.alpha = fadeIn(settled)
    }

    /** Names and the title wait until the icons are nearly home. */
    private fun fadeIn(value: Float) = ((value - FADE_FROM) / (1 - FADE_FROM)).coerceIn(0f, 1f)

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
        const val ICON_MS = 520L
        const val STAGGER_MS = 25L
        const val MAX_STAGGER_MS = 220L
        const val CLOSE_MS = 280L
        const val SPRING_DAMPING = 5.5f
        const val SPRING_FREQUENCY = 4f
        const val PANEL_RADIUS_DP = 32
        const val WIDGET_CORNER = 0.22f
        // The widget's backdrop is more see-through than the panel's (bg_widget_folder against bg_folder_panel)
        const val WIDGET_OPACITY = 0x8C / 217f
        const val FALLBACK_SCALE = 0.8f
        const val HIDDEN_SCALE = 0.2f
        const val FADE_FROM = 0.5f
        const val DIM = 0.45f
        const val BLUR_DP = 24
    }
}
