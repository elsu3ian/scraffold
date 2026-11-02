package net.mangadot.scaffold.ui.container

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.appcompat.widget.TooltipCompat
import net.mangadot.scaffold.R

/**
 * The side bubble: it sits half into the screen's edge and drags up and down. Tapping it fans out
 * [buttons] across [menu], and holding it calls [onLongPress].
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

    init {
        ball.post {
            ball.translationX = -dp(DOCKED_OFFSET_DP)
            ball.alpha = DOCKED_ALPHA
        }

        var dY = 0f
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
                    dY = view.y - event.rawY
                    startY = event.rawY
                    isDragging = false
                    longPressFired = false
                    ball.animate().alpha(1f).translationX(0f).setDuration(150).start()
                    view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!longPressFired && !isOpen && Math.abs(event.rawY - startY) > dp(6)) {
                        isDragging = true
                        view.removeCallbacks(longPress)
                    }
                    if (isDragging) {
                        view.y = (event.rawY + dY).coerceIn(dp(20), (view.parent as View).height - view.height - dp(20))
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPress)
                    if (isDragging) {
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
        ball.animate().translationX(-dp(DOCKED_OFFSET_DP)).alpha(DOCKED_ALPHA).setDuration(250).start()
    }

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
        ball.animate().translationX(0f).y(centerY - ball.height / 2f).alpha(1f).setDuration(ANIM_MS).start()
        icon.setImageResource(R.drawable.ic_close)
        onOpen()

        menu.visibility = View.VISIBLE
        menu.alpha = 0f
        menu.animate().alpha(1f).setDuration(ANIM_MS).start()

        val originX = ball.left + ball.width / 2f - menu.left - buttonSize / 2f
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
                .translationX(originX + radius * Math.cos(angle).toFloat())
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
        ball.animate().translationX(-dp(DOCKED_OFFSET_DP)).y(y).alpha(DOCKED_ALPHA).setDuration(250).start()
        menu.animate().alpha(0f).setDuration(ANIM_MS).withEndAction {
            if (!isOpen) menu.visibility = View.INVISIBLE
        }.start()
    }

    private fun dp(value: Int) = value * ball.resources.displayMetrics.density

    private companion object {
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
