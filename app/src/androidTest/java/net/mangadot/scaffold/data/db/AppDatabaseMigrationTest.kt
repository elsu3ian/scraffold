package net.mangadot.scaffold.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every released schema must reach the current one with its data intact. */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @After
    fun cleanUp() {
        context.deleteDatabase(TEST_DB)
    }

    /** Written by hand because v1 predates schema export. Matches the entities as v1 shipped them. */
    private fun createVersion1() {
        val file = context.getDatabasePath(TEST_DB)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE web_apps (id TEXT NOT NULL, name TEXT NOT NULL, url TEXT NOT NULL, iconUrl TEXT, " +
                    "iconColor TEXT, desktopMode INTEGER NOT NULL, dataSaver INTEGER NOT NULL, " +
                    "allowCamera INTEGER NOT NULL, allowMic INTEGER NOT NULL, openExternalLinks INTEGER NOT NULL, " +
                    "fullScreenMode INTEGER NOT NULL, createdAt INTEGER NOT NULL, lastLaunchedAt INTEGER NOT NULL, " +
                    "launchCount INTEGER NOT NULL, PRIMARY KEY(id))"
            )
            db.execSQL(
                "CREATE TABLE userscripts (id TEXT NOT NULL, name TEXT NOT NULL, code TEXT NOT NULL, " +
                    "matchPattern TEXT NOT NULL, runAt TEXT NOT NULL, enabled INTEGER NOT NULL, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))"
            )
            db.execSQL(
                "CREATE TABLE script_storage (appId TEXT NOT NULL, `key` TEXT NOT NULL, value TEXT NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, PRIMARY KEY(appId, `key`))"
            )
            db.execSQL("INSERT INTO web_apps VALUES ('app1', 'GitHub', 'https://github.com', NULL, NULL, 1, 0, 0, 0, 1, 1, 10, 20, 3)")
            db.execSQL("INSERT INTO userscripts VALUES ('s1', 'Script', '// code', 'https://*/*', 'document-start', 0, 10, 20)")
            db.execSQL("INSERT INTO script_storage VALUES ('s1', 'k', '\"v\"', 10)")
            db.version = 1
        }
    }

    @Test
    fun version1MigratesToCurrentAndKeepsData() {
        createVersion1()

        // Throws if the migrated tables differ from the exported current schema
        val db = helper.runMigrationsAndValidate(TEST_DB, CURRENT_VERSION, true, *AppDatabase.MIGRATIONS)

        db.query("SELECT name, url, desktopMode, launchCount, overrideGlobal, allowJavascript, userscriptsEnabled FROM web_apps").use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("GitHub", it.getString(0))
            assertEquals("https://github.com", it.getString(1))
            assertEquals(1, it.getInt(2))
            assertEquals(3, it.getInt(3))
            assertEquals(0, it.getInt(4))
            assertEquals(1, it.getInt(5))
            assertEquals(1, it.getInt(6))
        }
        db.query("SELECT code, enabled, sourceUrl FROM userscripts").use {
            it.moveToFirst()
            assertEquals("// code", it.getString(0))
            assertEquals(0, it.getInt(1))
            assertEquals(true, it.isNull(2))
        }
        db.query("SELECT value FROM script_storage").use {
            it.moveToFirst()
            assertEquals("\"v\"", it.getString(0))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
        const val CURRENT_VERSION = 3
    }
}
