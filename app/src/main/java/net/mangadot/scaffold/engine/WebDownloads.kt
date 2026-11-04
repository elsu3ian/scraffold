package net.mangadot.scaffold.engine

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.entity.DownloadEntity
import net.mangadot.scaffold.ui.downloads.DownloadFiles
import net.mangadot.scaffold.ui.theme.CosmicAccent

/** Saves files to Downloads with the page's login, so files behind an account come through. */
object WebDownloads {

    private const val STORAGE_REQUEST = 4107
    // Long enough to reach the button
    private const val SHOWN_FOR = 6000

    /** Everything [page] downloads goes to Downloads, the way a browser saves it, files the page made itself included. */
    fun listen(activity: Activity, page: DeckWebView, cookies: CookieManager, onDownload: (String) -> Unit = {}) {
        page.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            start(activity, url, cookies, null, contentDisposition, mimeType, userAgent, page)
            onDownload(url)
        }
    }

    fun start(
        context: Context,
        url: String,
        cookies: CookieManager,
        fileName: String? = null,
        contentDisposition: String? = null,
        mimeType: String? = null,
        userAgent: String? = null,
        page: DeckWebView? = null,
        appId: String? = page?.appId
    ) {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme == "blob" || scheme == "data") {
            val files = page?.files
            if (files == null) {
                Toast.makeText(context, "This file can't be saved directly", Toast.LENGTH_SHORT).show()
            } else {
                files.save(context, url, contentDisposition, mimeType)
            }
            return
        }
        if (!URLUtil.isNetworkUrl(url)) {
            Toast.makeText(context, "This file can't be saved directly", Toast.LENGTH_SHORT).show()
            return
        }
        if (!canWrite(context)) return
        val name = fileName?.takeIf { it.isNotEmpty() } ?: URLUtil.guessFileName(url, contentDisposition, mimeType)
        val mime = mimeType?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: mimeType.orEmpty()
        val request = DownloadManager.Request(Uri.parse(url))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            .setTitle(name)
        if (mime.isNotEmpty()) request.setMimeType(mime)
        // Downloads tried again from the Downloads page have no page, and still name a browser
        request.addRequestHeader("User-Agent", userAgent ?: DeckWebView.mobileUserAgent(context))
        cookies.getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        // Hosts that only hand files to their own pages check where the request came from. While a link
        // turns into a download the page already reports the file's address, so the page shown is asked.
        val from = page?.copyBackForwardList()?.currentItem?.url
        from?.let { referrer(it, url) }?.let { request.addRequestHeader("Referer", it) }
        val systemId = try {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't start the download", Toast.LENGTH_SHORT).show()
            return
        }
        // The file's own site, as browsers show it; the page can be an older one when a link was only a download
        val site = siteOf(url) ?: siteOf(from).orEmpty()
        DownloadFiles.record(
            context,
            DownloadEntity(name = name, mimeType = mime, site = site, url = url, appId = appId, systemId = systemId)
        )
        if (page != null) started(page, name) else Toast.makeText(context, "Downloading $name", Toast.LENGTH_SHORT).show()
    }

    /** Says a download began, with a way to the Downloads page, where its progress shows. */
    fun started(page: View, name: String) {
        bar(page, "Downloading $name", SHOWN_FOR)
            .setAction("Downloads") { DownloadFiles.showPage(page.context) }
            .show()
    }

    fun saved(page: View, name: String, open: () -> Unit) {
        bar(page, "Saved $name", SHOWN_FOR)
            .setAction("Open") { open() }
            .show()
    }

    fun notice(page: View, text: String) = bar(page, text, Snackbar.LENGTH_LONG).show()

    /** Dark like the rest of the app, with its accent on the button. */
    private fun bar(page: View, text: String, duration: Int): Snackbar =
        Snackbar.make(page, text, duration)
            .setBackgroundTint(ContextCompat.getColor(page.context, R.color.bg_surface_elevated))
            .setTextColor(ContextCompat.getColor(page.context, R.color.text_primary))
            .setActionTextColor(CosmicAccent.color)

    /** The site a download came from, as the Downloads page names it. */
    fun siteOf(url: String?): String? {
        if (url == null || !URLUtil.isNetworkUrl(url)) return null
        return Uri.parse(url).host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }
    }

    /**
     * Before Android 10, saving to Downloads needs the storage permission. Without it, this asks for it
     * where it can, and the file can be downloaded once it's given.
     */
    fun canWrite(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return true
        // A page's save picker asks from WebView's own thread, where neither of these may run
        Handler(Looper.getMainLooper()).post {
            (context as? Activity)?.let { ActivityCompat.requestPermissions(it, arrayOf(permission), STORAGE_REQUEST) }
            Toast.makeText(context, "Allow storage for Scraffold, then download again", Toast.LENGTH_LONG).show()
        }
        return false
    }

    /** The whole page address on its own site, only the site elsewhere, as browsers send it. */
    private fun referrer(page: String, target: String): String? {
        val from = Uri.parse(page)
        if (!URLUtil.isNetworkUrl(page) || from.host == null) return null
        // An address typed or opened on its own came from no page
        if (page.substringBefore('#') == target.substringBefore('#')) return null
        val sameOrigin = from.scheme == Uri.parse(target).scheme && from.authority == Uri.parse(target).authority
        if (sameOrigin) return page.substringBefore('#')
        // An https page never names itself to an http one
        if (from.scheme == "https" && Uri.parse(target).scheme == "http") return null
        return "${from.scheme}://${from.authority}/"
    }
}
