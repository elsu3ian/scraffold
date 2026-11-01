package net.mangadot.scaffold.engine.cache

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat

/**
 * Manages Scraffold storage & cache lifecycle:
 * - 500 MB automatic eviction daemon
 * - Preserves persistent cookies, LocalStorage, and IndexedDB logins
 * - 1-tap manual purge
 */
object CacheManager {

    private const val DEFAULT_AUTO_PURGE_LIMIT_MB = 500L
    private const val BYTES_PER_MB = 1024L * 1024L

    suspend fun getCacheSizeBytes(context: Context): Long = withContext(Dispatchers.IO) {
        var totalSize = 0L
        
        // Android app cache dir
        context.cacheDir?.let { totalSize += getFolderSize(it) }

        // WebView internal cache dirs
        val webViewDir = File(context.applicationInfo.dataDir, "app_webview")
        if (webViewDir.exists()) {
            val cacheSubDir = File(webViewDir, "Default/Cache")
            if (cacheSubDir.exists()) totalSize += getFolderSize(cacheSubDir)
            
            val codeCacheDir = File(webViewDir, "Default/Code Cache")
            if (codeCacheDir.exists()) totalSize += getFolderSize(codeCacheDir)
        }

        totalSize
    }

    suspend fun getFormattedCacheSize(context: Context): String {
        val bytes = getCacheSizeBytes(context)
        val mb = bytes.toDouble() / BYTES_PER_MB
        return if (mb < 1.0) {
            "${(bytes / 1024)} KB"
        } else {
            val df = DecimalFormat("#,##0.0")
            "${df.format(mb)} MB"
        }
    }

    /**
     * Checks if current cache footprint exceeds 500 MB limit,
     * and automatically purges temporary cache if threshold is breached.
     */
    suspend fun checkAndAutoPurge(context: Context, limitMb: Long = DEFAULT_AUTO_PURGE_LIMIT_MB): Boolean = withContext(Dispatchers.IO) {
        val currentSizeMb = getCacheSizeBytes(context) / BYTES_PER_MB
        if (currentSizeMb >= limitMb) {
            purgeTemporaryHttpCache(context)
            return@withContext true
        }
        false
    }

    /**
     * Safely evicts temporary HTTP media & script caches while preserving user logins (Cookies & LocalStorage)
     */
    suspend fun purgeTemporaryHttpCache(context: Context) = withContext(Dispatchers.IO) {
        // Clear standard cache dir
        context.cacheDir?.let { deleteContents(it) }

        // Clear WebView specific HTTP caches only
        val webViewDir = File(context.applicationInfo.dataDir, "app_webview")
        if (webViewDir.exists()) {
            val cacheSubDir = File(webViewDir, "Default/Cache")
            if (cacheSubDir.exists()) deleteContents(cacheSubDir)

            val codeCacheDir = File(webViewDir, "Default/Code Cache")
            if (codeCacheDir.exists()) deleteContents(codeCacheDir)
        }
    }

    private fun getFolderSize(dir: File): Long {
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (file in files) {
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }

    private fun deleteContents(dir: File) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                deleteContents(file)
            }
            file.delete()
        }
    }
}
