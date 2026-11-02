package net.mangadot.scaffold.ui.userscript

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import net.mangadot.scaffold.R

/** Dropdown for the editor's menu bar and other script actions. */
class GlassMenu(private val context: Context) {

    private val density = context.resources.displayMetrics.density
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var popup: PopupWindow? = null

    private fun dp(value: Int) = (value * density).toInt()

    fun item(
        label: String,
        shortcut: String? = null,
        danger: Boolean = false,
        enabled: Boolean = true,
        onClick: () -> Unit = {}
    ): GlassMenu {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            alpha = if (enabled) 1f else 0.4f
            isClickable = enabled
            if (enabled) {
                setOnClickListener {
                    popup?.dismiss()
                    onClick()
                }
            }
        }
        row.addView(TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, if (danger) R.color.accent_rose else R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (shortcut != null) {
            row.addView(TextView(context).apply {
                text = shortcut
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            })
        }
        column.addView(row)
        return this
    }

    fun divider(): GlassMenu {
        column.addView(View(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.border_primary))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
        return this
    }

    fun show(anchor: View) {
        val container = ScrollView(context).apply {
            addView(column)
            setPadding(0, dp(4), 0, dp(4))
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(0xF218181C.toInt())
                setStroke(dp(1).coerceAtLeast(1), 0x33FFFFFF)
            }
        }
        popup = PopupWindow(container, dp(230), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = 12 * density
            showAsDropDown(anchor)
        }
    }
}
