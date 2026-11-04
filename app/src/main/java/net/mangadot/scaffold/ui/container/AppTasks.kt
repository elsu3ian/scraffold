package net.mangadot.scaffold.ui.container

import android.app.ActivityManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.engine.ManifestInstall
import net.mangadot.scaffold.ui.browser.QuickBrowserActivity
import net.mangadot.scaffold.ui.shelf.MainActivity

/**
 * Each web app runs in its own Android task, so leaving one keeps its page alive and it shows up
 * separately in Recents, the way an installed PWA does.
 */
object AppTasks {

    private const val LAST_PAGE_PREFS = "scraffold_last_page"
    private const val BROWSER_TASK = "scraffold://browser"

    /** The address is what tells Android which task an app belongs to. */
    private fun taskUri(appId: String) = Uri.parse("scraffold://app/$appId")

    fun intent(context: Context, appId: String): Intent {
        return Intent(context, WebContainerActivity::class.java).apply {
            data = taskUri(appId)
            putExtra(WebContainerActivity.EXTRA_APP_ID, appId)
        }
    }

    /** After a restart Android brings a task back with its address but not its extras. */
    fun appId(intent: Intent): String {
        return intent.getStringExtra(WebContainerActivity.EXTRA_APP_ID)
            ?: intent.data?.takeIf { it.scheme == "scraffold" }?.lastPathSegment
            ?: ""
    }

    /** Opens the shelf and leaves whatever is running behind it. */
    fun goToShelf(context: Context) {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    fun browserIntent(context: Context): Intent {
        return Intent(context, QuickBrowserActivity::class.java).setData(Uri.parse(BROWSER_TASK))
    }

    fun openBrowser(activity: Activity) {
        val running = findByUri(activity, Uri.parse(BROWSER_TASK))
        if (running != null) {
            running.moveToFront()
        } else {
            activity.startActivity(browserIntent(activity))
        }
    }

    /** Brings the app back as it was left. Only a task it no longer has gets started afresh. */
    fun open(activity: Activity, app: WebAppEntity) = open(activity, app.id, app.url, app.name)

    fun open(activity: Activity, appId: String, url: String, name: String) {
        val running = find(activity, appId)
        if (running != null) {
            running.moveToFront()
        } else {
            activity.startActivity(intent(activity, appId).apply {
                putExtra(WebContainerActivity.EXTRA_APP_URL, url)
                putExtra(WebContainerActivity.EXTRA_APP_NAME, name)
            })
        }
    }

    /** For a removed app: its page must not outlive it. */
    fun close(context: Context, appId: String) {
        restart(context, appId)
        ManifestInstall.clear(context, appId)
    }

    /** Closes its open page, and it opens next time at its start page. */
    fun restart(context: Context, appId: String) {
        find(context, appId)?.finishAndRemoveTask()
        forgetLastPage(context, appId)
    }

    private fun find(context: Context, appId: String) = findByUri(context, taskUri(appId))

    private fun findByUri(context: Context, uri: Uri): ActivityManager.AppTask? {
        return context.getSystemService(ActivityManager::class.java).appTasks.firstOrNull { task ->
            // A task can end while this looks through them
            runCatching { task.taskInfo.baseIntent.data == uri }.getOrDefault(false)
        }
    }

    fun lastPage(context: Context, appId: String): String? {
        return context.getSharedPreferences(LAST_PAGE_PREFS, Context.MODE_PRIVATE).getString(appId, null)
    }

    fun saveLastPage(context: Context, appId: String, url: String) {
        context.getSharedPreferences(LAST_PAGE_PREFS, Context.MODE_PRIVATE).edit().putString(appId, url).apply()
    }

    fun forgetLastPage(context: Context, appId: String) {
        context.getSharedPreferences(LAST_PAGE_PREFS, Context.MODE_PRIVATE).edit().remove(appId).apply()
    }
}
