package net.mangadot.scaffold.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import net.mangadot.scaffold.ui.settings.SettingsActivity

/**
 * The refresh rate Scraffold asks the screen for while it's in front, for phones that keep apps they
 * don't know at 60 Hz. Left at the phone's default, the phone chooses as it would for any app.
 */
object HighRefresh {

    /** Stored in place of a rate: the phone chooses. */
    const val PHONE_DEFAULT = 0

    /** Stored in place of a rate: fastest while touched or scrolling, slow again once still. */
    const val ADAPTIVE = -1

    /** The rates the picker always offers, with any others the screen has. */
    val COMMON_RATES = listOf(60, 90, 120)

    // How long after the last touch the screen stays fast, so a fling's glide keeps it smooth to the end
    const val SETTLE_MS = 1500L

    // Whether the phone lets apps pick the rate, found out once per system version
    private const val KEY_CONTROL = "pref_refresh_control"
    // How long the screen gets to switch during that check
    private const val PROBE_STEP_MS = 900L

    private var lastTouchAt = 0L

    /** True while [probe] is finding out whether the phone lets apps pick the rate. */
    var probing = false
        private set

    /**
     * [PHONE_DEFAULT], [ADAPTIVE], or a rate in Hz. Always [PHONE_DEFAULT] on a phone that doesn't let
     * apps pick, so nothing is asked of it.
     */
    fun chosen(context: Context): Int {
        if (controllable(context) == false) return PHONE_DEFAULT
        return prefs(context).getInt(SettingsActivity.KEY_REFRESH_RATE, PHONE_DEFAULT)
    }

    /**
     * Whether this phone lets apps pick the rate, or null until [probe] has found out. Some phones,
     * Xiaomi's among them, choose the rate themselves whatever an app asks for. Found out again after
     * a system update, which can change that.
     */
    fun controllable(context: Context): Boolean? {
        val saved = prefs(context).getString(KEY_CONTROL, null) ?: return null
        if (saved.substringBeforeLast('|') != Build.FINGERPRINT) return null
        return saved.endsWith("|1")
    }

    /**
     * Finds out whether the phone follows what apps ask for: asks for one rate, then another, and sees
     * if the screen goes to each. A phone that picks for itself rarely lands on both by chance. A touch
     * in between leaves it undecided, as the phone may have sped up for the finger, and it's tried again
     * next time. [done] runs once it's decided.
     */
    fun probe(activity: Activity, done: () -> Unit) {
        if (probing || controllable(activity) != null) return
        val rates = rates(activity)
        if (rates.size < 2) return
        val now = currentRate(activity)
        val fastest = rates.last()
        // A rate in between first where there is one, since the phone itself mostly picks the ends
        val first = rates.firstOrNull { it != now && it != 60 && it != fastest } ?: rates.firstOrNull { it != now } ?: return
        val second = if (first != fastest) fastest else rates.first()
        val started = SystemClock.uptimeMillis()
        val handler = Handler(Looper.getMainLooper())
        probing = true

        fun finish(follows: Boolean) {
            probing = false
            if (follows || lastTouchAt < started) {
                prefs(activity).edit().putString(KEY_CONTROL, Build.FINGERPRINT + if (follows) "|1" else "|0").apply()
            }
            if (!activity.isDestroyed) {
                apply(activity)
                done()
            }
        }

        request(activity, modeFor(activity, first))
        handler.postDelayed({
            if (activity.isDestroyed || currentRate(activity) != first) {
                finish(false)
                return@postDelayed
            }
            request(activity, modeFor(activity, second))
            handler.postDelayed({
                finish(!activity.isDestroyed && currentRate(activity) == second)
            }, PROBE_STEP_MS)
        }, PROBE_STEP_MS)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)

    fun choose(activity: Activity, choice: Int) {
        prefs(activity).edit().putInt(SettingsActivity.KEY_REFRESH_RATE, choice).apply()
        apply(activity)
    }

    /** Call when a screen comes to the front, and again when the choice changes. Adaptive starts slow. */
    fun apply(activity: Activity) {
        if (probing) return
        when (val choice = chosen(activity)) {
            PHONE_DEFAULT -> request(activity, 0)
            ADAPTIVE -> request(activity, modeFor(activity, slowRate(activity)))
            else -> request(activity, modeFor(activity, choice))
        }
    }

    /**
     * For Adaptive: a finger down or moving asks for the fastest rate at once, and lifting it starts the
     * wait before slowing down. Returns whether the screen should slow down after [SETTLE_MS].
     */
    fun onTouch(activity: Activity, event: MotionEvent): Boolean {
        lastTouchAt = SystemClock.uptimeMillis()
        if (probing || chosen(activity) != ADAPTIVE) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                request(activity, modeFor(activity, rates(activity).maxOrNull() ?: 60))
                false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
            else -> false
        }
    }

    /** For Adaptive, once the screen has been still for [SETTLE_MS]. */
    fun settle(activity: Activity) {
        if (!probing && chosen(activity) == ADAPTIVE) request(activity, modeFor(activity, slowRate(activity)))
    }

    /** The rate the screen runs at this moment, in Hz. */
    fun currentRate(activity: Activity): Int = Math.round(display(activity).mode.refreshRate)

    /** The rates the screen offers at its current resolution, in Hz, slowest first. */
    fun rates(activity: Activity): List<Int> = modes(activity).map { Math.round(it.refreshRate) }.distinct().sorted()

    /**
     * What a still screen drops to: 60 Hz, which video and animations need, or the nearest the screen
     * has. LTPO screens go lower by themselves, but the slowest rates aren't offered to apps.
     */
    private fun slowRate(activity: Activity): Int {
        val rates = rates(activity)
        return rates.firstOrNull { it >= 60 } ?: rates.maxOrNull() ?: 60
    }

    private fun modeFor(activity: Activity, rate: Int): Int =
        modes(activity).minByOrNull { Math.abs(it.refreshRate - rate) }?.modeId ?: 0

    /**
     * Asks for the mode three ways, since phone makers listen to different ones: the screen mode, the
     * rate on its own, and on Android 15 and later the rate the window's views would like to draw at.
     * The phone has the last word either way.
     */
    private fun request(activity: Activity, modeId: Int) {
        val rate = if (modeId == 0) 0f else modes(activity).firstOrNull { it.modeId == modeId }?.refreshRate ?: 0f
        val attributes = activity.window.attributes
        if (attributes.preferredDisplayModeId != modeId || attributes.preferredRefreshRate != rate) {
            attributes.preferredDisplayModeId = modeId
            attributes.preferredRefreshRate = rate
            activity.window.attributes = attributes
        }
        if (Build.VERSION.SDK_INT >= 35) activity.window.decorView.requestedFrameRate = if (rate > 0f) rate else Float.NaN
    }

    // Other resolutions are left out, so a rate never changes how sharp the screen is
    private fun modes(activity: Activity): List<Display.Mode> {
        val display = display(activity)
        val current = display.mode
        return display.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
    }

    private fun display(activity: Activity): Display =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else null)
            ?: @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
}
