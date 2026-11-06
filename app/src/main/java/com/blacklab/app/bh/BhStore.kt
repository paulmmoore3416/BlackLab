package com.blacklab.app.bh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Behavioral Health store: one JSON document encrypted with AES-256-GCM under a key held in
 * the Android Keystore, in app-private storage. It is excluded from Android backups and device
 * transfer, never synced to BlackLab Desktop or Google Drive, never indexed by "Ask my files", and
 * never read aloud in the car. Old census, rounds and drafted notes are deleted automatically.
 */
object BhStore {
    private const val ALIAS = "blacklab_bh_v1"

    private val _state = MutableStateFlow(Bh.State())
    val state: StateFlow<Bh.State> = _state.asStateFlow()
    @Volatile private var loaded = false

    fun dir(context: Context) = File(context.filesDir, "bh").also { it.mkdirs() }
    private fun file(context: Context) = File(dir(context), "workspace.enc")

    fun load(context: Context): Bh.State {
        if (loaded) return _state.value
        synchronized(this) {
            if (loaded) return _state.value
            val f = file(context)
            val s = if (!f.exists()) Bh.State() else runCatching { Bh.fromJson(JSONObject(String(decrypt(f.readBytes()), Charsets.UTF_8))) }.getOrElse {
                // Can't read it (key removed with the screen lock, or damaged): keep the file aside, start clean
                runCatching { f.renameTo(File(dir(context), "workspace.unreadable.${System.currentTimeMillis()}")) }
                Bh.State()
            }
            _state.value = purge(s, System.currentTimeMillis())
            loaded = true
        }
        return _state.value
    }

    /** Forgets the in-memory copy and reads the encrypted file again (device tests). */
    internal fun reload(context: Context): Bh.State { synchronized(this) { loaded = false }; return load(context) }

    fun update(context: Context, change: (Bh.State) -> Bh.State) {
        synchronized(this) {
            val next = purge(change(load(context)), System.currentTimeMillis())
            _state.value = next
            runCatching {
                val bytes = encrypt(Bh.toJson(next).toString().toByteArray(Charsets.UTF_8))
                com.blacklab.app.files.FileOps.writeAtomically(file(context)) { it.writeBytes(bytes) }
            }
        }
        runCatching { Rounds.rearm(context) }
    }

    /** Erases everything in the workspace (keeps nothing, not even preferences). */
    fun wipe(context: Context) {
        synchronized(this) {
            dir(context).listFiles()?.forEach { f -> runCatching { f.writeBytes(ByteArray(f.length().toInt())); f.delete() } }
            _state.value = Bh.State()
        }
        runCatching { Rounds.rearm(context) }
    }

    // ---------------------------------------------------------------- retention (pure; unit tested)

    /** Kinds that describe other people and so follow the note-retention setting. Your own check-ins and supervision logs are kept. */
    val PEOPLE_KINDS = setOf(Specs.PEER_NOTE, Specs.INCIDENT, Specs.HANDOFF, Specs.GROUP_NOTE, Specs.REFERRAL, Specs.DEBRIEF, Specs.SAFETY_PLAN, Specs.BEHAVIOR)

    fun purge(s: Bh.State, now: Long): Bh.State {
        val keepMs = s.prefs.retentionHours.coerceAtLeast(1) * 3_600_000L
        val cutoff = now - keepMs
        val discharged = s.patients.filter { !it.active && it.added < cutoff }.map { it.id }.toSet()
        val noteCut = if (s.prefs.noteDays <= 0) Long.MIN_VALUE else now - s.prefs.noteDays * 86_400_000L
        return s.copy(
            checks = s.checks.filter { it.time >= cutoff && it.patientId !in discharged },
            patients = s.patients.filter { it.id !in discharged },
            shifts = s.shifts.filter { it.end == null || it.end >= now - 90L * 86_400_000L }.take(200),
            records = s.records.filter { it.kind !in PEOPLE_KINDS || it.updated >= noteCut },
        )
    }

