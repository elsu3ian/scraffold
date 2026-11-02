package net.mangadot.scaffold.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.mangadot.scaffold.data.db.dao.ScriptDao
import net.mangadot.scaffold.data.db.dao.ScriptStorageDao
import net.mangadot.scaffold.data.db.dao.WebAppDao
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.data.db.entity.ScriptStorageEntity
import net.mangadot.scaffold.data.db.entity.WebAppEntity

@Database(
    entities = [
        WebAppEntity::class,
        ScriptEntity::class,
        ScriptStorageEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun webAppDao(): WebAppDao
    abstract fun scriptDao(): ScriptDao
    abstract fun scriptStorageDao(): ScriptStorageDao

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

        internal val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

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
