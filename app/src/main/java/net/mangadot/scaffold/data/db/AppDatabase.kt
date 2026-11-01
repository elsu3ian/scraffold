package net.mangadot.scaffold.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun webAppDao(): WebAppDao
    abstract fun scriptDao(): ScriptDao
    abstract fun scriptStorageDao(): ScriptStorageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "scraffold.db"
                ).fallbackToDestructiveMigration()
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
