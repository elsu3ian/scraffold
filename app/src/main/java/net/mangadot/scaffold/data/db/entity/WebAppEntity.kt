package net.mangadot.scaffold.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "web_apps")
data class WebAppEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val iconUrl: String? = null,
    val iconColor: String? = null,
    val desktopMode: Boolean = false,
    val dataSaver: Boolean = false,
    val allowCamera: Boolean = false,
    val allowMic: Boolean = false,
    val openExternalLinks: Boolean = true,
    val fullScreenMode: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastLaunchedAt: Long = 0L,
    val launchCount: Int = 0
)
