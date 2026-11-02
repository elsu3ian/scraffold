package net.mangadot.scaffold.data.repository

import kotlinx.coroutines.flow.Flow
import net.mangadot.scaffold.data.db.dao.ScriptDao
import net.mangadot.scaffold.data.db.dao.ScriptStorageDao
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.data.db.entity.ScriptStorageEntity

class ScriptRepository(
    private val scriptDao: ScriptDao,
    private val scriptStorageDao: ScriptStorageDao
) {

    val allScripts: Flow<List<ScriptEntity>> = scriptDao.getAllScripts()

    suspend fun getEnabledScriptsList(): List<ScriptEntity> = scriptDao.getEnabledScriptsList()

    fun getScriptById(id: String): Flow<ScriptEntity?> = scriptDao.getScriptById(id)

    suspend fun insertScript(script: ScriptEntity) = scriptDao.insertScript(script)

    suspend fun deleteScriptById(id: String) = scriptDao.deleteScriptById(id)

    suspend fun setScriptEnabled(id: String, enabled: Boolean) = scriptDao.setScriptEnabled(id, enabled)

    // GM_getValue / GM_setValue bridge storage
    suspend fun getAllStorageValues(scriptId: String): List<ScriptStorageEntity> = scriptStorageDao.getAllForScript(scriptId)

    suspend fun setStorageValue(scriptId: String, key: String, value: String) {
        scriptStorageDao.setValue(ScriptStorageEntity(scriptId = scriptId, key = key, value = value))
    }

    suspend fun deleteStorageValue(scriptId: String, key: String) = scriptStorageDao.deleteValue(scriptId, key)

    suspend fun clearScriptStorage(scriptId: String) = scriptStorageDao.clearScript(scriptId)
}
