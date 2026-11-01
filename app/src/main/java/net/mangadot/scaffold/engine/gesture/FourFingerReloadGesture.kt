package net.mangadot.scaffold.engine.gesture

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 4-Finger Swipe-Down Gesture Listener for reloading PWAs.
 * Specifically chosen to avoid collision with:
 * - 1-finger vertical manga/webtoon reading scroll
 * - 2-finger pinch-to-zoom
 * - 3-finger OEM screenshot gestures (Xiaomi HyperOS, Realme UI)
 */
class FourFingerReloadGesture(
    private val onReloadTriggered: () -> Unit
) : View.OnTouchListener {

    private var initialY: Float = 0f
    private var isGestureActive = false
    private val triggerThresholdPx = 160f

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 4) {
                    initialY = getAverageY(event)
                    isGestureActive = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isGestureActive && event.pointerCount == 4) {
                    val currentY = getAverageY(event)
                    val deltaY = currentY - initialY

                    if (deltaY > triggerThresholdPx) {
                        isGestureActive = false
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onReloadTriggered.invoke()
                        return true // Swallowed
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.pointerCount <= 4) {
                    isGestureActive = false
                }
            }
        }
        return false // Pass through touch events to underlying WebView
    }

    private fun getAverageY(event: MotionEvent): Float {
        var sum = 0f
        val count = event.pointerCount.coerceAtMost(4)
        for (i in 0 until count) {
            sum += event.getY(i)
        }
        return sum / count
    }
}
