package net.mangadot.scaffold.data.backup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.BrowserHistoryEntity
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.data.db.entity.ScriptStorageEntity
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.engine.userscript.ScriptInstaller
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/** Runs against the app's real database, so it only touches its own ids and removes them after. */
@RunWith(AndroidJUnit4::class)
class BackupManagerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = AppDatabase.getInstance(context)
    private val repository = ScriptRepository(db.scriptDao(), db.scriptStorageDao())
    private val manager = BackupManager(context)
    private val file = File(context.cacheDir, "backup-test.json")

    @After
    fun cleanUp() {
        runBlocking {
            db.webAppDao().deleteWebAppById(APP_ID)
            ScriptInstaller.delete(context, repository, SCRIPT_ID)
        }
        file.delete()
    }

    @Test
    fun restoresWhatWasExported() = runBlocking {
        db.webAppDao().insertWebApp(WebAppEntity(id = APP_ID, name = "Backup Test", url = "https://example.com"))
        db.scriptDao().insertScript(ScriptEntity(id = SCRIPT_ID, name = "Backup Test", code = "// code", matchPattern = ""))
        ScriptInstaller.storeValue(context, repository, SCRIPT_ID, "small", "\"s\"")
        val large = "\"" + "x".repeat(3 * 1024 * 1024) + "\""
        ScriptInstaller.storeValue(context, repository, SCRIPT_ID, "large", large)

        manager.export(Uri.fromFile(file))
        db.webAppDao().deleteWebAppById(APP_ID)
        ScriptInstaller.delete(context, repository, SCRIPT_ID)

        manager.restore(manager.read(Uri.fromFile(file)))

        assertEquals("Backup Test", db.webAppDao().getAll().single { it.id == APP_ID }.name)
        assertEquals("// code", db.scriptDao().getAll().single { it.id == SCRIPT_ID }.code)
        val values = repository.getAllStorageValues(SCRIPT_ID).associate {
            it.key to ScriptInstaller.restoreValue(context, SCRIPT_ID, it.key, it.value)
        }
        assertEquals("\"s\"", values["small"])
        assertEquals(large, values["large"])
    }

    @Test
    fun aHostileBackupCanNotWriteOutsideItsFolders() = runBlocking {
        val outside = File(context.filesDir, "escaped-by-backup")
        val backup = BackupManager.BackupFile(
            scripts = listOf(
                ScriptEntity(id = "../../escaped", name = "Bad", code = "", matchPattern = ""),
                ScriptEntity(id = SCRIPT_ID, name = "Good", code = "", matchPattern = "")
            ),
            values = listOf(ScriptStorageEntity(scriptId = "other-script", key = "k", value = "v")),
            scriptFiles = mapOf(SCRIPT_ID to mapOf("../../../escaped-by-backup" to "AA=="))
        )

        manager.restore(backup)

        assertFalse(outside.exists())
        assertEquals(listOf("Good"), db.scriptDao().getAll().filter { it.name in listOf("Bad", "Good") }.map { it.name })
        assertTrue(repository.getAllStorageValues("other-script").isEmpty())
    }

    @Test
    fun browserHistoryIsRestoredWithoutScriptAddresses() = runBlocking {
        val backup = BackupManager.BackupFile(
            browserHistory = listOf(
                BrowserHistoryEntity(HISTORY_URL, "Page"),
                BrowserHistoryEntity(HOSTILE_URL, "Bad")
            )
        )

        manager.restore(backup)

        val urls = db.browserHistoryDao().getAll().map { it.url }
        assertTrue(HISTORY_URL in urls)
        assertFalse(HOSTILE_URL in urls)
        db.browserHistoryDao().clear()
    }

    @Test
    fun refusesOtherFiles() {
        file.writeText("{\"hello\":\"world\"}")
        assertThrows(IOException::class.java) { runBlocking { manager.read(Uri.fromFile(file)) } }
        file.writeText("not json")
        assertThrows(IOException::class.java) { runBlocking { manager.read(Uri.fromFile(file)) } }
    }

    private companion object {
        const val APP_ID = "backup-test-app"
        const val SCRIPT_ID = "backup-test-script"
        const val HISTORY_URL = "https://example.com/backup-test"
        const val HOSTILE_URL = "javascript:alert(1)"
    }
}
