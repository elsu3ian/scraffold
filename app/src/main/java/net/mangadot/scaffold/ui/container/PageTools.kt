package net.mangadot.scaffold.ui.container

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebSettings
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.databinding.LayoutDialogTextSizeBinding
import net.mangadot.scaffold.engine.PageZoom
import net.mangadot.scaffold.engine.ScriptEngine
import net.mangadot.scaffold.engine.userscript.UserscriptMeta
import net.mangadot.scaffold.ui.theme.showCosmic
import net.mangadot.scaffold.ui.userscript.UserscriptsActivity

/** What the side bubble offers for the page on screen, the same in web apps and the browser. */
object PageTools {

    private const val TEXT_ZOOM_PREFS = "scraffold_text_zoom"
    private const val TEXT_ZOOM_DEFAULT = 100
    private const val TEXT_ZOOM_MIN = 50
    private const val TEXT_ZOOM_MAX = 200
    private const val TEXT_ZOOM_STEP = 10
    private const val PAGE_ZOOM_PREFS = "scraffold_page_zoom"
    private const val PAGE_ZOOM_MIN = 50
    private const val PAGE_ZOOM_MAX = 200
    private const val PAGE_ZOOM_STEP = 5

    fun savedTextZoom(context: Context, key: String): Int {
        return context.getSharedPreferences(TEXT_ZOOM_PREFS, Context.MODE_PRIVATE).getInt(key, TEXT_ZOOM_DEFAULT)
    }

    fun savedPageZoom(context: Context, key: String): Int {
        return context.getSharedPreferences(PAGE_ZOOM_PREFS, Context.MODE_PRIVATE).getInt(key, PageZoom.DEFAULT_PERCENT)
    }

    fun showSizeDialog(activity: AppCompatActivity, key: String, webSettings: WebSettings, pageZoom: PageZoom) {
        val view = LayoutDialogTextSizeBinding.inflate(activity.layoutInflater)
        val textPrefs = activity.getSharedPreferences(TEXT_ZOOM_PREFS, Context.MODE_PRIVATE)
        val pagePrefs = activity.getSharedPreferences(PAGE_ZOOM_PREFS, Context.MODE_PRIVATE)

        fun applyText(percent: Int) {
            webSettings.textZoom = percent
            view.tvTextSizeValue.text = "$percent%"
            textPrefs.edit().putInt(key, percent).apply()
        }

        fun applyPage(percent: Int) {
            pageZoom.set(percent)
            view.tvPageSizeValue.text = "$percent%"
            pagePrefs.edit().putInt(key, percent).apply()
        }

        fun bind(seek: SeekBar, label: TextView, current: Int, min: Int, max: Int, step: Int, apply: (Int) -> Unit) {
            seek.max = (max - min) / step
            seek.progress = (current - min) / step
            label.text = "$current%"
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) apply(min + progress * step)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        bind(view.seekPageSize, view.tvPageSizeValue, savedPageZoom(activity, key), PAGE_ZOOM_MIN, PAGE_ZOOM_MAX, PAGE_ZOOM_STEP, ::applyPage)
        bind(view.seekTextSize, view.tvTextSizeValue, webSettings.textZoom, TEXT_ZOOM_MIN, TEXT_ZOOM_MAX, TEXT_ZOOM_STEP, ::applyText)

        AlertDialog.Builder(activity)
            .setTitle("Page and font size")
            .setView(view.root)
            .setNeutralButton("Reset") { _, _ ->
                applyPage(PageZoom.DEFAULT_PERCENT)
                applyText(TEXT_ZOOM_DEFAULT)
            }
            .setPositiveButton("Done", null)
            .showCosmic()
    }

    /** The running page's scripts and any commands they registered with GM_registerMenuCommand. */
    fun showScriptMenu(activity: AppCompatActivity, url: String, scripts: ScriptRepository, engine: ScriptEngine, scriptsOn: Boolean = true) {
        activity.lifecycleScope.launch {
            val matching = scripts.getEnabledScriptsList()
                .filter { scriptsOn && UserscriptMeta.parse(it.code).appliesTo(url) }
            val failed = engine.failedScripts
            val (broken, running) = matching.partition { it.id in failed }
            val commands = engine.bridge.menuCommands.toList()
            val labels = running.map { "● ${it.name}" } + commands.map { "${it.scriptName}: ${it.caption}" } +
                broken.map { "⚠ ${it.name}: ${failed[it.id]}" } + "Manage userscripts"
            AlertDialog.Builder(activity)
                .setTitle(if (running.isEmpty()) "No scripts on this page" else "${running.size} running on this page")
                .setItems(labels.toTypedArray()) { _, which ->
                    val commandIndex = which - running.size
                    when {
                        which < running.size -> openScriptManager(activity, url)
                        commandIndex < commands.size -> engine.bridge.runMenuCommand(commands[commandIndex])
                        else -> openScriptManager(activity, url)
                    }
                }
                .showCosmic()
        }
    }

    private fun openScriptManager(activity: AppCompatActivity, url: String) {
        activity.startActivity(Intent(activity, UserscriptsActivity::class.java).apply {
            putExtra(UserscriptsActivity.EXTRA_FILTER_DOMAIN, Uri.parse(url).host.orEmpty())
        })
    }
}
