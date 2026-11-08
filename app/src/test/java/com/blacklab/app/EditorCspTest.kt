package com.blacklab.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** The editor's CSP pins its inline script by hash: editing the script means updating the hash. */
class EditorCspTest {
    @Test fun cspHashMatchesTheEditorScript() {
        val f = listOf("src/main/assets/editor/editor.html", "app/src/main/assets/editor/editor.html").map(::File).first { it.exists() }
        val html = f.readText()
        val script = html.substringAfter("<script>").substringBefore("</script>")
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(script.toByteArray()))
        assertTrue("Update the sha256 in editor.html's Content-Security-Policy to 'sha256-$hash'", html.contains("'sha256-$hash'"))
        assertTrue(html.contains("default-src 'none'"))
        assertTrue("only one inline script is allowed", html.split("<script").size == 2)
    }
}
