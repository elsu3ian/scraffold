package net.mangadot.scaffold.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import net.mangadot.scaffold.data.db.entity.WebAppEntity

@Dao
interface WebAppDao {

    @Query("SELECT * FROM web_apps ORDER BY lastLaunchedAt DESC, createdAt DESC")
    fun getAllWebApps(): Flow<List<WebAppEntity>>

    @Query("SELECT * FROM web_apps WHERE id = :id LIMIT 1")
    fun getWebAppById(id: String): Flow<WebAppEntity?>

    @Query("SELECT * FROM web_apps WHERE url = :url LIMIT 1")
    suspend fun getWebAppByUrl(url: String): WebAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWebApp(webApp: WebAppEntity)

    @Update
    suspend fun updateWebApp(webApp: WebAppEntity)

    @Delete
    suspend fun deleteWebApp(webApp: WebAppEntity)

    @Query("DELETE FROM web_apps WHERE id = :id")
    suspend fun deleteWebAppById(id: String)

    @Query("UPDATE web_apps SET launchCount = launchCount + 1, lastLaunchedAt = :timestamp WHERE id = :id")
    suspend fun recordLaunch(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE web_apps SET iconUrl = :iconUrl WHERE id = :id")
    suspend fun updateIconUrl(id: String, iconUrl: String)

    // Instant Settings Updates
    @Query("UPDATE web_apps SET desktopMode = :enabled WHERE id = :id")
    suspend fun updateDesktopMode(id: String, enabled: Boolean)

    @Query("UPDATE web_apps SET dataSaver = :enabled WHERE id = :id")
    suspend fun updateDataSaver(id: String, enabled: Boolean)

    @Query("UPDATE web_apps SET allowCamera = :enabled WHERE id = :id")
    suspend fun updateAllowCamera(id: String, enabled: Boolean)

    @Query("UPDATE web_apps SET allowMic = :enabled WHERE id = :id")
    suspend fun updateAllowMic(id: String, enabled: Boolean)

    @Query("UPDATE web_apps SET openExternalLinks = :enabled WHERE id = :id")
    suspend fun updateOpenExternalLinks(id: String, enabled: Boolean)

    @Query("UPDATE web_apps SET fullScreenMode = :enabled WHERE id = :id")
    suspend fun updateFullScreenMode(id: String, enabled: Boolean)
}
