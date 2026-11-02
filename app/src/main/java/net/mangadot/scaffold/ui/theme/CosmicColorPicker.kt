package net.mangadot.scaffold.ui.theme

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import net.mangadot.scaffold.R

/** Saturation/brightness square above a hue bar. */
class ColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onColorChanged: ((Int) -> Unit)? = null

    private val hsv = floatArrayOf(0f, 1f, 1f)
    private val density = resources.displayMetrics.density
    private val barHeight = 24 * density
    private val gap = 14 * density
    private val square = RectF()
    private val hueBar = RectF()
    private val squarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = Color.WHITE
    }
    private var draggingHue = false

    val color: Int
        get() = Color.HSVToColor(hsv)

    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        updateSquareShader()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * 0.6f + gap + barHeight).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        square.set(0f, 0f, w.toFloat(), h - gap - barHeight)
        hueBar.set(0f, h - barHeight, w.toFloat(), h.toFloat())
        huePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED),
            null,
            Shader.TileMode.CLAMP
        )
        updateSquareShader()
    }

    private fun updateSquareShader() {
        if (square.isEmpty) return
        val pureHue = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        squarePaint.shader = ComposeShader(
            LinearGradient(0f, 0f, 0f, square.bottom, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP),
            LinearGradient(0f, 0f, square.right, 0f, Color.WHITE, pureHue, Shader.TileMode.CLAMP),
            PorterDuff.Mode.MULTIPLY
        )
    }

    override fun onDraw(canvas: Canvas) {
        val radius = 10 * density
        canvas.drawRoundRect(square, radius, radius, squarePaint)
        canvas.drawRoundRect(hueBar, barHeight / 2, barHeight / 2, huePaint)
        canvas.drawCircle(square.width() * hsv[1], square.height() * (1 - hsv[2]), 9 * density, handlePaint)
        canvas.drawCircle(hueBar.width() * hsv[0] / 360f, hueBar.centerY(), barHeight / 2, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            draggingHue = event.y > square.bottom + gap / 2
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val x = (event.x / width).coerceIn(0f, 1f)
        if (draggingHue) {
            hsv[0] = x * 359.9f
            updateSquareShader()
        } else {
            hsv[1] = x
            hsv[2] = 1 - (event.y / square.height()).coerceIn(0f, 1f)
        }
        invalidate()
        onColorChanged?.invoke(color)
        return true
    }
}

/**
 * Pick a color by touch. [onPreview] follows the finger, [onDone] gets the final color, and
 * Cancel reports the starting color through [onPreview] so a live preview can be undone.
 */
class CosmicColorPicker(
    context: Context,
    title: String,
    private val initial: Int,
    defaultColor: Int? = null,
    private val onPreview: (Int) -> Unit = {},
    private val onDone: (Int) -> Unit
) : Dialog(context) {

    private var settled = false

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val titleView = TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(0, 0, 0, dp(12))
        }
        val picker = ColorPickerView(context).apply { setColor(initial) }
        val preview = View(context)
        val hex = TextView(context).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(dp(10), 0, 0, 0)
        }
        val previewRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            addView(preview, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(hex)
        }
        fun show(color: Int) {
            preview.background = GradientDrawable().apply {
                cornerRadius = 6 * density
                setColor(color)
            }
            hex.text = String.format("#%06x", color and 0xFFFFFF)
        }
        show(initial)
        picker.onColorChanged = { color ->
            show(color)
            onPreview(color)
        }

        fun button(label: String, color: Int, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(color)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            if (defaultColor != null) {
                addView(button("Default", ContextCompat.getColor(context, R.color.text_secondary)) {
                    picker.setColor(defaultColor)
                    show(defaultColor)
                    onPreview(defaultColor)
                })
            }
            addView(button("Cancel", ContextCompat.getColor(context, R.color.text_secondary)) { dismiss() })
            addView(button("Done", CosmicAccent.color) {
                settled = true
                onDone(picker.color)
                dismiss()
            })
        }

        root.addView(titleView)
        root.addView(picker)
        root.addView(previewRow)
        root.addView(buttons)
        setContentView(root)
        CosmicThemeStore.load(context).applyDialogSurface(window, root, R.drawable.bg_card_solid)

        setOnDismissListener {
            if (!settled) onPreview(initial)
        }
    }

    override fun onStart() {
        super.onStart()
        val metrics = context.resources.displayMetrics
        val width = minOf((metrics.widthPixels * 0.9f).toInt(), (360 * metrics.density).toInt())
        window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
