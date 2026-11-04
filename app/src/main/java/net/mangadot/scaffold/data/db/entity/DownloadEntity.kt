package net.mangadot.scaffold.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A file downloaded from a page, for the Downloads page. Android's download manager fetches links
 * ([systemId]); files a page made itself are written by Scraffold ([fileUri]).
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val mimeType: String,
    /** The site the download came from, as shown under its name. */
    val site: String,
    /** Where the file was fetched from, empty for one a page made itself. */
    val url: String,
    /** The app it was downloaded in, whose login trying again uses. Null for the shared login. */
    val appId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val systemId: Long = NO_SYSTEM_ID,
    val fileUri: String? = null,
    /** The whole file, 0 while that isn't known. */
    val size: Long = 0,
    /** How much of a file a page made has been written so far. */
    val written: Long = 0,
    val state: Int = RUNNING
) {
    companion object {
        const val NO_SYSTEM_ID = -1L
        const val RUNNING = 0
        const val DONE = 1
        const val FAILED = 2
    }
}
