package net.mangadot.scaffold.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import net.mangadot.scaffold.data.db.entity.WebAppEntity

@Dao
interface WebAppDao {

    @Query("SELECT * FROM web_apps ORDER BY lastLaunchedAt DESC, createdAt DESC")
    fun getAllWebApps(): Flow<List<WebAppEntity>>

    @Query("SELECT * FROM web_apps")
    suspend fun getAll(): List<WebAppEntity>

    @Query("SELECT * FROM web_apps WHERE id = :id LIMIT 1")
    fun getWebAppById(id: String): Flow<WebAppEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWebApp(webApp: WebAppEntity)

    @Update
    suspend fun updateWebApp(webApp: WebAppEntity)

    @Query("DELETE FROM web_apps WHERE id = :id")
    suspend fun deleteWebAppById(id: String)

    @Query("UPDATE web_apps SET launchCount = launchCount + 1, lastLaunchedAt = :timestamp WHERE id = :id")
    suspend fun recordLaunch(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE web_apps SET iconUrl = :iconUrl WHERE id = :id")
    suspend fun updateIconUrl(id: String, iconUrl: String)

    @Query("UPDATE web_apps SET shelfOrder = :order WHERE id = :id")
    suspend fun updateShelfOrder(id: String, order: Int)

    @Transaction
    suspend fun saveShelfOrder(ids: List<String>) {
        ids.forEachIndexed { index, id -> updateShelfOrder(id, index + 1) }
    }
}
