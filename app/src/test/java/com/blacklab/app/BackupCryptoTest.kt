package com.blacklab.app

import com.blacklab.app.core.BackupCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

class BackupCryptoTest {
    private val key = BackupCrypto.newKey()

    private fun seal(data: ByteArray, k: ByteArray = key, pass: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        val wrap = pass?.let { BackupCrypto.wrapKey(k, it.toCharArray(), iterations = 1_000) }
        BackupCrypto.encrypt(out, k, wrap, "test", 2) { it.write(data) }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, k: ByteArray = key): ByteArray {
        val input = ByteArrayInputStream(sealed)
        val h = BackupCrypto.readHeader(input)
        return ByteArrayOutputStream().also { BackupCrypto.decrypt(input, h, k, it) }.toByteArray()
    }

    @Test fun roundTripsAcrossChunkSizes() {
        for (size in listOf(0, 1, BackupCrypto.CHUNK - 1, BackupCrypto.CHUNK, BackupCrypto.CHUNK + 1, 3 * BackupCrypto.CHUNK + 17)) {
            val data = Random(size).nextBytes(size)
            val sealed = seal(data)
            assertTrue(BackupCrypto.isEncrypted(sealed))
            assertArrayEquals("size $size", data, open(sealed))
        }
    }

    @Test fun headerCarriesMetadataAndNoPlaintext() {
        val data = "journal: very private words".toByteArray()
        val sealed = seal(data)
        val h = BackupCrypto.readHeader(ByteArrayInputStream(sealed))
        assertEquals(BackupCrypto.keyId(key), h.keyId)
        assertEquals("test", h.appVersion)
        assertEquals(2, h.schema)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("private words"))
    }

    @Test fun detectsTampering() {
        val sealed = seal(Random(1).nextBytes(5000))
        for (pos in listOf(sealed.size - 1, sealed.size / 2, 20)) {
            val bad = sealed.copyOf().also { it[pos] = (it[pos].toInt() xor 1).toByte() }
            try { open(bad); fail("tamper at $pos not detected") } catch (_: Exception) { }
        }
    }

    @Test fun detectsTruncationAtChunkBoundary() {
        val sealed = seal(Random(2).nextBytes(2 * BackupCrypto.CHUNK + 5))
        // drop the final chunk: 4-byte length + (5 + 16) bytes of ciphertext
        val cut = sealed.copyOf(sealed.size - (4 + 5 + 16))
        try { open(cut); fail("truncation not detected") } catch (e: IOException) { }
    }

    @Test fun wrongKeyIsRejected() {
        val sealed = seal("x".toByteArray())
        try { open(sealed, BackupCrypto.newKey()); fail() } catch (_: BackupCrypto.WrongPassphrase) { }
    }

    @Test fun passphraseRecoversTheKeyOnAnotherPhone() {
        val sealed = seal("restore me".toByteArray(), pass = "correct horse battery")
        val h = BackupCrypto.readHeader(ByteArrayInputStream(sealed))
        val recovered = BackupCrypto.unwrapKey(h.wrap!!, "correct horse battery".toCharArray())
        assertArrayEquals(key, recovered)
        assertEquals("restore me", String(open(sealed, recovered)))
        try { BackupCrypto.unwrapKey(h.wrap!!, "wrong".toCharArray()); fail() } catch (_: BackupCrypto.WrongPassphrase) { }
    }

    @Test fun plainZipIsNotMistakenForEncrypted() {
        assertFalse(BackupCrypto.isEncrypted(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
    }
}
