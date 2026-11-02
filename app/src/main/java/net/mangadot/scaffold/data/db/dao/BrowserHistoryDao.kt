package net.mangadot.scaffold.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.mangadot.scaffold.data.db.entity.BrowserHistoryEntity

@Dao
interface BrowserHistoryDao {

    @Query("SELECT * FROM browser_history ORDER BY visitedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<BrowserHistoryEntity>>

    @Query("SELECT * FROM browser_history")
    suspend fun getAll(): List<BrowserHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(visit: BrowserHistoryEntity)

    @Query("UPDATE browser_history SET title = :title WHERE url = :url")
    suspend fun updateTitle(url: String, title: String)

    @Query("DELETE FROM browser_history WHERE url NOT IN (SELECT url FROM browser_history ORDER BY visitedAt DESC LIMIT :keep)")
    suspend fun keepRecent(keep: Int)

    @Query("DELETE FROM browser_history WHERE url = :url")
    suspend fun delete(url: String)

    @Query("DELETE FROM browser_history")
    suspend fun clear()
}
