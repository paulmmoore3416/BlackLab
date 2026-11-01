package com.blacklab.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Your saved signatures and initials. Each image is encrypted with AES-256-GCM under a key that
 * lives in the phone's Android Keystore (it never leaves the device), stored in app-private
 * storage, and excluded from backups, device transfer, cloud sync and the desktop link.
 */
object Signatures {
    const val SIGNATURE = "SIGNATURE"
    const val INITIALS = "INITIALS"
    private const val ALIAS = "blacklab_signatures_v1"
    private const val LEGACY = "signature.png" // 4.1–4.3 kept one unencrypted signature here

    data class Sig(val id: String, val kind: String, val label: String, val created: Long, val isDefault: Boolean, val width: Int, val height: Int)

    /** Bumped on every change so screens can refresh. */
    val version = MutableStateFlow(0)

    fun dir(context: Context) = File(context.filesDir, "signatures").also { it.mkdirs() }
    private fun index(context: Context) = File(dir(context), "index.json")
    private fun file(context: Context, id: String) = File(dir(context), "$id.sig")

    // ----------------------------------------------------------------- index (pure helpers, unit tested)

    fun parse(json: String): List<Sig> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Sig(o.getString("id"), o.optString("kind", SIGNATURE), o.optString("label"), o.optLong("created"), o.optBoolean("default"), o.optInt("w"), o.optInt("h"))
        }
    }.getOrDefault(emptyList())

    fun serialize(list: List<Sig>): String = JSONArray().apply {
        list.forEach { s -> put(JSONObject().put("id", s.id).put("kind", s.kind).put("label", s.label).put("created", s.created).put("default", s.isDefault).put("w", s.width).put("h", s.height)) }
    }.toString()

    /** Exactly one default per kind when any exist: [preferred] if given, else the current default, else the newest. */
    fun normalizeDefaults(list: List<Sig>, preferred: String? = null): List<Sig> = list.groupBy { it.kind }.flatMap { (_, group) ->
        val chosen = group.firstOrNull { it.id == preferred }?.id
            ?: group.firstOrNull { it.isDefault }?.id
            ?: group.maxByOrNull { it.created }?.id
        group.map { it.copy(isDefault = it.id == chosen) }
    }.sortedWith(compareBy({ it.kind != SIGNATURE }, { -it.created }))

    // ----------------------------------------------------------------- store

    @Synchronized
    fun list(context: Context): List<Sig> {
        migrateLegacy(context)
        return parse(index(context).takeIf { it.exists() }?.readText() ?: "[]").filter { file(context, it.id).exists() }
    }

    fun default(context: Context, kind: String): Sig? = list(context).firstOrNull { it.kind == kind && it.isDefault }

    @Synchronized
    private fun write(context: Context, list: List<Sig>) {
        com.blacklab.app.files.FileOps.writeAtomically(index(context)) { it.writeBytes(serialize(list).toByteArray()) }
        version.value++
    }

    @Synchronized
    fun save(context: Context, kind: String, bitmap: Bitmap, label: String = "", makeDefault: Boolean = true): Sig {
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        com.blacklab.app.files.FileOps.writeAtomically(file(context, id)) { it.writeBytes(encrypt(png)) }
        val sig = Sig(id, kind, label.ifBlank { if (kind == INITIALS) "Initials" else "Signature" }, System.currentTimeMillis(), makeDefault, bitmap.width, bitmap.height)
        write(context, normalizeDefaults(list(context) + sig, if (makeDefault) id else null))
        return sig
    }

    fun load(context: Context, id: String): Bitmap? = runCatching {
        val bytes = decrypt(file(context, id).readBytes())
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    @Synchronized
    fun setDefault(context: Context, id: String) = write(context, normalizeDefaults(list(context), id))

    @Synchronized
    fun rename(context: Context, id: String, label: String) = write(context, list(context).map { if (it.id == id) it.copy(label = label.take(40)) else it })

    @Synchronized
    fun delete(context: Context, id: String) {
        file(context, id).let { f -> if (f.exists()) { f.writeBytes(ByteArray(f.length().toInt())); f.delete() } }
        write(context, normalizeDefaults(list(context).filter { it.id != id }))
    }

    /** Moves the old unencrypted signature (PDF markup, 4.1–4.3) into the encrypted store, then removes it. */
    private fun migrateLegacy(context: Context) {
        val old = File(context.filesDir, LEGACY)
        if (!old.exists()) return
        val bmp = BitmapFactory.decodeFile(old.path)
        if (bmp != null) {
            val id = "legacy" + System.currentTimeMillis().toString(36)
            val png = old.readBytes()
            com.blacklab.app.files.FileOps.writeAtomically(file(context, id)) { it.writeBytes(encrypt(png)) }
            val existing = parse(index(context).takeIf { it.exists() }?.readText() ?: "[]")
            val sig = Sig(id, SIGNATURE, "Signature", old.lastModified(), existing.none { it.kind == SIGNATURE }, bmp.width, bmp.height)
            com.blacklab.app.files.FileOps.writeAtomically(index(context)) { it.writeBytes(serialize(normalizeDefaults(existing + sig)).toByteArray()) }
        }
        old.writeBytes(ByteArray(old.length().toInt())); old.delete()
    }

    // ----------------------------------------------------------------- crypto

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12)) }
        return c.doFinal(data, 12, data.size - 12)
    }
}
