package net.mangadot.scaffold.ui.container

import android.graphics.Rect
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.appcompat.widget.TooltipCompat
import net.mangadot.scaffold.R

/**
 * The side bubble: it sits half into the left or right edge of the screen, and drags up and down or
 * across to the other edge, which it keeps to next time. Tapping it fans out [buttons] across [menu],
 * and holding it calls [onLongPress].
 */
class AssistiveBall(
    private val ball: View,
    private val icon: ImageView,
    private val menu: View,
    private val buttons: List<View>,
    private val onOpen: () -> Unit = {},
    private val onLongPress: () -> Unit
) {

    var isOpen = false
        private set

    private var restY: Float? = null

    private val prefs = ball.context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    /** Which edge it sits on. */
    private var onRight = prefs.getBoolean(KEY_ON_RIGHT, false)

    init {
        ball.post {
            ball.translationX = dockedX()
            ball.alpha = DOCKED_ALPHA
            keepFromBackGesture()
        }
        // Turning the phone moves the right edge, which the ball keeps to
        (ball.parent as View).addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft && onRight && !isOpen) ball.post { ball.translationX = dockedX() }
        }

        var dX = 0f
        var dY = 0f
        var startX = 0f
        var startY = 0f
        var isDragging = false
        var longPressFired = false

        // The listener consumes the whole gesture, so the framework's own long-press timer never runs
        val longPress = Runnable {
            longPressFired = true
            ball.performLongClick()
        }

        ball.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.translationX - event.rawX
                    dY = view.y - event.rawY
                    startX = event.rawX
                    startY = event.rawY
                    isDragging = false
                    longPressFired = false
                    ball.animate().alpha(1f).translationX(shownX()).setDuration(150).start()
                    view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging && !longPressFired && !isOpen && Math.hypot((event.rawX - startX).toDouble(), (event.rawY - startY).toDouble()) > dp(6)) {
                        isDragging = true
                        view.removeCallbacks(longPress)
                        // Picked up mid-animation, it follows the finger from where it is now
                        view.animate().cancel()
                        dX = view.translationX - event.rawX
                    }
                    if (isDragging) {
                        view.y = (event.rawY + dY).coerceIn(dp(20), (view.parent as View).height - view.height - dp(20))
                        view.translationX = (event.rawX + dX).coerceIn(leftX(docked = true), rightX(docked = true))
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPress)
                    if (isDragging) {
                        // Lands on whichever edge its middle is nearer
                        val middle = view.left + view.translationX + view.width / 2f
                        val side = middle > (view.parent as View).width / 2f
                        if (side != onRight) {
                            onRight = side
                            prefs.edit().putBoolean(KEY_ON_RIGHT, side).apply()
                        }
                        if (!isOpen) dock()
                    } else if (!longPressFired) {
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPress)
                    if (!isOpen) dock()
                    true
                }
                else -> false
            }
        }

        ball.setOnClickListener { if (isOpen) close() else open() }
        ball.setOnLongClickListener {
            ball.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
            true
        }
        menu.setOnClickListener { close() }
        buttons.forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }
    }

    private fun dock() {
        ball.animate().translationX(dockedX()).alpha(DOCKED_ALPHA).setDuration(250)
            .withEndAction(::keepFromBackGesture).start()
    }

    /**
     * Sitting on the screen's edge, a drag on the ball would otherwise be taken for Android's back
     * swipe. Set again whenever it settles, so the spot follows it.
     */
    private fun keepFromBackGesture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ball.systemGestureExclusionRects = listOf(Rect(0, 0, ball.width, ball.height))
        }
    }

    // Laid out at the left edge, so the right edge is reached by moving it across
    private fun leftX(docked: Boolean) = if (docked) -dp(DOCKED_OFFSET_DP) else 0f

    private fun rightX(docked: Boolean): Float {
        val parent = ball.parent as View
        val across = (parent.width - parent.paddingRight - ball.width - ball.left).toFloat().coerceAtLeast(0f)
        return if (docked) across + dp(DOCKED_OFFSET_DP) else across
    }

    private fun dockedX() = if (onRight) rightX(docked = true) else leftX(docked = true)

    private fun shownX() = if (onRight) rightX(docked = false) else leftX(docked = false)

    private fun open() {
        isOpen = true
        // More buttons take a wider arc, so they stay clear of each other
        val fitted = (BUTTON_SPACING_DP * (buttons.size - 1) / (2 * Math.toRadians(SWEEP_DEGREES.toDouble()))).toInt()
        val radius = dp(maxOf(RADIUS_DP, fitted))
        val buttonSize = dp(BUTTON_DP)

        // Slides away from the screen's top or bottom while open, so the whole arc fits
        restY = ball.y
        val reach = radius + buttonSize
        val centerY = if (menu.height > reach * 2) {
            (ball.y + ball.height / 2f).coerceIn(menu.top + reach, menu.bottom - reach)
        } else {
            menu.top + menu.height / 2f
        }
        val ballX = shownX()
        ball.animate().translationX(ballX).y(centerY - ball.height / 2f).alpha(1f).setDuration(ANIM_MS).start()
        icon.setImageResource(R.drawable.ic_close)
        onOpen()

        menu.visibility = View.VISIBLE
        menu.alpha = 0f
        menu.animate().alpha(1f).setDuration(ANIM_MS).start()

        val originX = ball.left + ballX + ball.width / 2f - menu.left - buttonSize / 2f
        // The arc opens away from the edge the ball is on
        val across = if (onRight) -1 else 1
        val originY = centerY - menu.top - buttonSize / 2f
        buttons.forEachIndexed { index, button ->
            val fraction = if (buttons.size > 1) index / (buttons.size - 1f) else 0.5f
            val angle = Math.toRadians((-SWEEP_DEGREES + 2 * SWEEP_DEGREES * fraction).toDouble())
            button.animate().cancel()
            button.translationX = originX
            button.translationY = originY
            button.scaleX = 0.4f
            button.scaleY = 0.4f
            button.alpha = 0f
            button.animate()
                .translationX(originX + across * radius * Math.cos(angle).toFloat())
                .translationY(originY + radius * Math.sin(angle).toFloat())
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay(index * STAGGER_MS)
                .setDuration(ANIM_MS)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        }
    }

    fun close() {
        isOpen = false
        icon.setImageResource(R.drawable.ic_more_vert)
        val y = restY ?: ball.y
        restY = null
        ball.animate().translationX(dockedX()).y(y).alpha(DOCKED_ALPHA).setDuration(250)
            .withEndAction(::keepFromBackGesture).start()
        menu.animate().alpha(0f).setDuration(ANIM_MS).withEndAction {
            if (!isOpen) menu.visibility = View.INVISIBLE
        }.start()
    }

    private fun dp(value: Int) = value * ball.resources.displayMetrics.density

    private companion object {
        const val PREFS_NAME = "scraffold_ball"
        const val KEY_ON_RIGHT = "on_right"
        const val DOCKED_OFFSET_DP = 18
        const val DOCKED_ALPHA = 0.5f
        const val RADIUS_DP = 96
        const val BUTTON_DP = 40
        const val BUTTON_SPACING_DP = 44
        const val SWEEP_DEGREES = 78f
        const val ANIM_MS = 180L
        const val STAGGER_MS = 14L
    }
}
