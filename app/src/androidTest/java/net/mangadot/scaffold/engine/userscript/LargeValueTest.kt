package net.mangadot.scaffold.engine.userscript

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.data.repository.ScriptRepository
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** A row over Android's 2 MB cursor limit can't be read back, so large values must not live in the table. */
@RunWith(AndroidJUnit4::class)
class LargeValueTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val repository = ScriptRepository(db.scriptDao(), db.scriptStorageDao())
    private val script = ScriptEntity(id = "large-value-test", name = "Test", code = "", matchPattern = "")
    private val scope = CoroutineScope(Dispatchers.IO)

    @After
    fun cleanUp() {
        runBlocking { ScriptInstaller.delete(context, repository, script.id) }
        db.close()
    }

    /** A new bridge reads from storage, as a fresh page load does. */
    private fun freshBridge(): GmBridge {
        return GmBridge(context, repository, scope).also { it.prepare(TOKEN, script, UserscriptMeta()) }
    }

    private fun args(vararg values: String) = JSONArray(values.toList()).toString()

    @Test
    fun largeValueSurvivesReloadAndDelete() {
        val large = "\"" + "x".repeat(3 * 1024 * 1024) + "\""
        freshBridge().call(TOKEN, "setValue", args("big", large))
        assertEquals(large, freshBridge().call(TOKEN, "getValue", args("big")))

        val keys = JSONArray(freshBridge().call(TOKEN, "listValues", "[]"))
        assertEquals("big", keys.getString(0))

        freshBridge().call(TOKEN, "deleteValue", args("big"))
        assertNull(freshBridge().call(TOKEN, "getValue", args("big")))
        assertFalse(ScriptInstaller.hasValueFiles(context, script.id))
    }

    @Test
    fun overwritingWithSmallValueRemovesTheFile() {
        freshBridge().call(TOKEN, "setValue", args("k", "\"" + "y".repeat(1024 * 1024) + "\""))
        val bridge = freshBridge()
        bridge.call(TOKEN, "setValue", args("k", "\"small\""))
        assertEquals("\"small\"", freshBridge().call(TOKEN, "getValue", args("k")))
        assertFalse(ScriptInstaller.hasValueFiles(context, script.id))
    }

    @Test
    fun writesToOneKeyLandInOrder() {
        val bridge = freshBridge()
        repeat(200) { bridge.call(TOKEN, "setValue", args("n", it.toString())) }
        assertEquals("199", freshBridge().call(TOKEN, "getValue", args("n")))
    }

    @Test
    fun scriptTooLargeToReadBackIsRefused() {
        val code = "// ==UserScript==\n// @name Huge\n// ==/UserScript==\n" + "/".repeat(2 * 1024 * 1024)
        assertThrows(IOException::class.java) {
            runBlocking { ScriptInstaller.save(context, repository, code) }
        }
    }

    private companion object {
        const val TOKEN = "test-token"
    }
}
