package com.candy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ───────────────────────── Talking to the Kanade bot ─────────────────────────

class ApiResult(val status: Int, val body: JSONObject?, val etag: String? = null, val fromCache: Boolean = false) {
    val ok: Boolean get() = status in 200..299

    /** The bot's own message when it sent one, otherwise a generic one. */
    fun message(c: Context): String {
        val fromBot = body?.optString("error").orEmpty()
        if (fromBot.isNotBlank()) return fromBot
        return c.getString(if (status == 0) R.string.err_offline else R.string.err_generic)
    }
}

class BytesResult(val status: Int, val bytes: ByteArray?, val etag: String?)

object CandyApi {
    /** The bot on this same phone (Termux). Only used when api-url.txt is empty. */
    private const val LOCAL_BASE = "http://127.0.0.1:8787"

    private val remote: String?
        get() = BuildConfig.PUBLIC_BASE.trim().trimEnd('/').takeIf { it.startsWith("https://") }

    private val base: String get() = remote ?: LOCAL_BASE

    /** The full address of a bot path, for players that fetch the file themselves. */
    fun urlOf(path: String): String = base + path

    /** GET when [body] is null, POST otherwise. */
    suspend fun call(path: String, body: JSONObject? = null, token: String? = null, ifNoneMatch: String? = null): ApiResult =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val c = URL(base + path).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = if (remote != null) 10000 else 4000
                c.readTimeout = 20000
                c.requestMethod = if (body == null) "GET" else "POST"
                c.setRequestProperty("Accept", "application/json")
                // ngrok shows a warning page to browsers; this tells it the caller is an app.
                c.setRequestProperty("ngrok-skip-browser-warning", "1")
                if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
                if (ifNoneMatch != null && body == null) c.setRequestProperty("If-None-Match", ifNoneMatch)
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                ApiResult(code, runCatching { JSONObject(text) }.getOrNull(), c.getHeaderField("ETag"))
            } catch (e: Exception) {
                ApiResult(0, null)
            } finally {
                conn?.disconnect()
            }
        }

    /** Sends a video as the raw request body. The answer holds the id to use when posting the stage. */
    suspend fun uploadVideo(token: String, input: InputStream, length: Long, onProgress: (Float) -> Unit): ApiResult =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val c = URL(base + "/candy/upload").openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = if (remote != null) 10000 else 4000
                c.readTimeout = 60000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setFixedLengthStreamingMode(length)
                c.setRequestProperty("Content-Type", "video/mp4")
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("ngrok-skip-browser-warning", "1")
                c.setRequestProperty("Authorization", "Bearer $token")
                c.outputStream.use { out ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    var n = input.read(buf)
                    while (n != -1) {
                        out.write(buf, 0, n)
                        sent += n
                        onProgress(if (length > 0) (sent.toFloat() / length.toFloat()).coerceIn(0f, 1f) else 0f)
                        n = input.read(buf)
                    }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                ApiResult(code, runCatching { JSONObject(text) }.getOrNull())
            } catch (e: Exception) {
                ApiResult(0, null)
            } finally {
                conn?.disconnect()
            }
        }

    /** Saves a file (a stage video) into [dest]. Returns false when it could not be fetched. */
    suspend fun download(path: String, token: String, dest: File, maxBytes: Long): Boolean =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val c = URL(base + path).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = if (remote != null) 10000 else 4000
                c.readTimeout = 30000
                c.setRequestProperty("ngrok-skip-browser-warning", "1")
                c.setRequestProperty("Authorization", "Bearer $token")
                if (c.responseCode !in 200..299 || c.contentLengthLong > maxBytes) return@withContext false
                c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                true
            } catch (e: Exception) {
                dest.delete()
                false
            } finally {
                conn?.disconnect()
            }
        }

    /** Downloads a file (a profile photo) with the same sign-in token. */
    suspend fun fetchBytes(path: String, token: String, etag: String? = null): BytesResult =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val c = URL(base + path).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = if (remote != null) 10000 else 4000
                c.readTimeout = 20000
                c.requestMethod = "GET"
                c.setRequestProperty("ngrok-skip-browser-warning", "1")
                c.setRequestProperty("Authorization", "Bearer $token")
                if (etag != null) c.setRequestProperty("If-None-Match", etag)
                val code = c.responseCode
                val tooBig = c.contentLength > 3_000_000
                val bytes = if (code in 200..299 && !tooBig) c.inputStream.use { it.readBytes() } else null
                BytesResult(code, bytes, c.getHeaderField("ETag"))
            } catch (e: Exception) {
                BytesResult(0, null, null)
            } finally {
                conn?.disconnect()
            }
        }
}

/** The signed-in token with the calls that need it. A 401 means the session ended, so we sign out. */
class Net(val token: String, private val onExpired: () -> Unit) {
    /**
     * GET. For the pages the bot answers with an ETag (feed, inbox, chats...) the last answer is kept on the phone, encrypted:
     *  - same data on the bot: it answers "304", we reuse the saved copy (nothing to download);
     *  - the bot is unreachable: we fall back to the saved copy, so the app still opens and shows the latest it saw.
     */
    suspend fun get(path: String): ApiResult {
        val key = ApiCache.keyOf(token, path)
        val saved = ApiCache.read(key)
        val r = CandyApi.call(path, null, token, saved?.etag)
        if (r.status == 304) {
            val body = saved?.let { runCatching { JSONObject(it.body) }.getOrNull() }
            if (body != null) return ApiResult(200, body, saved?.etag, fromCache = true)
            ApiCache.drop(key)
            return guard(CandyApi.call(path, null, token))
        }
        if (r.ok && r.etag != null && r.body != null) ApiCache.write(key, r.etag, r.body.toString())
        if (r.status == 0 && saved != null) {
            val body = runCatching { JSONObject(saved.body) }.getOrNull()
            if (body != null) return ApiResult(200, body, saved.etag, fromCache = true)
        }
        return guard(r)
    }

    /** The last saved answer for [path], without touching the network. Used to show something the moment a screen opens. */
    suspend fun peek(path: String): JSONObject? =
        ApiCache.read(ApiCache.keyOf(token, path))?.let { e -> runCatching { JSONObject(e.body) }.getOrNull() }

    suspend fun post(path: String, body: JSONObject = JSONObject()): ApiResult =
        guard(CandyApi.call(path, body, token))

    private fun guard(r: ApiResult): ApiResult {
        if (r.status == 401) onExpired()
        return r
    }
}

// ───────────────────────── The signed-in session, kept encrypted ─────────────────────────

class Session(val token: String, val profile: JSONObject)

/** The token and the profile are encrypted with a key that never leaves the Android Keystore. */
object SessionStore {
    private const val ALIAS = "candy_account"
    private const val PREF = "candy_account"

    private fun key(): SecretKey {
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

    fun save(c: Context, s: Session) {
        try {
            val plain = JSONObject().put("token", s.token).put("profile", s.profile).toString()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("s", Base64.encodeToString(out, Base64.NO_WRAP)).apply()
        } catch (e: Exception) {
            // Nothing to do: the person just signs in again next time.
        }
    }

    fun load(c: Context): Session? {
        return try {
            val raw = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("s", null) ?: return null
            val all = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
            val o = JSONObject(String(cipher.doFinal(all, 12, all.size - 12), Charsets.UTF_8))
            Session(o.getString("token"), o.getJSONObject("profile"))
        } catch (e: Exception) {
            null
        }
    }

    fun clear(c: Context) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove("s").apply()
    }
}
