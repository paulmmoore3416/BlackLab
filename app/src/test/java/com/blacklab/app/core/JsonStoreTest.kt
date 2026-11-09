package com.blacklab.app.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsonStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    data class Doc(val items: List<String> = emptyList(), val site: String = "")

    private fun store(version: Int = 2, migrations: Map<Int, (JSONObject) -> JSONObject> = emptyMap()) = JsonStore(
        "Test", version, { Doc() },
        decode = { o -> Doc((0 until (o.optJSONArray("items")?.length() ?: 0)).map { o.getJSONArray("items").getString(it) }, o.optString("site")) },
        encode = { d -> JSONObject().put("items", org.json.JSONArray(d.items)).put("site", d.site) },
        migrations = migrations,
    )

    private fun file() = File(tmp.root, "sub/doc.json")

    @Test fun missingFileStartsEmptyWithoutAProblem() {
        val s = store()
        assertEquals(Doc(), s.load(file()))
        assertNull(s.problem)
    }

    @Test fun writesSchemaVersionAndKeepsTheLastGoodCopy() {
        val f = file()
        val s = store()
        s.update(f) { it.copy(items = listOf("a")) }
        s.update(f) { it.copy(items = it.items + "b") }
        assertEquals(2, JSONObject(f.readText()).getInt(JsonStore.SCHEMA_KEY))
        assertEquals(listOf("a", "b"), store().load(f).items)
        // The backup holds the copy before the last write
        assertEquals("a", JSONObject(JsonStore.backupOf(f).readText()).getJSONArray("items").getString(0))
        assertTrue(tmp.root.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test fun damagedFileIsKeptAsideAndTheBackupIsUsed() {
        val f = file()
        store().apply { update(f) { it.copy(site = "one") }; update(f) { it.copy(site = "two") } }
        f.writeText("{ this is not json")
        val s = store()
        assertEquals("one", s.load(f).site) // from .bak
        assertNotNull(s.problem)
        assertTrue(f.parentFile!!.listFiles()!!.any { it.name.startsWith("doc.unreadable-") })
    }

    @Test fun damagedFileWithoutBackupIsNeverOverwrittenSilently() {
        val f = file(); f.parentFile!!.mkdirs()
        f.writeText("garbage")
        val s = store()
        assertEquals(Doc(), s.load(f))
        assertTrue(s.problem!!.contains("no good copy"))
        val kept = f.parentFile!!.listFiles()!!.single { it.name.startsWith("doc.unreadable-") }
        assertEquals("garbage", kept.readText())
    }

    @Test fun crashBetweenMovesFallsBackToBackup() {
        val f = file()
        store().apply { update(f) { it.copy(site = "saved") }; update(f) { it.copy(site = "newer") } }
        f.delete() // current → .bak happened, temp → current didn't
        assertEquals("saved", store().load(f).site)
    }

    @Test fun olderFilesAreMigratedStepByStep() {
        val f = file(); f.parentFile!!.mkdirs()
        f.writeText("""{"names":["x","y"]}""") // version 1: no _schema, field was called "names"
        val s = store(version = 3, migrations = mapOf(
            1 to { o -> o.put("items", o.getJSONArray("names")).also { it.remove("names") } },
            2 to { o -> o.put("site", "migrated") },
        ))
        assertEquals(Doc(listOf("x", "y"), "migrated"), s.load(f))
        assertNull(s.problem)
        s.writeNow(f)
        assertEquals(3, JSONObject(f.readText()).getInt(JsonStore.SCHEMA_KEY))
    }

    @Test fun fileFromANewerVersionIsReadButNeverWritten() {
        val f = file(); f.parentFile!!.mkdirs()
        val original = """{"_schema":9,"items":["keep"],"futureField":true}"""
        f.writeText(original)
        val s = store(version = 2)
        assertEquals(listOf("keep"), s.load(f).items)
        assertTrue(s.readOnly)
        s.update(f) { it.copy(items = emptyList()) }
        assertEquals(emptyList<String>(), s.value.items) // in memory
        assertEquals(original, f.readText()) // untouched on disk
        assertFalse(s.writeNow(f))
    }

    @Test fun quarantineKeepsOnlyTheThreeNewest() {
        val dir = File(tmp.root, "q").apply { mkdirs() }
        repeat(5) { i -> File(dir, "doc.unreadable-${1000 + i}.json").writeText("x") }
        File(dir, "doc.json").writeText("bad")
        JsonStore.quarantine(File(dir, "doc.json"))
        assertEquals(3, dir.listFiles()!!.count { it.name.startsWith("doc.unreadable-") })
    }
}
