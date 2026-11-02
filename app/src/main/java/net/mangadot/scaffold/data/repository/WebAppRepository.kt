package net.mangadot.scaffold.data.repository

import kotlinx.coroutines.flow.Flow
import net.mangadot.scaffold.data.db.dao.WebAppDao
import net.mangadot.scaffold.data.db.entity.WebAppEntity

class WebAppRepository(private val webAppDao: WebAppDao) {

    val allWebApps: Flow<List<WebAppEntity>> = webAppDao.getAllWebApps()

    fun getWebAppById(id: String): Flow<WebAppEntity?> = webAppDao.getWebAppById(id)

    suspend fun insertWebApp(webApp: WebAppEntity) = webAppDao.insertWebApp(webApp)

    suspend fun updateWebApp(webApp: WebAppEntity) = webAppDao.updateWebApp(webApp)

    suspend fun deleteWebAppById(id: String) = webAppDao.deleteWebAppById(id)

    suspend fun recordLaunch(id: String) = webAppDao.recordLaunch(id)

    suspend fun updateIconUrl(id: String, iconUrl: String) = webAppDao.updateIconUrl(id, iconUrl)

    suspend fun saveShelfOrder(ids: List<String>) = webAppDao.saveShelfOrder(ids)
}
