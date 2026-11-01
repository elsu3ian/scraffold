package net.mangadot.scaffold.data.repository

import kotlinx.coroutines.flow.Flow
import net.mangadot.scaffold.data.db.dao.WebAppDao
import net.mangadot.scaffold.data.db.entity.WebAppEntity

class WebAppRepository(private val webAppDao: WebAppDao) {

    val allWebApps: Flow<List<WebAppEntity>> = webAppDao.getAllWebApps()

    fun getWebAppById(id: String): Flow<WebAppEntity?> = webAppDao.getWebAppById(id)

    suspend fun getWebAppByUrl(url: String): WebAppEntity? = webAppDao.getWebAppByUrl(url)

    suspend fun insertWebApp(webApp: WebAppEntity) = webAppDao.insertWebApp(webApp)

    suspend fun updateWebApp(webApp: WebAppEntity) = webAppDao.updateWebApp(webApp)

    suspend fun deleteWebAppById(id: String) = webAppDao.deleteWebAppById(id)

    suspend fun recordLaunch(id: String) = webAppDao.recordLaunch(id)

    // Reactive Instant Settings Persistence
    suspend fun updateDesktopMode(id: String, enabled: Boolean) = webAppDao.updateDesktopMode(id, enabled)

    suspend fun updateDataSaver(id: String, enabled: Boolean) = webAppDao.updateDataSaver(id, enabled)

    suspend fun updateAllowCamera(id: String, enabled: Boolean) = webAppDao.updateAllowCamera(id, enabled)

    suspend fun updateAllowMic(id: String, enabled: Boolean) = webAppDao.updateAllowMic(id, enabled)

    suspend fun updateOpenExternalLinks(id: String, enabled: Boolean) = webAppDao.updateOpenExternalLinks(id, enabled)

    suspend fun updateFullScreenMode(id: String, enabled: Boolean) = webAppDao.updateFullScreenMode(id, enabled)
}
