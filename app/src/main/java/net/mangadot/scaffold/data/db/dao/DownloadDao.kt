package net.mangadot.scaffold.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.mangadot.scaffold.data.db.entity.DownloadEntity

/** The non-suspending calls are for the page's own thread, which isn't the main one and can wait. */
@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun all(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads")
    suspend fun getAll(): List<DownloadEntity>

    @Insert
    fun insert(download: DownloadEntity): Long

    @Query("UPDATE downloads SET written = :written WHERE id = :id")
    fun progress(id: Long, written: Long)

    @Query("UPDATE downloads SET state = :state, size = :size, fileUri = :fileUri, name = :name WHERE id = :id")
    fun finish(id: Long, state: Int, size: Long, fileUri: String?, name: String)

    @Query("UPDATE downloads SET state = :state, size = :size, name = :name, fileUri = :fileUri WHERE id = :id")
    suspend fun settle(id: Long, state: Int, size: Long, name: String, fileUri: String?)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}