    // ---------------------------------------------------------------- crypto

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

    // ---------------------------------------------------------------- convenience mutations

    fun prefs(context: Context, change: (Bh.Prefs) -> Bh.Prefs) = update(context) { it.copy(prefs = change(it.prefs)) }

    fun saveRecord(context: Context, r: Forms.Rec) = update(context) { s ->
        s.copy(records = if (s.records.any { it.id == r.id }) s.records.map { if (it.id == r.id) r else it } else listOf(r) + s.records)
    }
    fun deleteRecord(context: Context, id: String) = update(context) { s -> s.copy(records = s.records.filterNot { it.id == id }) }

    fun upsertPatient(context: Context, p: Bh.Patient) = update(context) { s ->
        s.copy(patients = if (s.patients.any { it.id == p.id }) s.patients.map { if (it.id == p.id) p else it } else s.patients + p)
    }
    fun discharge(context: Context, id: String) = update(context) { s -> s.copy(patients = s.patients.map { if (it.id == id) it.copy(active = false, added = System.currentTimeMillis()) else it }) }
    fun clearCensus(context: Context) = update(context) { s -> s.copy(patients = emptyList(), checks = emptyList(), roundsOn = false, roundsSince = null) }

    fun addChecks(context: Context, checks: List<Bh.Check>) = update(context) { s -> s.copy(checks = (checks + s.checks).sortedByDescending { it.time }) }
    fun deleteCheck(context: Context, id: String) = update(context) { s -> s.copy(checks = s.checks.filterNot { it.id == id }) }
    fun setRounds(context: Context, on: Boolean) = update(context) { s -> s.copy(roundsOn = on, roundsSince = if (on) System.currentTimeMillis() else null) }

    fun upsertShift(context: Context, sh: Bh.Shift) = update(context) { s ->
        s.copy(shifts = (if (s.shifts.any { it.id == sh.id }) s.shifts.map { if (it.id == sh.id) sh else it } else listOf(sh) + s.shifts).sortedByDescending { it.start })
    }

    fun upsertParticipant(context: Context, p: Bh.Participant) = update(context) { s ->
        s.copy(participants = if (s.participants.any { it.id == p.id }) s.participants.map { if (it.id == p.id) p else it } else s.participants + p)
    }
    fun deleteParticipant(context: Context, id: String) = update(context) { s ->
        s.copy(participants = s.participants.filterNot { it.id == id }, records = s.records.filterNot { it.ref == id && it.kind in PEOPLE_KINDS })
    }

    fun upsertPlan(context: Context, g: Bh.GroupPlan) = update(context) { s ->
        s.copy(plans = if (s.plans.any { it.id == g.id }) s.plans.map { if (it.id == g.id) g else it } else s.plans + g)
    }
    fun deletePlan(context: Context, id: String) = update(context) { s -> s.copy(plans = s.plans.filterNot { it.id == id }) }

    fun upsertResource(context: Context, r: Bh.Resource) = update(context) { s ->
        s.copy(resources = if (s.resources.any { it.id == r.id }) s.resources.map { if (it.id == r.id) r else it } else s.resources + r)
    }
    fun deleteResource(context: Context, id: String) = update(context) { s -> s.copy(resources = s.resources.filterNot { it.id == id }, favorites = s.favorites - id) }
    fun toggleFavorite(context: Context, id: String) = update(context) { s -> s.copy(favorites = if (id in s.favorites) s.favorites - id else s.favorites + id) }

    fun upsertEoc(context: Context, e: Bh.EocRound) = update(context) { s ->
        s.copy(eoc = (if (s.eoc.any { it.id == e.id }) s.eoc.map { if (it.id == e.id) e else it } else listOf(e) + s.eoc).sortedByDescending { it.time })
    }
    fun deleteEoc(context: Context, id: String) = update(context) { s -> s.copy(eoc = s.eoc.filterNot { it.id == id }) }
}
