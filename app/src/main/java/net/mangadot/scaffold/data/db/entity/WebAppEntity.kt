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
    val overrideGlobal: Boolean = false,
    val allowJavascript: Boolean = true,
    val blockThirdParty: Boolean = false,
    val allowHttp: Boolean = false,
    val allowLocation: Boolean = false,
    val allowDrm: Boolean = false,
    val acceptCookies: Boolean = true,
    val thirdPartyCookies: Boolean = false,
    val adblockEnabled: Boolean = false,
    val pinchZoom: Boolean = true,
    val userscriptsEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastLaunchedAt: Long = 0L,
    val launchCount: Int = 0,
    // Place in the Custom sort, from 1; 0 means not placed yet
    val shelfOrder: Int = 0,
    // From the site's web app manifest when it was added; null for sites without one
    val startUrl: String? = null,
    val scope: String? = null,
    val display: String? = null,
    val themeColor: String? = null,
    val backgroundColor: String? = null
)
