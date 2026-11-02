package net.mangadot.scaffold.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.mangadot.scaffold.data.db.entity.ScriptStorageEntity

@Dao
interface ScriptStorageDao {

    @Query("SELECT * FROM script_storage")
    suspend fun getAll(): List<ScriptStorageEntity>

    @Query("SELECT * FROM script_storage WHERE scriptId = :scriptId")
    suspend fun getAllForScript(scriptId: String): List<ScriptStorageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setValue(entry: ScriptStorageEntity)

    @Query("DELETE FROM script_storage WHERE scriptId = :scriptId AND `key` = :key")
    suspend fun deleteValue(scriptId: String, key: String)

    @Query("DELETE FROM script_storage WHERE scriptId = :scriptId")
    suspend fun clearScript(scriptId: String)
}
