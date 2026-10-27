package com.candy

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** Profile photos of other people, kept in memory while the app is open. The bot decides which photo a person shows. */
object AvatarCache {
    private val mem = LruCache<String, Bitmap>(80)
    private val missing = HashSet<String>()

    private fun key(hash: String) = bareHash(hash).lowercase()

    suspend fun load(token: String, hash: String): Bitmap? {
        val k = key(hash)
        if (k.isEmpty()) return null
        val cached = mem.get(k)
        if (cached != null) return cached
        if (missing.contains(k)) return null
        val r = CandyApi.fetchBytes("/avatar?hash=" + Uri.encode(k), token)
        val bytes = r.bytes
        if (r.status in 200..299 && bytes != null && bytes.isNotEmpty()) {
            val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            if (bmp != null) {
                mem.put(k, bmp)
                return bmp
            }
        }
        if (r.status == 404) missing.add(k)
        return null
    }

    fun forget(hash: String) {
        val k = key(hash)
        mem.remove(k)
        missing.remove(k)
    }

    fun clear() {
        mem.evictAll()
        missing.clear()
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
fun AvatarEditor(net: Net, myHash: String, avatar: JSONObject?, onChanged: () -> Unit) {
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
        Avatar(myHash, net.token, 96.dp, version, Modifier.clickable(enabled = !busy) { open = true })
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
                Column(modifier = Modifier.fillMaxWidth()) {
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
