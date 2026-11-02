package net.mangadot.scaffold.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One page visited in the quick browser; visiting it again moves it back to the top. */
@Entity(tableName = "browser_history")
data class BrowserHistoryEntity(
    @PrimaryKey
    val url: String,
    val title: String,
    val visitedAt: Long = System.currentTimeMillis()
)
