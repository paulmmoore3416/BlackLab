package com.blacklab.app.core

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encrypted backup format (`.blbackup`). Pure JVM so it is unit-tested off the phone.
 *
 * ```
 * "BLBK" | version (1 byte) | header length (int) | header JSON | chunks…
 * chunk  = length (int) | AES-256-GCM(ciphertext + tag)
 * ```
 * The backup zip is encrypted with the install's random *data key* in 1 MiB chunks. Each chunk's nonce
 * is the header's 8-byte prefix plus the chunk counter, and its AAD is the header plus the counter and a
 * final-chunk flag, so chunks can't be reordered, dropped, truncated or moved between backups.
 *
 * The header carries the data key's id (first 8 bytes of its SHA-256) and, once a backup passphrase is set,
 * the data key wrapped with a PBKDF2 key from that passphrase. On this phone the data key comes from the
 * Keystore; on a new phone (or after a reinstall) the passphrase unwraps it.
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION = 1
    const val CHUNK = 1 shl 20
    const val PBKDF2_ITERATIONS = 310_000
    private val rnd = SecureRandom()
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    class WrongPassphrase : IOException("Wrong backup passphrase")
    class NeedsPassphrase : IOException("This backup was made on another phone or install — enter its backup passphrase")
    class NotRestorable : IOException("This backup was made before a passphrase was set, so it can only be restored where it was made")

    data class Header(val keyId: String, val created: Long, val appVersion: String, val schema: Int, val nonce: ByteArray, val wrap: JSONObject?, val raw: ByteArray)

    fun newKey(): ByteArray = ByteArray(32).also(rnd::nextBytes)

    fun keyId(key: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(key).copyOf(8).joinToString("") { "%02x".format(it) }

    /** Wraps [dataKey] with a key derived from [passphrase]; the result is stored once and copied into every backup header. */
    fun wrapKey(dataKey: ByteArray, passphrase: CharArray, iterations: Int = PBKDF2_ITERATIONS): JSONObject {
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive(passphrase, salt, iterations), "AES"), GCMParameterSpec(128, iv))
        c.updateAAD("blacklab-backup-key".toByteArray())
        return JSONObject().put("kdf", "PBKDF2WithHmacSHA256").put("iter", iterations)
            .put("salt", b64.encodeToString(salt)).put("iv", b64.encodeToString(iv)).put("key", b64.encodeToString(c.doFinal(dataKey)))
    }

    fun unwrapKey(wrap: JSONObject, passphrase: CharArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        val salt = unb64.decode(wrap.getString("salt"))
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive(passphrase, salt, wrap.getInt("iter")), "AES"), GCMParameterSpec(128, unb64.decode(wrap.getString("iv"))))
        c.updateAAD("blacklab-backup-key".toByteArray())
        return try { c.doFinal(unb64.decode(wrap.getString("key"))) } catch (_: AEADBadTagException) { throw WrongPassphrase() }
    }

    private fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    fun isEncrypted(head: ByteArray): Boolean = head.size >= 4 && head.copyOf(4).contentEquals(MAGIC)

    /** Encrypts everything [write] produces into [out]. */
    fun encrypt(out: OutputStream, dataKey: ByteArray, wrap: JSONObject?, appVersion: String, schema: Int, write: (OutputStream) -> Unit) {
        val nonce = ByteArray(8).also(rnd::nextBytes)
        val header = JSONObject().put("keyId", keyId(dataKey)).put("created", System.currentTimeMillis()).put("app", appVersion)
            .put("schema", schema).put("nonce", b64.encodeToString(nonce)).apply { if (wrap != null) put("wrap", wrap) }
            .toString().toByteArray(Charsets.UTF_8)
        val d = DataOutputStream(out)
        d.write(MAGIC); d.writeByte(VERSION); d.writeInt(header.size); d.write(header)
        val sink = ChunkSink(d, SecretKeySpec(dataKey, "AES"), nonce, header)
        write(sink)
        sink.finish()
        d.flush()
    }

    fun readHeader(input: InputStream): Header {
        val d = DataInputStream(input)
        val magic = ByteArray(4); d.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a BlackLab backup")
        val v = d.readUnsignedByte()
        if (v != VERSION) throw IOException("This backup needs a newer BlackLab (format $v)")
        val len = d.readInt()
        if (len !in 2..65_536) throw IOException("Damaged backup header")
        val raw = ByteArray(len); d.readFully(raw)
        val o = JSONObject(String(raw, Charsets.UTF_8))
        return Header(o.getString("keyId"), o.optLong("created"), o.optString("app"), o.optInt("schema"),
            unb64.decode(o.getString("nonce")), o.optJSONObject("wrap"), raw)
    }

    /** Decrypts the chunks after [readHeader] into [out]. Throws if anything was changed or cut off. */
    fun decrypt(input: InputStream, header: Header, dataKey: ByteArray, out: OutputStream) {
        if (keyId(dataKey) != header.keyId) throw WrongPassphrase()
        val d = DataInputStream(input)
        val key = SecretKeySpec(dataKey, "AES")
        var counter = 0
        while (true) {
            val len = try { d.readInt() } catch (_: EOFException) { throw IOException("The backup is cut off") }
            val isFinal = len < 0
            val n = if (isFinal) len.inv() else len
            if (n !in 16..(CHUNK + 16)) throw IOException("Damaged backup")
            val ct = ByteArray(n); d.readFully(ct)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonceFor(header.nonce, counter)))
            c.updateAAD(aad(header.raw, counter, isFinal))
            out.write(try { c.doFinal(ct) } catch (_: AEADBadTagException) { throw IOException("The backup was changed or damaged") })
            if (isFinal) break
            counter++
        }
        if (d.read() != -1) throw IOException("Unexpected data after the backup")
    }

    private fun nonceFor(prefix: ByteArray, counter: Int) = ByteBuffer.allocate(12).put(prefix).putInt(counter).array()
    private fun aad(header: ByteArray, counter: Int, final: Boolean) = ByteBuffer.allocate(header.size + 5).put(header).putInt(counter).put(if (final) 1 else 0).array()

    /** Buffers one chunk at a time; the last chunk's length is stored inverted to mark it final. */
    private class ChunkSink(val d: DataOutputStream, val key: SecretKeySpec, val nonce: ByteArray, val header: ByteArray) : OutputStream() {
        private val buf = ByteArray(CHUNK)
        private var used = 0
        private var counter = 0
        private var done = false

        override fun write(b: Int) { if (used == CHUNK) emit(false); buf[used++] = b.toByte() }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off; var left = len
            while (left > 0) {
                if (used == CHUNK) emit(false)
                val n = minOf(left, CHUNK - used)
                System.arraycopy(b, o, buf, used, n); used += n; o += n; left -= n
            }
        }
        override fun close() { /* finish() is called explicitly so an exception mid-write never seals a partial backup */ }

        private fun emit(final: Boolean) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonceFor(nonce, counter)))
            c.updateAAD(aad(header, counter, final))
            val ct = c.doFinal(buf, 0, used)
            d.writeInt(if (final) ct.size.inv() else ct.size); d.write(ct)
            used = 0; counter++
        }

        fun finish() { if (!done) { emit(true); done = true } }
    }
}
