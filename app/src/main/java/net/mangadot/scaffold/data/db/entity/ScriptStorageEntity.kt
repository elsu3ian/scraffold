package net.mangadot.scaffold.data.db.entity

import androidx.room.Entity

/** One script's GM_setValue entry. */
@Entity(
    tableName = "script_storage",
    primaryKeys = ["scriptId", "key"]
)
data class ScriptStorageEntity(
    val scriptId: String,
    val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)
