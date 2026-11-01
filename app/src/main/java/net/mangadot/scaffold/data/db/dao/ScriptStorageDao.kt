package net.mangadot.scaffold.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.mangadot.scaffold.data.db.entity.ScriptStorageEntity

@Dao
interface ScriptStorageDao {

    @Query("SELECT value FROM script_storage WHERE appId = :appId AND `key` = :key LIMIT 1")
    suspend fun getValue(appId: String, key: String): String?

    @Query("SELECT * FROM script_storage WHERE appId = :appId")
    suspend fun getAllForApp(appId: String): List<ScriptStorageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setValue(entry: ScriptStorageEntity)

    @Query("DELETE FROM script_storage WHERE appId = :appId AND `key` = :key")
    suspend fun deleteValue(appId: String, key: String)

    @Query("DELETE FROM script_storage WHERE appId = :appId")
    suspend fun clearAppStorage(appId: String)
}
