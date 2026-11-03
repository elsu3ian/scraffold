package net.mangadot.scaffold.ui.folder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.engine.AppIcons
import net.mangadot.scaffold.ui.container.WebContainerActivity
import net.mangadot.scaffold.ui.shelf.ShelfSort

/**
 * A home screen folder like a phone's large folders: the first three apps are big icons that open
 * directly, and the small icons in the last cell open FolderActivity with everything.
 */
open class FolderWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        drawAsync(context, manager, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        drawAsync(context, manager, intArrayOf(id))
    }

    private fun drawAsync(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                draw(context, manager, ids)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Which widget opened the folder, so it can find where each app was drawn. */
        const val EXTRA_STYLE = "net.mangadot.scaffold.folder.STYLE"
        const val STYLE_UNKNOWN = 0
        const val STYLE_LARGE = 1
        const val STYLE_CLASSIC = 2

        // Clear of the big icons' 1 to 3, and one per style so each keeps its own extra
        private const val OPEN_FOLDER_REQUEST = 100
        private const val ICON_PX = 144
        private const val ICON_CORNER = 0.25f
        private const val SMALL_ICON_GAP_PX = 12f
        private const val SMALL_ICONS = 4
        private const val CLASSIC_PX = 192
        private const val CLASSIC_COLUMNS = 3
        private const val CLASSIC_PADDING = 0.14f
        private const val CLASSIC_GAP = 0.06f
        private const val LABEL_DP = 22
        private const val ICON_BASE_COLOR = 0x33FFFFFF
        // The same see-through backdrop as bg_widget_folder
        private val CLASSIC_BASE_COLOR = 0x8C1C1C22.toInt()
        private val BIG_ICON_CELLS = intArrayOf(R.id.widget_folder_app1, R.id.widget_folder_app2, R.id.widget_folder_app3)
        private val SHOWN_APPS = maxOf(BIG_ICON_CELLS.size + SMALL_ICONS, CLASSIC_COLUMNS * CLASSIC_COLUMNS)
        private val PROVIDERS = listOf(FolderWidgetProvider::class.java, ClassicFolderWidgetProvider::class.java)

        /** For work that has to finish after the screen that started it has closed. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Apps only change while this process runs, so watching here catches every add, edit and removal. */
        fun watchApps(context: Context) {
            val appContext = context.applicationContext
            scope.launch {
                AppDatabase.getInstance(appContext).webAppDao().getAllWebApps()
                    .map { apps -> ShelfSort.current(appContext, apps).take(SHOWN_APPS).map { listOf(it.id, it.name, it.url, it.iconUrl) } }
                    .distinctUntilChanged()
                    .collect { drawAll(appContext) }
            }
        }

        /** For changes the database can't see, such as the shelf's sort order. */
        fun refresh(context: Context) {
            val appContext = context.applicationContext
            scope.launch { drawAll(appContext) }
        }

        private suspend fun drawAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = PROVIDERS.flatMap { manager.getAppWidgetIds(ComponentName(context, it)).asList() }.toIntArray()
            if (ids.isNotEmpty()) draw(context, manager, ids)
        }

        private suspend fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val apps = ShelfSort.current(context, AppDatabase.getInstance(context).webAppDao().getAll())
            ids.forEach { id ->
                val options = manager.getAppWidgetOptions(id)
                val classic = manager.getAppWidgetInfo(id)?.provider?.className == ClassicFolderWidgetProvider::class.java.name
                manager.updateAppWidget(id, if (classic) classicViews(context, apps, options) else views(context, apps, options))
            }
        }

        private fun openFolder(context: Context, style: Int) = PendingIntent.getActivity(
            context,
            OPEN_FOLDER_REQUEST + style,
            Intent(context, FolderActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_STYLE, style),
            // Mutable so the launcher can fill in where the tap was, which an immutable one drops.
            // The intent names our own screen, so filling in can't send it anywhere else
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        private fun views(context: Context, apps: List<WebAppEntity>, options: Bundle): RemoteViews {
            return RemoteViews(context.packageName, R.layout.widget_folder).apply {
                // The icons fly out of where the box drew them, so the box takes every tap but the big icons',
                // the small icons' cell included. A tap on the label only says roughly where the folder is.
                setOnClickPendingIntent(R.id.widget_folder_root, openFolder(context, STYLE_UNKNOWN))
                setOnClickPendingIntent(R.id.widget_folder_box, openFolder(context, STYLE_LARGE))
                setImageViewBitmap(R.id.widget_folder_more, smallIcons(apps.drop(BIG_ICON_CELLS.size).take(SMALL_ICONS)))

                BIG_ICON_CELLS.forEachIndexed { index, cell ->
                    val app = apps.getOrNull(index)
                    if (app == null) {
                        setViewVisibility(cell, View.INVISIBLE)
                    } else {
                        setViewVisibility(cell, View.VISIBLE)
                        setImageViewBitmap(cell, bigIcon(app))
                        setContentDescription(cell, app.name)
                        setOnClickPendingIntent(cell, openApp(context, app, index + 1))
                    }
                }
                keepSquare(context, this, R.id.widget_folder_root, options)
            }
        }

        /** One cell, like a folder of app icons: a few apps in tiny icons, and a tap opens them all. */
        private fun classicViews(context: Context, apps: List<WebAppEntity>, options: Bundle): RemoteViews {
            return RemoteViews(context.packageName, R.layout.widget_folder_classic).apply {
                setOnClickPendingIntent(R.id.widget_folder_classic_root, openFolder(context, STYLE_UNKNOWN))
                setOnClickPendingIntent(R.id.widget_folder_classic_icon, openFolder(context, STYLE_CLASSIC))
                setImageViewBitmap(R.id.widget_folder_classic_icon, classicIcon(apps.take(CLASSIC_COLUMNS * CLASSIC_COLUMNS)))
                keepSquare(context, this, R.id.widget_folder_classic_root, options)
            }
        }

        private fun openApp(context: Context, app: WebAppEntity, requestCode: Int): PendingIntent {
            val intent = Intent(context, FolderLaunchActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(WebContainerActivity.EXTRA_APP_ID, app.id)
                putExtra(WebContainerActivity.EXTRA_APP_URL, app.url)
                putExtra(WebContainerActivity.EXTRA_APP_NAME, app.name)
            }
            return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /** Home screen cells are rarely square, so the space left over goes around the folder. */
        private fun keepSquare(context: Context, views: RemoteViews, root: Int, options: Bundle) {
            val portrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            val width = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            val height = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) - LABEL_DP
            if (width <= 0 || height <= 0) return
            val side = minOf(width, height)
            val density = context.resources.displayMetrics.density
            val sides = ((width - side) / 2 * density).toInt()
            val ends = ((height - side) / 2 * density).toInt()
            views.setViewPadding(root, sides, ends, sides, ends)
        }

        /**
         * Where the widget of [style] drew each app, in shelf order, given the [bounds] the launcher
         * reported for the tap. Both styles draw square icons centred in their views.
         */
        fun iconSpots(context: Context, style: Int, bounds: Rect): List<RectF> {
            val area = RectF(bounds)
            return when (style) {
                STYLE_CLASSIC -> {
                    val folder = square(area)
                    classicSpots(folder.width()).onEach { it.offset(folder.left, folder.top) }
                }
                STYLE_LARGE -> {
                    val padding = context.resources.getDimension(R.dimen.widget_folder_padding)
                    val margin = context.resources.getDimension(R.dimen.widget_folder_cell_margin)
                    val cellWidth = (area.width() - padding * 2) / 2
                    val cellHeight = (area.height() - padding * 2) / 2
                    val cells = List(BIG_ICON_CELLS.size + 1) { index ->
                        val left = area.left + padding + (index % 2) * cellWidth
                        val top = area.top + padding + (index / 2) * cellHeight
                        square(RectF(left + margin, top + margin, left + cellWidth - margin, top + cellHeight - margin))
                    }
                    val more = cells.last()
                    cells.dropLast(1) + smallSpots(more.width()).onEach { it.offset(more.left, more.top) }
                }
                else -> emptyList()
            }
        }

        private fun square(area: RectF): RectF {
            val half = minOf(area.width(), area.height()) / 2
            return RectF(area.centerX() - half, area.centerY() - half, area.centerX() + half, area.centerY() + half)
        }

        private fun bigIcon(app: WebAppEntity): Bitmap {
            val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            drawIcon(Canvas(bitmap), app, RectF(0f, 0f, ICON_PX.toFloat(), ICON_PX.toFloat()))
            return bitmap
        }

        /** Two across from the top, leaving the rest of the cell empty as a phone's folder does. */
        private fun smallIcons(apps: List<WebAppEntity>): Bitmap {
            val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            apps.zip(smallSpots(ICON_PX.toFloat())).forEach { (app, spot) -> drawIcon(canvas, app, spot) }
            return bitmap
        }

        /** The small icons' places in a cell [side] wide. */
        private fun smallSpots(side: Float): List<RectF> {
            val gap = side * SMALL_ICON_GAP_PX / ICON_PX
            val size = (side - gap) / 2
            return List(SMALL_ICONS) { index ->
                val left = (index % 2) * (size + gap)
                val top = (index / 2) * (size + gap)
                RectF(left, top, left + size, top + size)
            }
        }

        /** The folder's backdrop with the apps in rows of three, the way a launcher draws a closed folder. */
        private fun classicIcon(apps: List<WebAppEntity>): Bitmap {
            val bitmap = Bitmap.createBitmap(CLASSIC_PX, CLASSIC_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val full = CLASSIC_PX.toFloat()
            val corner = full * ICON_CORNER
            canvas.drawRoundRect(RectF(0f, 0f, full, full), corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CLASSIC_BASE_COLOR })

            apps.zip(classicSpots(full)).forEach { (app, spot) -> drawIcon(canvas, app, spot) }
            return bitmap
        }

        /** The apps' places in a classic folder [side] wide. */
        private fun classicSpots(side: Float): List<RectF> {
            val padding = side * CLASSIC_PADDING
            val gap = side * CLASSIC_GAP
            val size = (side - padding * 2 - gap * (CLASSIC_COLUMNS - 1)) / CLASSIC_COLUMNS
            return List(CLASSIC_COLUMNS * CLASSIC_COLUMNS) { index ->
                val left = padding + (index % CLASSIC_COLUMNS) * (size + gap)
                val top = padding + (index / CLASSIC_COLUMNS) * (size + gap)
                RectF(left, top, left + size, top + size)
            }
        }

        private fun drawIcon(canvas: Canvas, app: WebAppEntity, area: RectF) {
            val corner = area.width() * ICON_CORNER
            val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ICON_BASE_COLOR }
            canvas.drawRoundRect(area, corner, corner, base)

            val icon = AppIcons.thumbnail(app.iconUrl, area.width().toInt())
            if (icon != null) {
                // Cropped to fill, the same as the shelf
                val scale = area.width() / minOf(icon.width, icon.height)
                val matrix = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(
                        area.left + (area.width() - icon.width * scale) / 2,
                        area.top + (area.height() - icon.height * scale) / 2
                    )
                }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    shader = BitmapShader(icon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
                }
                canvas.drawRoundRect(area, corner, corner, paint)
            } else {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    textSize = area.height() * 0.5f
                }
                val baseline = area.centerY() - (paint.descent() + paint.ascent()) / 2
                canvas.drawText(app.name.trim().take(1).uppercase(), area.centerX(), baseline, paint)
            }
        }
    }
}
