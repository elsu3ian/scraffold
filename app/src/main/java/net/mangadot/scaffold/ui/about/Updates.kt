package net.mangadot.scaffold.ui.about

import android.app.Activity
import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Environment
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mangadot.scaffold.BuildConfig
import net.mangadot.scaffold.R
import net.mangadot.scaffold.ui.browser.ExternalLinks
import net.mangadot.scaffold.ui.theme.CosmicCard
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Finds newer builds, says what's new in them, and installs them. Builds are published from main rather than
 * looked up as GitHub Releases, so the latest version is the one main's build file declares, its notes are its
 * section of main's changelog, and the download is wherever the README's Download APK button points. All three
 * are plain files on raw.githubusercontent.com, which isn't held to the GitHub API's 60 anonymous requests an hour.
 */
object Updates {

    sealed class Check {
        data class Available(val version: String, val code: Int, val url: String, val notes: CharSequence?) : Check()
        object Current : Check()
        object Failed : Check()
    }

    suspend fun latest(context: Context): Check = withContext(Dispatchers.IO) {
        try {
            val repository = Uri.parse(context.getString(R.string.github_repo_url)).path.orEmpty().trim('/')
            val raw = "https://raw.githubusercontent.com/$repository/$BRANCH"
            val build = download("$raw/app/build.gradle.kts") ?: return@withContext Check.Failed
            val latestName = VERSION_NAME.find(build)?.groupValues?.get(1) ?: return@withContext Check.Failed
            val latestCode = versionCodeOf(latestName) ?: return@withContext Check.Failed
            if (latestCode <= BuildConfig.VERSION_CODE) return@withContext Check.Current
            val link = download("$raw/README.md")?.let { DOWNLOAD_BUTTON.find(it)?.groupValues?.get(1) }
                ?: context.getString(R.string.github_repo_url)
            val notes = download("$raw/CHANGELOG.md")?.let { releaseNotes(it, latestName) }
            Check.Available(latestName, latestCode, link, notes)
        } catch (e: Exception) {
            Check.Failed
        }
    }

