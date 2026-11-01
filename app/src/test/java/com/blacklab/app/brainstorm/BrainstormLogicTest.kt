package com.blacklab.app.brainstorm

import com.blacklab.app.data.Migrations
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainstormLogicTest {

    private fun page(vararg items: JSONObject, ink: JSONArray = JSONArray()) =
        JSONObject().put("v", 1).put("title", "Plant walk").put("created", 0L).put("items", JSONArray(items.toList())).put("ink", ink).toString()

    @Test fun extractCollectsTextTagsTranscriptsAndPictureText() {
        val json = page(
            JSONObject().put("t", "text").put("x", 20).put("y", 300).put("html", "<p data-tag=\"todo\" data-done=\"1\">Replace AHU-3 belt</p><p data-tag=\"question\">Who owns the | chiller?</p>"),
            JSONObject().put("t", "text").put("x", 20).put("y", 90).put("html", "<h2>Boiler room</h2><p>Pressure looked normal</p>"),
            JSONObject().put("t", "audio").put("x", 20).put("y", 500).put("transcript", "call the vendor tomorrow"),
            JSONObject().put("t", "image").put("x", 20).put("y", 600).put("src", "asset:a.jpg").put("ocr", "SERIAL 12345"),
        )
        val ex = PageText.extract(json)
        assertEquals("Plant walk", ex.title)
        assertTrue(ex.text.indexOf("Boiler room") < ex.text.indexOf("Replace AHU-3 belt")) // top-to-bottom order
        assertTrue(ex.text.contains("call the vendor tomorrow"))
        assertTrue(ex.text.contains("SERIAL 12345"))
        val tags = Tags.parseIndex(ex.tagIndex)
        assertEquals(2, tags.size)
        assertEquals(Tags.Line("todo", true, "Replace AHU-3 belt"), tags[0])
        assertEquals("Who owns the / chiller?", tags[1].text) // separator is escaped
        assertEquals(setOf("a.jpg"), PageText.assetNames(json))
    }

    @Test fun sectionEncryptionRoundTripsAndRejectsWrongPassword() {
        val salt = PageCrypto.newSalt()
        val key = PageCrypto.deriveKey("correct horse".toCharArray(), salt, rounds = 2_000)
        val data = "{\"title\":\"secret\"}".toByteArray()
        val enc = PageCrypto.encrypt(key, data)
        assertTrue(PageCrypto.isEncrypted(enc))
        assertFalse(PageCrypto.isEncrypted(data))
        assertNotEquals(String(enc), String(data))
        assertEquals(String(data), String(PageCrypto.decrypt(key, enc)))
        val check = PageCrypto.check(key)
        assertTrue(PageCrypto.verify(key, check))
        val wrong = PageCrypto.deriveKey("wrong".toCharArray(), salt, rounds = 2_000)
        assertFalse(PageCrypto.verify(wrong, check))
        assertTrue(runCatching { PageCrypto.decrypt(wrong, enc) }.isFailure)
        // fresh IV every time
        assertNotEquals(PageCrypto.encrypt(key, data).toList(), PageCrypto.encrypt(key, data).toList())
    }

    @Test fun everyTemplateBuildsAValidPage() {
        PageTemplates.all.forEach { t ->
            val m = JSONObject(PageTemplates.build(t.id, "T"))
            assertEquals("T", m.getString("title"))
            assertTrue("${t.id} has items", m.getJSONArray("items").length() > 0)
            assertTrue(m.has("bg") && m.has("ink"))
        }
        val todo = PageText.extract(PageTemplates.build("todo", "Errands"))
        assertEquals(0, Tags.parseIndex(todo.tagIndex).size) // empty To Do lines aren't indexed
        val mind = JSONObject(PageTemplates.mindMap("Energy savings", listOf("LED retrofit", "VFDs", "BAS schedules")))
        assertEquals(4, mind.getJSONArray("items").length())
        assertEquals(3, mind.getJSONArray("ink").length())
    }

    @Test fun inkRendersShapesAndSmoothStrokes() {
        val line = JSONObject().put("shape", "line").put("w", 2).put("pts", JSONArray(listOf(0, 0, 10, 20)))
        assertEquals("M0.0 0.0L10.0 20.0", PageHtml.inkPath(line))
        val rect = JSONObject().put("shape", "rect").put("pts", JSONArray(listOf(1, 2, 3, 4)))
        assertTrue(PageHtml.inkPath(rect).endsWith("Z"))
        val free = JSONObject().put("w", 2).put("pts", JSONArray(listOf(0, 0, 0.5, 5, 5, 0.5, 10, 0, 0.5, 15, 5, 0.5)))
        val d = PageHtml.inkPath(free)
        assertTrue(d.startsWith("M0.0 0.0Q") && d.contains("L15.0 5.0"))
        val m = JSONObject().put("ink", JSONArray().put(line).put(free))
        val b = PageHtml.inkBounds(m)!!
        assertTrue(b.x1 < 0 && b.y2 > 20)
        assertTrue(PageHtml.inkSvg(m, 100.0, 100.0).contains("<path"))
    }

    @Test fun renderingSanitizesAndKeepsTags() {
        val evil = "<p data-tag=\"important\" onclick=\"alert(1)\">Hi<script>alert(2)</script></p><img src=x onerror=alert(3)><a href=\"javascript:alert(4)\">x</a>"
        val clean = PageHtml.sanitize(evil)
        assertFalse(clean.contains("script") || clean.contains("onclick") || clean.contains("onerror") || clean.contains("javascript:"))
        assertTrue(clean.contains("data-tag=\"important\""))
        val txt = PageHtml.tagsAsText("<p data-tag=\"todo\" data-done=\"1\">Done</p><ul class=\"checklist\"><li>Open</li></ul>")
        assertTrue(txt.contains("☑ Done") && txt.contains("☐ Open"))
        val html = PageHtml.static(page(JSONObject().put("t", "text").put("x", 10).put("y", 100).put("w", 200).put("html", "<p>Body</p>")), { "img/$it" })
        assertTrue(html.contains("Plant walk") && html.contains("left:10px;top:100px;width:200px"))
        val linear = PageHtml.linear(page(JSONObject().put("t", "file").put("x", 0).put("y", 0).put("name", "spec.pdf")), { it })
        assertTrue(linear.contains("📎 spec.pdf"))
    }

    @Test fun noteItemsAndLabelsRoundTrip() {
        val items = listOf(NoteItem("Filters", false, 0), NoteItem("MERV 13", true, 1))
        assertEquals(items, NoteOps.parseItems(NoteOps.itemsJson(items)))
        assertEquals(",Work,Home,", NoteOps.joinLabels(listOf("Work", "Home", "Work")))
        assertEquals(listOf("Work", "Home"), NoteOps.splitLabels(",Work,Home,"))
        assertEquals("", NoteOps.joinLabels(emptyList()))
        val att = listOf(NoteAttachment("audio", "Personal/Brainstorm/notes/1/v.m4a", "hello", "", 1200))
        assertEquals(att, NoteOps.parseAttachments(NoteOps.attachmentsJson(att)))
    }

    @Test fun migrationCreatesEveryBrainstormTable() {
        val sql = Migrations.SQL_1_2.joinToString("\n")
        listOf("notebooks", "nb_sections", "nb_pages", "notes", "note_labels", "bs_comments", "bs_shares").forEach { t ->
            assertTrue("creates $t", sql.contains("CREATE TABLE IF NOT EXISTS `$t`"))
        }
        assertTrue(sql.contains("ON DELETE CASCADE"))
    }

    @Test fun liveShareTokensAreLongAndRandom() {
        val a = LiveShare.newToken(); val b = LiveShare.newToken()
        assertTrue(a.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(a, b)
    }
}
