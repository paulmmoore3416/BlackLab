package com.blacklab.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blacklab.app.brainstorm.Brainstorm
import com.blacklab.app.brainstorm.PageText
import com.blacklab.app.core.Profile
import com.blacklab.app.docs.ExportFormat
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Pictures on pages, every page export format, and a full notebook export → import round trip. */
@RunWith(AndroidJUnit4::class)
class BrainstormDeviceTest {

    @Test fun photoInsertExportAndNotebookRoundTrip() = runBlocking {
        val app = DeviceTest.app
        val dao = app.db.brainstorm()
        val name = DeviceTest.tag()
        val nbId = Brainstorm.createNotebook(app, Profile.PERSONAL, name, 0)
        val created = mutableListOf(nbId)
        val files = mutableListOf<File>()
        try {
            val secId = Brainstorm.createSection(app, nbId, "Plant walk", 0)
            val pageId = Brainstorm.createPage(app, Profile.PERSONAL, secId, "blank", "Boiler room")
            val page = dao.page(pageId)!!

            // Insert a photo the way the canvas does: an asset file plus an image item
            val ref = Brainstorm.addAsset(app, page, "png") { f ->
                val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(43, 217, 168)) }
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            assertTrue(ref.startsWith("asset:"))
            val model = JSONObject(Brainstorm.read(app, page))
            model.put("items", JSONArray()
                .put(JSONObject().put("id", "t1").put("t", "text").put("x", 40).put("y", 90).put("w", 400)
                    .put("html", "<h2>Boiler room</h2><p data-tag=\"todo\">Check pressure relief valve</p>"))
                .put(JSONObject().put("id", "i1").put("t", "image").put("x", 40).put("y", 220).put("w", 64).put("h", 48).put("src", ref)))
            Brainstorm.save(app, pageId, model.toString())
            val saved = dao.page(pageId)!!
            assertTrue(saved.searchText.contains("Check pressure relief valve"))
            assertEquals(setOf(ref.removePrefix("asset:")), PageText.assetNames(Brainstorm.read(app, saved)))

            // Every export format produces a non-empty file (PDF is the same renderer printing uses)
            ExportFormat.entries.forEach { fmt ->
                val out = Brainstorm.export(app, saved, fmt)
                files += out
                assertTrue("$fmt export is empty", out.length() > 0)
            }
            val html = files.first { it.extension == "html" }.readText()
            assertTrue("picture is inlined in HTML", html.contains("data:image/png;base64,"))
            assertTrue(files.first { it.extension == "pdf" }.readBytes().copyOfRange(0, 4).contentEquals("%PDF".toByteArray()))

            // Notebook package round trip
            val pkg = Brainstorm.exportNotebook(app, dao.notebook(nbId)!!).also { files += it }
            val importedId = Brainstorm.importNotebook(app, Profile.PERSONAL, Uri.fromFile(pkg))
            created += importedId
            val sections = dao.sections(importedId)
            assertEquals("every section comes back, in order", dao.sections(nbId).map { it.name }, sections.map { it.name })
            val pages = dao.pages(sections.first { it.name == "Plant walk" }.id)
            assertEquals("Boiler room", pages.single().title)
            assertTrue(pages.single().searchText.contains("Check pressure relief valve"))
            assertNotNull("picture came along", Brainstorm.assetBytes(app, pages.single(), ref, null))
        } finally {
            files.forEach { it.delete() }
            created.forEach { id ->
                dao.notebook(id)?.let { nb ->
                    val pages = dao.sections(id).flatMap { dao.allPagesIn(it.id) }
                    Brainstorm.deleteNotebook(app, nb)
                    pages.forEach { p -> dao.page(p.id)?.let { Brainstorm.purgePage(app, it) } }
                }
            }
        }
    }
}
