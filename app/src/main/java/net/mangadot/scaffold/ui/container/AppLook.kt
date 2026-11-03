package net.mangadot.scaffold.ui.container

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.engine.manifestColor

/**
 * Dresses an installed web app in its manifest's colors: the status bar in its theme color, and a launch splash
 * of its icon on its background color until the page shows. Apps without manifest colors look as they always did.
 */
class AppLook(private val activity: Activity) {

    private val decor = activity.window.decorView as ViewGroup
    private var statusTint: View? = null
    private var splash: View? = null
    private var statusBarHeight = 0

    fun apply(app: WebAppEntity, showSplash: Boolean) {
        val theme = app.themeColor.manifestColor()
        val background = app.backgroundColor.manifestColor() ?: theme
        if (theme != null) tintStatusBar(theme)
        if (showSplash && background != null && splash == null) showSplash(app, background)
    }

    /** The status bar's height, from the container's insets. It's 0 in fullscreen, where the tint hides too. */
    fun onStatusBarHeight(height: Int) {
        statusBarHeight = height
        statusTint?.layoutParams?.let {
            it.height = height
            statusTint?.layoutParams = it
        }
    }

    private fun tintStatusBar(color: Int) {
        // Android 15 no longer draws a status bar color, so a band is laid under the bar instead
        val band = statusTint ?: View(activity).also {
            decor.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusBarHeight, Gravity.TOP))
            statusTint = it
        }
        band.setBackgroundColor(color)
        WindowCompat.getInsetsController(activity.window, decor).isAppearanceLightStatusBars = isLight(color)
    }

    private fun showSplash(app: WebAppEntity, color: Int) {
        val density = activity.resources.displayMetrics.density
        val iconSize = (SPLASH_ICON_DP * density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        app.iconUrl?.takeIf { it.isNotEmpty() }?.let(BitmapFactory::decodeFile)?.let { icon ->
            content.addView(ImageView(activity).apply {
                setImageBitmap(icon)
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.22f)
                    }
                }
                clipToOutline = true
            }, LinearLayout.LayoutParams(iconSize, iconSize))
        }
        content.addView(TextView(activity).apply {
            text = app.name
            textSize = 18f
            setTextColor(if (isLight(color)) Color.BLACK else Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, (16 * density).toInt(), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val cover = FrameLayout(activity).apply {
            setBackgroundColor(color)
            // Holds touches until the page can take them
            isClickable = true
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        decor.addView(cover, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        splash = cover
        cover.postDelayed({ hideSplash() }, SPLASH_MAX_MS)
    }

    /** The page is far enough along to show, as Chrome lifts an installed app's splash at first paint. */
    fun onProgress(progress: Int) {
        if (progress >= SPLASH_LIFT_PROGRESS) hideSplash()
    }

    fun hideSplash() {
        val cover = splash ?: return
        splash = null
        cover.animate().alpha(0f).setDuration(SPLASH_FADE_MS).withEndAction { decor.removeView(cover) }.start()
    }

    private fun isLight(color: Int) = ColorUtils.calculateLuminance(color) > 0.5

    private companion object {
        const val SPLASH_ICON_DP = 96
        const val SPLASH_LIFT_PROGRESS = 80
        const val SPLASH_MAX_MS = 4000L
        const val SPLASH_FADE_MS = 200L
    }
}
