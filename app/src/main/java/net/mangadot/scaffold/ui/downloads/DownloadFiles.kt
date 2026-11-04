package net.mangadot.scaffold.ui.downloads

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.DownloadEntity
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Where a download's file is, and opening, sharing and deleting it. */
object DownloadFiles {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Files a page is writing in this run of the app. One still marked as running otherwise was cut off. */
    val writing: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun record(context: Context, download: DownloadEntity) {
        val downloads = AppDatabase.getInstance(context).downloadDao()
        scope.launch { downloads.insert(download) }
    }

    fun showPage(context: Context) {
        val intent = Intent(context, DownloadsActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** A content address other apps can be given, or null while the file isn't there. */
    fun uriFor(context: Context, download: DownloadEntity): Uri? {
        if (download.state != DownloadEntity.DONE) return null
        if (download.systemId != DownloadEntity.NO_SYSTEM_ID) {
            return runCatching { downloadManager(context).getUriForDownloadedFile(download.systemId) }.getOrNull()
        }
        return download.fileUri?.let { shareable(context, Uri.parse(it)) }
    }

    /** What thumbnails are read from: the file's place in the phone's media, where Android has one. */
    fun mediaUriFor(download: DownloadEntity): Uri? =
        download.fileUri?.let(Uri::parse)?.takeIf { it.scheme == "content" && it.authority == MediaStore.AUTHORITY }

    fun open(context: Context, download: DownloadEntity) {
        val uri = uriFor(context, download)
        if (uri == null) {
            Toast.makeText(context, "This file isn't on your phone anymore", Toast.LENGTH_SHORT).show()
            return
        }
        open(context, uri, download.mimeType)
    }

    fun open(context: Context, uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(shareable(context, uri) ?: uri, mimeType.ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app on this phone opens this file", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't open this file", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, download: DownloadEntity) {
        val uri = uriFor(context, download) ?: run {
            Toast.makeText(context, "This file isn't on your phone anymore", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(download.mimeType.ifEmpty { "*/*" })
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Lets the app picked see the file too, not just the chooser
        send.clipData = ClipData.newRawUri(download.name, uri)
        context.startActivity(Intent.createChooser(send, download.name))
    }

    /** Removes the file from the phone, then from the list. A running download stops. */
    suspend fun delete(context: Context, download: DownloadEntity) {
        if (download.systemId != DownloadEntity.NO_SYSTEM_ID) {
            runCatching { downloadManager(context).remove(download.systemId) }
        } else {
            download.fileUri?.let(Uri::parse)?.let { uri ->
                runCatching {
                    if (uri.scheme == "file") uri.path?.let { File(it).delete() }
                    else context.contentResolver.delete(uri, null, null)
                }
            }
        }
        AppDatabase.getInstance(context).downloadDao().delete(download.id)
    }

    /** Whether the file is still on the phone, for files Scraffold wrote itself. */
    fun stillThere(context: Context, download: DownloadEntity): Boolean {
        val uri = download.fileUri?.let(Uri::parse) ?: return false
        return when (uri.scheme) {
            "file" -> uri.path?.let { File(it).exists() } == true
            else -> runCatching {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                    ?.use { it.moveToFirst() }
            }.getOrNull() == true
        }
    }

    fun downloadManager(context: Context): DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** Before Android 10 a page's file is a plain path, which no other app may be handed; its media entry may. */
    private fun shareable(context: Context, uri: Uri): Uri? {
        if (uri.scheme != "file") return uri
        val path = uri.path ?: return null
        val files = MediaStore.Files.getContentUri("external")
        return runCatching {
            context.contentResolver.query(
                files, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path), null
            )?.use { if (it.moveToFirst()) Uri.withAppendedPath(files, it.getLong(0).toString()) else null }
        }.getOrNull()
    }
}
