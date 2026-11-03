package net.mangadot.scaffold.engine.gesture

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import net.mangadot.scaffold.R
import net.mangadot.scaffold.engine.DeckWebView
import net.mangadot.scaffold.ui.settings.SettingsActivity
import kotlin.math.abs

/**
 * Pulling down at the top of a page reloads it, the way Chrome does: only when the page itself was
 * already at its top as the finger came down, so a feed or chat that scrolls inside the page scrolls
 * rather than reloads, and scrolling back up to the top never turns into a reload on the way.
 */
class PullToReloadLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    /** The page on screen, or null when there isn't one to reload. */
    var page: () -> DeckWebView? = { null }

    private val startWindow = START_WINDOW_DP * resources.displayMetrics.density
    private var downY = 0f
    private var blocked = true
    private var gaveUp = false
    private var severalFingers = false

    init {
        setColorSchemeResources(R.color.accent_emerald)
        setProgressBackgroundColorSchemeResource(R.color.bg_surface_elevated)
        setOnChildScrollUpCallback { _, _ ->
            val web = page()
            // Two fingers are a pinch or a two-finger swipe, never a pull
            gaveUp || severalFingers || web == null || !web.isShown || web.scrollY > 0 || !web.pulledPastTop
        }
        setDistanceToTriggerSync((TRIGGER_DP * resources.displayMetrics.density).toInt())
        setOnRefreshListener { page()?.reload() ?: run { isRefreshing = false } }
    }

    // Read each time the screen shows, so switching it off in Settings applies on the way back
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            isEnabled = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_GESTURE_PULL, true)
        }
    }

    /** Pass on the page's loading progress; the spinner goes once it has loaded. */
    fun onProgress(progress: Int) {
        if (progress >= 100) isRefreshing = false
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                severalFingers = false
                downY = event.y
                // The page hasn't seen this touch yet, so whether it was pulled past its top is left over from the last
                blocked = true
                gaveUp = (page()?.scrollY ?: 0) > 0
            }
            MotionEvent.ACTION_POINTER_DOWN -> severalFingers = true
            MotionEvent.ACTION_MOVE -> if (blocked && !gaveUp) {
                if (!canChildScrollUp()) {
                    blocked = false
                    // The page says it's at its top a moment after the finger starts, so the pull is
                    // measured from here rather than from where the finger landed
                    val start = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_DOWN }
                    super.onInterceptTouchEvent(start)
                    start.recycle()
                } else if (abs(event.y - downY) > startWindow) {
                    // The page scrolled first, so it wasn't at its top: this touch is a scroll
                    gaveUp = true
                }
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    private companion object {
        const val START_WINDOW_DP = 32
        const val TRIGGER_DP = 120
    }
}