    /**
     * Looks on the home screen at most every [CHECK_EVERY_MS], quietly, and offers a newer build unless it was
     * skipped. A failed look waits for the next time like any other.
     */
    fun offerIfDue(activity: Activity, scope: LifecycleCoroutineScope) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_EVERY_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        scope.launch {
            val found = latest(activity) as? Check.Available ?: return@launch
            if (found.code == prefs.getInt(KEY_SKIPPED, 0) || activity.isFinishing || activity.isDestroyed) return@launch
            offer(activity, found, canSkip = true)
        }
    }

    /** Says what's new in [found] and offers to install it, on the same card as the shelf guide. */
    fun offer(activity: Activity, found: Check.Available, canSkip: Boolean) {
        val density = activity.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val notes = CosmicCard.body(activity).apply {
            text = found.notes ?: "A new version is ready to install."
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        // However long the notes, the card stays the guide's size and they scroll inside it
        val maxNotes = (activity.resources.displayMetrics.heightPixels * NOTES_SHARE).toInt()
        val scroller = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxNotes, View.MeasureSpec.AT_MOST))
        }.apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(pad * 2)
            addView(notes)
        }
        val buttons = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))
            if (canSkip) {
                addView(CosmicCard.button(activity, "Skip this version", main = false) {
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SKIPPED, found.code).apply()
                    dialog.dismiss()
                })
            }
            addView(CosmicCard.button(activity, "Later", main = false) { dialog.dismiss() })
            addView(CosmicCard.button(activity, "Install", main = true) {
                dialog.dismiss()
                install(activity, found)
            })
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, (8 * density).toInt())
            background = CosmicCard.background(activity)
            addView(CosmicCard.title(activity, "Scraffold v${found.version} is out"))
            if (found.notes != null) {
                addView(TextView(activity).apply {
                    text = "What's new"
                    setTextColor(ContextCompat.getColor(activity, R.color.text_muted))
                    textSize = 11f
                    letterSpacing = 0.08f
                    isAllCaps = true
                    setPadding(0, (8 * density).toInt(), 0, 0)
                })
            }
            addView(scroller)
            addView(buttons)
        }
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // As wide as the guide's card: the screen less its margins
            val width = activity.resources.displayMetrics.widthPixels - (2 * CARD_MARGIN_DP * density).toInt()
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    /**
     * Downloads the APK and hands it to Android's installer once it's in. Android asks, the first time, to let
     * Scraffold install apps. A link that isn't an APK opens in the browser instead.
     */
    private fun install(activity: Activity, found: Check.Available) {
        if (!found.url.endsWith(".apk", ignoreCase = true)) {
            ExternalLinks.open(activity, Uri.parse(found.url))
            return
        }
        val context = activity.applicationContext
        val downloads = context.getSystemService(DownloadManager::class.java)
        val request = DownloadManager.Request(Uri.parse(found.url))
            .setTitle("Scraffold v${found.version}")
            .setMimeType(APK_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "Scraffold-v${found.version}.apk")
        val id = try {
            downloads.enqueue(request)
        } catch (e: Exception) {
            ExternalLinks.open(activity, Uri.parse(found.url))
            return
        }
        Toast.makeText(activity, "Downloading Scraffold v${found.version}…", Toast.LENGTH_SHORT).show()

        // Registered on the app rather than the screen, so leaving the screen doesn't lose the download
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                context.unregisterReceiver(this)
                val apk = if (succeeded(downloads, id)) downloads.getUriForDownloadedFile(id) else null
                if (apk == null) {
                    Toast.makeText(context, "The update didn't download. Try again from About.", Toast.LENGTH_LONG).show()
                    return
                }
                val installer = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apk, APK_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(installer)
                } catch (e: Exception) {
                    Toast.makeText(context, "Open the download from your notifications to install it.", Toast.LENGTH_LONG).show()
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun succeeded(downloads: DownloadManager, id: Long): Boolean =
        downloads.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            cursor.moveToFirst() &&
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
        } ?: false

    /**
     * The changelog's section for [version], under a "## [version]" heading: its "### Added" and the like as
     * bold headings, each entry a paragraph of its own with its bold name kept bold, and other Markdown marks gone.
     */
    internal fun releaseNotes(changelog: String, version: String): CharSequence? {
        val lines = changelog.lines()
        val start = lines.indexOfFirst { it.trim().startsWith("## [$version]") }
        if (start < 0) return null
        val section = lines.drop(start + 1).takeWhile { !it.startsWith("## ") && it.trim() != "---" }
        val notes = SpannableStringBuilder()
        for (line in section.map { it.trim() }.filter { it.isNotEmpty() }) {
            if (notes.isNotEmpty()) notes.append("\n\n")
            when {
                line.startsWith("### ") -> bold(notes, line.removePrefix("### "))
                line.startsWith("- ") -> {
                    notes.append("• ")
                    val entry = line.removePrefix("- ").replace("`", "")
                    val name = BOLD_NAME.find(entry)?.takeIf { it.range.first == 0 }
                    if (name != null) {
                        bold(notes, name.groupValues[1])
                        notes.append(entry.substring(name.range.last + 1).replace("**", ""))
                    } else {
                        notes.append(entry.replace("**", ""))
                    }
                }
                else -> notes.append(line.replace("**", "").replace("`", ""))
            }
        }
        return notes.takeIf { it.isNotEmpty() }
    }

    private fun bold(into: SpannableStringBuilder, text: String) {
        val from = into.length
        into.append(text)
        into.setSpan(StyleSpan(Typeface.BOLD), from, into.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun download(url: String): String? {
        OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
            return if (response.isSuccessful) response.body?.string() else null
        }
    }

    /**
     * The version code a version name stands for, worked out as the build file does: x.y.z gives
     * (x * 10000 + y * 100 + z) * 10, and a final release 9 more than its betas. Null for a name that
     * doesn't fit.
     */
    fun versionCodeOf(name: String): Int? {
        val parts = name.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
        if (parts.size != 3 || parts[1] >= 100 || parts[2] >= 100) return null
        val final = if (name.contains('-')) 0 else 9
        return (parts[0] * 10000 + parts[1] * 100 + parts[2]) * 10 + final
    }

    private const val BRANCH = "main"
    private const val APK_TYPE = "application/vnd.android.package-archive"
    private const val PREFS = "updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SKIPPED = "skipped_version_code"
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
    private const val NOTES_SHARE = 0.4f
    private const val CARD_MARGIN_DP = 20
    private val BOLD_NAME = Regex("""\*\*([^*]+)\*\*""")
    private val VERSION_NAME = Regex("""\bversionName\s*=\s*"([^"]+)"""")
    /** The README's Download APK badge: [![Download APK](badge)](link) */
    private val DOWNLOAD_BUTTON = Regex("""\[!\[Download APK]\([^)]*\)]\(([^)\s]+)\)""")
}
