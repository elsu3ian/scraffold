package net.mangadot.scaffold.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.mangadot.scaffold.data.db.dao.BrowserHistoryDao
import net.mangadot.scaffold.data.db.dao.DownloadDao
import net.mangadot.scaffold.data.db.dao.ScriptDao
import net.mangadot.scaffold.data.db.dao.ScriptStorageDao
import net.mangadot.scaffold.data.db.dao.WebAppDao
import net.mangadot.scaffold.data.db.entity.BrowserHistoryEntity
import net.mangadot.scaffold.data.db.entity.DownloadEntity
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.data.db.entity.ScriptStorageEntity
import net.mangadot.scaffold.data.db.entity.WebAppEntity

@Database(
    entities = [
        WebAppEntity::class,
        ScriptEntity::class,
        ScriptStorageEntity::class,
        BrowserHistoryEntity::class,
        DownloadEntity::class
    ],
    version = 9,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun webAppDao(): WebAppDao
    abstract fun scriptDao(): ScriptDao
    abstract fun scriptStorageDao(): ScriptStorageDao
    abstract fun browserHistoryDao(): BrowserHistoryDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        /** Adds the Web App Settings columns and keeps the user's shelf. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE userscripts ADD COLUMN sourceUrl TEXT")
                listOf(
                    "overrideGlobal" to 0, "allowJavascript" to 1, "blockThirdParty" to 0, "allowHttp" to 0,
                    "allowLocation" to 0, "allowDrm" to 0, "acceptCookies" to 1, "thirdPartyCookies" to 0,
                    "adblockEnabled" to 1, "pinchZoom" to 1
                ).forEach { (column, default) ->
                    db.execSQL("ALTER TABLE web_apps ADD COLUMN $column INTEGER NOT NULL DEFAULT $default")
                }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE web_apps ADD COLUMN userscriptsEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** Renames appId to scriptId. A rebuild, because SQLite only gained RENAME COLUMN on Android 11. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE script_storage_new (scriptId TEXT NOT NULL, `key` TEXT NOT NULL, " +
                        "value TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(scriptId, `key`))"
                )
                db.execSQL("INSERT INTO script_storage_new SELECT appId, `key`, value, updatedAt FROM script_storage")
                db.execSQL("DROP TABLE script_storage")
                db.execSQL("ALTER TABLE script_storage_new RENAME TO script_storage")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE web_apps ADD COLUMN shelfOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS browser_history (url TEXT NOT NULL, title TEXT NOT NULL, " +
                        "visitedAt INTEGER NOT NULL, PRIMARY KEY(url))"
                )
            }
        }

        /** What the site's manifest says about the app. Existing apps keep every column and get nulls here. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("startUrl", "scope", "display", "themeColor", "backgroundColor").forEach { column ->
                    db.execSQL("ALTER TABLE web_apps ADD COLUMN $column TEXT")
                }
            }
        }

        /** The Downloads page's list. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `downloads` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `site` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `systemId` INTEGER NOT NULL, `fileUri` TEXT, " +
                        "`size` INTEGER NOT NULL, `written` INTEGER NOT NULL, `state` INTEGER NOT NULL)"
                )
            }
        }

        /** The app a download came from, so trying it again signs in the same way. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN appId TEXT")
            }
        }

        internal val MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
            MIGRATION_8_9
        )

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "scraffold.db"
                ).addMigrations(*MIGRATIONS)
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
