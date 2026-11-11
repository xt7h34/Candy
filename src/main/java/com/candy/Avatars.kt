package com.candy

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** Profile photos of other people, kept in memory while the app is open. The bot decides which photo a person shows. */
object AvatarCache {
    private val mem = LruCache<String, Bitmap>(80)
    private val missing = HashSet<String>()

    // Photos are also kept on the phone. One that is under an hour old is used as it is; an older one is checked with the bot
    // first (it answers "unchanged" without sending the photo again), and is used as it is when the bot cannot be reached.
    private const val FRESH_MS = 60L * 60 * 1000
    @Volatile private var dir: File? = null

    fun init(c: Context) {
        dir = File(c.applicationContext.cacheDir, "avatars").apply { mkdirs() }
    }

    private fun key(hash: String) = bareHash(hash).lowercase()

    private fun fileOf(k: String): File? = dir?.let { File(it, k.replace(Regex("[^A-Za-z0-9._-]"), "_")) }

    suspend fun load(token: String, hash: String): Bitmap? {
        val k = key(hash)
        if (k.isEmpty()) return null
        val cached = mem.get(k)
        if (cached != null) return cached
        if (missing.contains(k)) return null

        val f = fileOf(k)
        val tagFile = f?.let { File(it.parentFile, it.name + ".etag") }
        var onDisk: Bitmap? = null
        var tag: String? = null
        if (f != null && f.length() > 0) {
            onDisk = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() }
            tag = tagFile?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
            if (onDisk != null && System.currentTimeMillis() - f.lastModified() < FRESH_MS) {
                mem.put(k, onDisk)
                return onDisk
            }
        }

        val r = CandyApi.fetchBytes("/avatar?hash=" + Uri.encode(k), token, if (onDisk != null) tag else null)
        val bytes = r.bytes
        if (r.status == 304 && onDisk != null) {
            f?.setLastModified(System.currentTimeMillis())
            mem.put(k, onDisk)
            return onDisk
        }
        if (r.status in 200..299 && bytes != null && bytes.isNotEmpty()) {
            val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            if (bmp != null) {
                mem.put(k, bmp)
                if (f != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            f.writeBytes(bytes)
                            if (r.etag != null) tagFile?.writeText(r.etag) else tagFile?.delete()
                        }
                    }
                }
                return bmp
            }
        }
        if (r.status == 404) {
            missing.add(k)
            f?.delete()
            tagFile?.delete()
        }
        if (onDisk != null && r.status == 0) {
            mem.put(k, onDisk)
            return onDisk
        }
        return null
    }

    fun forget(hash: String) {
        val k = key(hash)
        mem.remove(k)
        missing.remove(k)
        fileOf(k)?.let { it.delete(); File(it.parentFile, it.name + ".etag").delete() }
    }

    fun clear() {
        mem.evictAll()
        missing.clear()
        dir?.listFiles()?.forEach { it.delete() }
    }
}

/** The round profile photo. Shows the Candy lollipop when the person has no photo. */
@Composable
fun Avatar(hash: String, token: String, size: Dp, version: Int = 0, modifier: Modifier = Modifier) {
    var bmp by remember(hash, version) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(hash, version, token) {
        bmp = AvatarCache.load(token, hash)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colorResource(R.color.ic_launcher_background)),
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.3f),
            )
        }
    }
}

object AvatarUploader {
    private const val MAX_SIDE = 512

    /** Reads the picked image, turns it upright, crops it to a square and shrinks it. */
    fun prepare(c: Context, uri: Uri): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (raw == null) {
                null
            } else {
                val orientation = c.contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
                val m = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                }
                val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                val side = min(upright.width, upright.height)
                val square = Bitmap.createBitmap(upright, (upright.width - side) / 2, (upright.height - side) / 2, side, side)
                val done = if (side > MAX_SIDE) Bitmap.createScaledBitmap(square, MAX_SIDE, MAX_SIDE, true) else square
                val out = ByteArrayOutputStream()
                done.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        }
    } catch (e: Throwable) {
        null
    }
}

/** Your own photo with a button to change it: pick from the gallery, use the WhatsApp photo, or remove it. */
@Composable
fun AvatarEditor(net: Net, myHash: String, avatar: JSONObject?, onAddStage: (() -> Unit)? = null, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var version by remember { mutableStateOf(0) }

    val hasCustom = avatar?.optBoolean("hasCustom") == true
    val usingCustom = avatar?.optString("mode") == "custom"

    fun finish(r: ApiResult) {
        busy = false
        if (r.ok) {
            error = ""
            AvatarCache.forget(myHash)
            version++
            onChanged()
        } else {
            error = r.message(ctx)
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val jpeg = withContext(Dispatchers.IO) { AvatarUploader.prepare(ctx, uri) }
                if (jpeg == null) {
                    busy = false
                    error = ctx.getString(R.string.avatar_unreadable)
                } else {
                    finish(net.post("/me/avatar", JSONObject().put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Avatar(
                myHash, net.token, 96.dp, version,
                Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape).clickable(enabled = !busy) { open = true },
            )
            if (onAddStage != null) {
                // the + on the photo: starts a new stage
                val ring = MaterialTheme.colorScheme.background
                val desc = stringResource(R.string.story_new)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 4.dp, y = 4.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(ring)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .semantics { contentDescription = desc }
                        .clickable(enabled = !busy, onClick = onAddStage),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(16.dp)) {
                        val w = 2.2.dp.toPx()
                        drawLine(Color.Black, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = w, cap = StrokeCap.Round)
                        drawLine(Color.Black, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = w, cap = StrokeCap.Round)
                    }
                }
            }
        }
        TextButton(onClick = { open = true }, enabled = !busy) { Text(stringResource(R.string.avatar_change)) }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (error.isNotBlank()) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.avatar_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = { open = false; pick.launch("image/*") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.avatar_choose)) }
                    if (usingCustom) {
                        TextButton(
                            onClick = {
                                open = false
                                busy = true
                                scope.launch { finish(net.post("/me/avatar/mode", JSONObject().put("mode", "whatsapp"))) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.avatar_whatsapp)) }
                    } else if (hasCustom) {
                        TextButton(
                            onClick = {
                                open = false
                                busy = true
                                scope.launch { finish(net.post("/me/avatar/mode", JSONObject().put("mode", "custom"))) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.avatar_use_saved)) }
                    }
                    if (hasCustom) {
                        TextButton(
                            onClick = {
                                open = false
                                busy = true
                                scope.launch { finish(net.post("/me/avatar/remove")) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.avatar_remove)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}