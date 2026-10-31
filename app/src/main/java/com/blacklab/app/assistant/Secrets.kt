package com.blacklab.app.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys and tokens, encrypted with a hardware-backed Android Keystore key. Nothing here is
 * ever compiled into the APK, written to the BlackLab folder, included in backups or returned
 * by the desktop link (which only reports whether a key is set).
 */
class Secrets(context: Context) {

    companion object {
        const val ELEVENLABS = "elevenlabs"
        const val DEV_KNOWLEDGE = "developer_knowledge"
        const val GOOGLE_DRIVE = "google_drive"
        const val GMAIL = "gmail"
        const val GOOGLE_KEEP = "google_keep"
        const val LINK_TOKEN = "link_token"

        val all = listOf(ELEVENLABS, DEV_KNOWLEDGE, GOOGLE_DRIVE, GMAIL, GOOGLE_KEEP)
        private const val ALIAS = "blacklab_secrets_v1"
    }

    private val prefs = context.getSharedPreferences("blacklab_secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun get(name: String): String? = runCatching {
        val blob = prefs.getString(name, null) ?: return null
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    fun has(name: String): Boolean = prefs.contains(name)

    /** The desktop pairing code, created on first use. */
    fun linkToken(): String = get(LINK_TOKEN) ?: run {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        val token = (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        put(LINK_TOKEN, token)
        token
    }

    fun resetLinkToken(): String { put(LINK_TOKEN, null); return linkToken() }

    /**
     * One-time import of keys pushed to `BlackLab/.provision/keys.json` (e.g. with adb push).
     * The file is deleted after it is read so plaintext keys don't linger on shared storage.
     */
    fun importProvisioning(root: File): Int {
        val dir = File(root, ".provision")
        val f = File(dir, "keys.json")
        if (!f.exists()) return 0
        val count = runCatching {
            val json = JSONObject(f.readText())
            var n = 0
            json.keys().forEach { k -> if (k in all) { put(k, json.optString(k)); n++ } }
            n
        }.getOrDefault(0)
        f.delete()
        dir.delete()
        return count
    }
}
