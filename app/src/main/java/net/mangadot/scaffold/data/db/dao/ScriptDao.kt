package net.mangadot.scaffold.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import net.mangadot.scaffold.data.db.entity.ScriptEntity

@Dao
interface ScriptDao {

    @Query("SELECT * FROM userscripts ORDER BY updatedAt DESC")
    fun getAllScripts(): Flow<List<ScriptEntity>>

    @Query("SELECT * FROM userscripts WHERE enabled = 1")
    fun getEnabledScripts(): Flow<List<ScriptEntity>>

    @Query("SELECT * FROM userscripts WHERE enabled = 1")
    suspend fun getEnabledScriptsList(): List<ScriptEntity>

    @Query("SELECT * FROM userscripts WHERE id = :id LIMIT 1")
    fun getScriptById(id: String): Flow<ScriptEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScript(script: ScriptEntity)

    @Update
    suspend fun updateScript(script: ScriptEntity)

    @Delete
    suspend fun deleteScript(script: ScriptEntity)

    @Query("DELETE FROM userscripts WHERE id = :id")
    suspend fun deleteScriptById(id: String)

    @Query("UPDATE userscripts SET enabled = :enabled, updatedAt = :timestamp WHERE id = :id")
    suspend fun setScriptEnabled(id: String, enabled: Boolean, timestamp: Long = System.currentTimeMillis())
}
