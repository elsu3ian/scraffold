package net.mangadot.scaffold.ui.folder

import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.launch
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.ui.container.AppTasks
import net.mangadot.scaffold.ui.container.WebContainerActivity

/** Has no screen. It lets a big icon on the widget resume a running app the same way the shelf does. */
class FolderLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(WebContainerActivity.EXTRA_APP_ID)
        val url = intent.getStringExtra(WebContainerActivity.EXTRA_APP_URL)
        if (id != null && url != null) {
            val dao = AppDatabase.getInstance(this).webAppDao()
            FolderWidgetProvider.scope.launch { dao.recordLaunch(id) }
            AppTasks.open(this, id, url, intent.getStringExtra(WebContainerActivity.EXTRA_APP_NAME).orEmpty())
        }
        finish()
    }
}
