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
    val enabledScripts: Flow<List<ScriptEntity>> = scriptDao.getEnabledScripts()

    suspend fun getEnabledScriptsList(): List<ScriptEntity> = scriptDao.getEnabledScriptsList()

    fun getScriptById(id: String): Flow<ScriptEntity?> = scriptDao.getScriptById(id)

    suspend fun insertScript(script: ScriptEntity) = scriptDao.insertScript(script)

    suspend fun updateScript(script: ScriptEntity) = scriptDao.updateScript(script)

    suspend fun deleteScriptById(id: String) = scriptDao.deleteScriptById(id)

    suspend fun setScriptEnabled(id: String, enabled: Boolean) = scriptDao.setScriptEnabled(id, enabled)

    // GM_getValue / GM_setValue bridge storage, namespaced by script id
    suspend fun getAllStorageValues(appId: String): List<ScriptStorageEntity> = scriptStorageDao.getAllForApp(appId)

    suspend fun getStorageValue(appId: String, key: String): String? = scriptStorageDao.getValue(appId, key)

    suspend fun setStorageValue(appId: String, key: String, value: String) {
        scriptStorageDao.setValue(ScriptStorageEntity(appId = appId, key = key, value = value))
    }

    suspend fun deleteStorageValue(appId: String, key: String) = scriptStorageDao.deleteValue(appId, key)

    suspend fun clearAppStorage(appId: String) = scriptStorageDao.clearAppStorage(appId)
}
