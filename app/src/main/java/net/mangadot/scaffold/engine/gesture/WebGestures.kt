package net.mangadot.scaffold.engine.gesture

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Multi-finger swipes over a web page. Every finger must travel the same way, which is what
 * separates these from a pinch. Touches always carry on to the page.
 */
class WebGestures(
    context: Context,
    private val onTwoFingerDown: (() -> Unit)?,
    private val onTwoFingerHorizontal: ((forward: Boolean) -> Unit)?,
    private val onThreeFingerHorizontal: ((next: Boolean) -> Unit)?
) : View.OnTouchListener {

    private val threshold = SWIPE_DP * context.resources.displayMetrics.density
    private val startX = FloatArray(MAX_FINGERS)
    private val startY = FloatArray(MAX_FINGERS)
    private var fingers = 0
    private var fired = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false
                fingers = 0
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A finger landing starts the swipe again, with everyone's positions from now
                fingers = event.pointerCount.coerceAtMost(MAX_FINGERS)
                for (i in 0 until fingers) {
                    startX[i] = event.getX(i)
                    startY[i] = event.getY(i)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!fired && fingers >= 2 && event.pointerCount == fingers) detect(event)
            }
        }
        return false
    }

    private fun detect(event: MotionEvent) {
        val dx = FloatArray(fingers) { event.getX(it) - startX[it] }
        val dy = FloatArray(fingers) { event.getY(it) - startY[it] }
        val horizontalRight = dx.all { it > threshold } && dy.all { abs(it) < threshold }
        val horizontalLeft = dx.all { it < -threshold } && dy.all { abs(it) < threshold }
        val down = dy.all { it > threshold } && dx.all { abs(it) < threshold }
        if (fingers == 2) {
            when {
                down && onTwoFingerDown != null -> onTwoFingerDown.invoke()
                horizontalRight && onTwoFingerHorizontal != null -> onTwoFingerHorizontal.invoke(false)
                horizontalLeft && onTwoFingerHorizontal != null -> onTwoFingerHorizontal.invoke(true)
                else -> return
            }
        } else {
            when {
                horizontalLeft && onThreeFingerHorizontal != null -> onThreeFingerHorizontal.invoke(true)
                horizontalRight && onThreeFingerHorizontal != null -> onThreeFingerHorizontal.invoke(false)
                else -> return
            }
        }
        fired = true
    }

    private companion object {
        const val SWIPE_DP = 70
        const val MAX_FINGERS = 3
    }
}
