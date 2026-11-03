package net.mangadot.scaffold.engine.gesture

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import net.mangadot.scaffold.R
import net.mangadot.scaffold.engine.DeckWebView
import net.mangadot.scaffold.ui.settings.SettingsActivity

/**
 * Pulling down at the top of a page reloads it, the way Chrome does: only once the page itself has
 * nowhere further up to go, so a feed or chat that scrolls inside the page scrolls rather than reloads.
 */
class PullToReloadLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    /** The page on screen, or null when there isn't one to reload. */
    var page: () -> DeckWebView? = { null }

    private var blocked = true
    private var severalFingers = false

    init {
        setColorSchemeResources(R.color.accent_emerald)
        setProgressBackgroundColorSchemeResource(R.color.bg_surface_elevated)
        setOnChildScrollUpCallback { _, _ ->
            val web = page()
            // Two fingers are a pinch or a two-finger swipe, never a pull
            severalFingers || web == null || !web.isShown || web.scrollY > 0 || !web.pulledPastTop
        }
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
                blocked = canChildScrollUp()
            }
            MotionEvent.ACTION_POINTER_DOWN -> severalFingers = true
            MotionEvent.ACTION_MOVE -> if (blocked && !canChildScrollUp()) {
                blocked = false
                // The page only just reached its top, so the pull is measured from here rather than
                // from where the finger landed, which would have it jump most of the way at once
                val start = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_DOWN }
                super.onInterceptTouchEvent(start)
                start.recycle()
            }
        }
        return super.onInterceptTouchEvent(event)
    }
}
