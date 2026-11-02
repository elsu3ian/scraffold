package net.mangadot.scaffold.engine

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast

/** Saves files to Downloads with the page's login, so files behind an account come through. */
object WebDownloads {

    fun start(
        context: Context,
        url: String,
        cookies: CookieManager,
        fileName: String? = null,
        contentDisposition: String? = null,
        mimeType: String? = null,
        userAgent: String? = null
    ) {
        if (!URLUtil.isNetworkUrl(url)) {
            Toast.makeText(context, "This file can't be saved directly", Toast.LENGTH_SHORT).show()
            return
        }
        val name = fileName?.takeIf { it.isNotEmpty() } ?: URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        mimeType?.let { request.setMimeType(it) }
        userAgent?.let { request.addRequestHeader("User-Agent", it) }
        cookies.getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        try {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(context, "Downloading $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't start the download", Toast.LENGTH_SHORT).show()
        }
    }
}
