package net.mangadot.scaffold.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "userscripts")
data class ScriptEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val code: String,
    val matchPattern: String,
    val runAt: String = RUN_AT_DOCUMENT_START,
    val enabled: Boolean = true,
    val sourceUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val RUN_AT_DOCUMENT_START = "document-start"
        const val RUN_AT_DOCUMENT_END = "document-end"
        const val RUN_AT_DOCUMENT_IDLE = "document-idle"
    }
}
