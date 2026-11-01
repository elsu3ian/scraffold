package net.mangadot.scaffold.data.db.entity

import androidx.room.Entity

@Entity(
    tableName = "script_storage",
    primaryKeys = ["appId", "key"]
)
data class ScriptStorageEntity(
    val appId: String,
    val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)
