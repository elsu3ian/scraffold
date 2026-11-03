package net.mangadot.scaffold.ui.container

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import net.mangadot.scaffold.R
import net.mangadot.scaffold.engine.AdBlockEngine
import net.mangadot.scaffold.engine.UserRules
import net.mangadot.scaffold.ui.theme.CosmicAccent
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Hides a leftover ad the person points at. A layer over the page takes the touches, so tapping an
 * ad never opens it: a tap picks what's under the finger, and a drag still scrolls the page. The bar
 * widens the pick to its parent or narrows it back, shows what the rule would hide, and saves it as
 * `site##selector` in [UserRules]. The page side is assets/element_picker.js, injected only now.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class ElementPicker private constructor(private val activity: AppCompatActivity, private val web: WebView) {

    // A name no site could know to look for, as the shield's bridge has
    private val name = "_" + (1..12).map { ('a'..'z').random() }.joinToString("")
    private val content: FrameLayout = activity.findViewById(android.R.id.content)
    private val density = activity.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop

    private val layer = FrameLayout(activity)
    private val bar = LinearLayout(activity)
    private val title = TextView(activity)
    private val detail = TextView(activity)
    private val btnNarrower = button("Narrower")
    private val btnWider = button("Wider")
    private val btnPreview = button("Preview")
    private val btnCancel = button("Cancel")
    private val btnSave = button("Hide it")

    private var state: JSONObject? = null
    private var downX = 0f
    private var downY = 0f
    private var onPage = false
    private var scrolling = false

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = finish(save = false)
    }

    private val ending = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = close()
    }

    private fun start() {
        buildBar()
        layer.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        layer.setOnTouchListener { _, event -> touch(event); true }
        content.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        placeBar(top = false)
        activity.onBackPressedDispatcher.addCallback(activity, back)
        activity.lifecycle.addObserver(ending)
        inject()
        show(null)
    }

    private fun inject() {
        val script = activity.assets.open("element_picker.js").bufferedReader().use { it.readText() }
        web.evaluateJavascript(script.replace("__PICKER__", name), null)
    }

    /** Calls the picker in the page, putting it back first if the page has moved on since. */
    private fun call(expression: String, retried: Boolean = false, then: ((JSONObject?) -> Unit)? = null) {
        web.evaluateJavascript("window.$name ? window.$name.$expression : null") { result ->
            if (active !== this) return@evaluateJavascript
            if (result == null || result == "null") {
                if (retried) {
                    then?.invoke(null)
                    return@evaluateJavascript
                }
                inject()
                return@evaluateJavascript call(expression, retried = true, then = then)
            }
            then?.invoke(runCatching { JSONObject(result) }.getOrNull())
        }
    }

    private fun touch(event: MotionEvent) {
        val (fx, fy) = pagePoint(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onPage = fx in 0f..1f && fy in 0f..1f
                if (!onPage) return
                downX = event.rawX
                downY = event.rawY
                scrolling = false
                forward(event)
                call("hover($fx, $fy)")
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> if (onPage) {
                scrolling = true
                forward(event)
            }
            MotionEvent.ACTION_MOVE -> if (onPage) {
                if (!scrolling && hypot(event.rawX - downX, event.rawY - downY) > slop) scrolling = true
                forward(event)
            }
            MotionEvent.ACTION_UP -> if (onPage) {
                if (scrolling) {
                    forward(event)
                } else {
                    // The page only hears of a touch that was called off, so nothing on it is clicked
                    forward(event, MotionEvent.ACTION_CANCEL)
                    placeBar(top = fy > 0.55f)
                    call("pick($fx, $fy)") { show(it) }
                }
                onPage = false
            }
            MotionEvent.ACTION_CANCEL -> if (onPage) {
                forward(event)
                onPage = false
            }
        }
    }

    /** Where [event] falls on the page, as fractions of the WebView's width and height. */
    private fun pagePoint(event: MotionEvent): Pair<Float, Float> {
        val (dx, dy) = offset()
        if (web.width == 0 || web.height == 0) return -1f to -1f
        return (event.x + dx) / web.width to (event.y + dy) / web.height
    }

    private fun offset(): Pair<Float, Float> {
        val layerAt = IntArray(2).also(layer::getLocationOnScreen)
        val webAt = IntArray(2).also(web::getLocationOnScreen)
        return (layerAt[0] - webAt[0]).toFloat() to (layerAt[1] - webAt[1]).toFloat()
    }

    private fun forward(event: MotionEvent, action: Int? = null) {
        val (dx, dy) = offset()
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(dx, dy)
        if (action != null) copy.action = action
        web.dispatchTouchEvent(copy)
        copy.recycle()
    }

    private fun show(found: JSONObject?) {
        state = found
        val selector = found?.optString("selector").orEmpty()
        if (selector.isEmpty()) {
            title.text = "Tap what you want to hide"
            detail.text = "Drag to scroll the page. Nothing you tap opens."
        } else {
            val count = found!!.optInt("count")
            val site = found.optString("site")
            title.text = if (count == 1) "Hides 1 element on $site" else "Hides $count elements on $site"
            detail.text = selector
        }
        enable(btnNarrower, found?.optBoolean("canNarrow") == true)
        enable(btnWider, found?.optBoolean("canWiden") == true)
        enable(btnPreview, selector.isNotEmpty())
        enable(btnSave, selector.isNotEmpty() && found?.optString("site").orEmpty().isNotEmpty())
        btnPreview.text = if (found?.optBoolean("previewing") == true) "Show again" else "Preview"
    }

    private fun finish(save: Boolean) {
        if (active !== this) return
        if (!save) {
            web.evaluateJavascript("window.$name && window.$name.stop()", null)
            close()
            return
        }
        call("save()") { saved ->
            close()
            val site = saved?.optString("site").orEmpty()
            val selector = saved?.optString("selector").orEmpty()
            if (site.isEmpty() || selector.isEmpty()) return@call
            if (UserRules.add(activity, "$site##$selector")) AdBlockEngine.getInstance(activity).userRulesChanged()
            Toast.makeText(activity, "Hidden on $site. It's in Shield settings, under My filters.", Toast.LENGTH_LONG).show()
        }
    }

    private fun close() {
        if (active === this) active = null
        back.remove()
        activity.lifecycle.removeObserver(ending)
        content.removeView(layer)
    }

    private fun buildBar() {
        bar.orientation = LinearLayout.VERTICAL
        bar.background = ContextCompat.getDrawable(activity, R.drawable.bg_card_solid)
        bar.elevation = dp(8).toFloat()
        bar.setPadding(dp(14), dp(12), dp(14), dp(10))
        // Taps on the bar itself stay off the page
        bar.isClickable = true

        title.setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        title.textSize = 13f
        title.setTypeface(title.typeface, Typeface.BOLD)
        bar.addView(title)

        detail.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        detail.textSize = 11f
        detail.typeface = Typeface.MONOSPACE
        detail.maxLines = 3
        detail.ellipsize = TextUtils.TruncateAt.END
        bar.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        bar.addView(row(btnNarrower, btnWider, btnPreview), rowParams())
        bar.addView(row(btnCancel, btnSave), rowParams())

        btnNarrower.setOnClickListener { call("narrower()") { show(it) } }
        btnWider.setOnClickListener { call("wider()") { show(it) } }
        btnPreview.setOnClickListener {
            val on = state?.optBoolean("previewing") != true
            call("preview($on)") { show(it) }
        }
        btnCancel.setOnClickListener { finish(save = false) }
        btnSave.setOnClickListener { finish(save = true) }
    }

    /** At the bottom, or the top when what was picked is down there. */
    private fun placeBar(top: Boolean) {
        val insets = ViewCompat.getRootWindowInsets(content)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        bar.layoutParams = (bar.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (top) Gravity.TOP else Gravity.BOTTOM
            setMargins(
                dp(12) + (insets?.left ?: 0),
                dp(12) + if (top) insets?.top ?: 0 else 0,
                dp(12) + (insets?.right ?: 0),
                dp(12) + if (top) 0 else insets?.bottom ?: 0
            )
        }
    }

    private fun row(vararg buttons: TextView) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEachIndexed { index, button ->
            addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
    }

    private fun rowParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(10)
    }

    private fun button(label: String) = TextView(activity).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(CosmicAccent.color)
        background = ContextCompat.getDrawable(activity, R.drawable.bg_card)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        isClickable = true
        isFocusable = true
    }

    private fun enable(view: View, on: Boolean) {
        view.isEnabled = on
        view.alpha = if (on) 1f else 0.4f
    }

    private fun dp(value: Int) = (value * density).toInt()

    companion object {
        private var active: ElementPicker? = null

        fun start(activity: AppCompatActivity, web: WebView) {
            active?.finish(save = false)
            ElementPicker(activity, web).also { active = it }.start()
        }
    }
}
