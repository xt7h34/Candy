package com.candy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One answer from the bot as we saved it: the ETag it came with, and the JSON text. */
class SavedAnswer(val etag: String, val body: String)

/**
 * The bot's last answers, kept on the phone so screens can open instantly and the app still works when the bot is unreachable.
 * Everything is encrypted with a key that never leaves the Android Keystore (chats are private), lives in the app's own
 * folder (backups are off), is wiped on sign-out, and is capped in size (the oldest answers go first).
 */
object ApiCache {
    private const val ALIAS = "candy_cache"
    private const val DIR = "api_cache"
    private const val MAX_BYTES = 24L * 1024 * 1024
    private const val TRIM_EVERY = 25

    @Volatile private var dir: File? = null
    private val memory = LruCache<String, SavedAnswer>(48)
    private val writes = AtomicInteger(0)

    fun init(c: Context) {
        dir = File(c.applicationContext.filesDir, DIR).apply { mkdirs() }
    }

    /** The file name for one answer: tied to who is signed in and to the exact address asked, and it reveals neither. */
    fun keyOf(token: String, path: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("$token\n$path".toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
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

    suspend fun read(key: String): SavedAnswer? = withContext(Dispatchers.IO) {
        memory.get(key)?.let { return@withContext it }
        val f = File(dir ?: return@withContext null, key)
        if (!f.isFile) return@withContext null
        try {
            val all = f.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
            val text = String(cipher.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
            val nl = text.indexOf('\n')
            if (nl <= 0) {
                f.delete()
                return@withContext null
            }
            f.setLastModified(System.currentTimeMillis())   // recently used stays longer
            SavedAnswer(text.substring(0, nl), text.substring(nl + 1)).also { memory.put(key, it) }
        } catch (e: Exception) {
            f.delete()   // unreadable (for example the key changed): start fresh
            null
        }
    }

    suspend fun write(key: String, etag: String, body: String) {
        withContext(Dispatchers.IO) {
            val d = dir ?: return@withContext
            try {
                memory.put(key, SavedAnswer(etag, body))
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey())
                val out = cipher.iv + cipher.doFinal("$etag\n$body".toByteArray(Charsets.UTF_8))
                val tmp = File(d, "$key.tmp")
                tmp.writeBytes(out)
                if (!tmp.renameTo(File(d, key))) tmp.delete()
                if (writes.incrementAndGet() % TRIM_EVERY == 0) trim(d)
            } catch (e: Exception) {
                // Saving is only a speed-up: the app works without it.
            }
        }
    }

    suspend fun drop(key: String) {
        withContext(Dispatchers.IO) {
            memory.remove(key)
            dir?.let { File(it, key).delete() }
        }
    }

    private fun trim(d: File) {
        val files = d.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES * 3 / 4) break
            total -= f.length()
            f.delete()
        }
        memory.evictAll()
    }

    /** Sign-out: nothing of the person's chats or feed stays behind. */
    fun clear() {
        memory.evictAll()
        dir?.listFiles()?.forEach { it.delete() }
    }
}
