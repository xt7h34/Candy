package com.candy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Pictures sent in a chat. The bot keeps one picture per message and never changes it, so it is saved on the phone the first time
 * it is seen (the oldest go first when it is full) and shown from there afterwards. Gone with the rest on sign-out.
 */
object ChatImageCache {
    private const val DISK_MAX = 100L * 1024 * 1024

    private val mem = object : LruCache<String, Bitmap>(20 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    @Volatile private var dir: File? = null

    fun init(c: Context) {
        dir = File(c.applicationContext.cacheDir, "chat_images").apply { mkdirs() }
    }

    private fun fileOf(peerHash: String, n: Int): File? =
        dir?.let { File(it, (bareHash(peerHash) + "_" + n).replace(Regex("[^A-Za-z0-9._-]"), "_")) }

    private fun trim(d: File) {
        val files = d.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_MAX) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_MAX * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    /** Opens the picture without loading more pixels than needed for [maxSide]. */
    private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    suspend fun load(token: String, peerHash: String, n: Int, maxSide: Int): Bitmap? {
        val key = bareHash(peerHash) + "_" + n + "_" + maxSide
        mem.get(key)?.let { return it }

        val file = fileOf(peerHash, n)
        if (file != null && file.length() > 0) {
            val saved = withContext(Dispatchers.IO) {
                val bmp = runCatching { decode(file.readBytes(), maxSide) }.getOrNull()
                if (bmp != null) file.setLastModified(System.currentTimeMillis()) else file.delete()
                bmp
            }
            if (saved != null) {
                mem.put(key, saved)
                return saved
            }
        }

        val path = "/candy/dm/image?hash=" + Uri.encode(bareHash(peerHash)) + "&n=" + n
        val r = CandyApi.fetchBytes(path, token)
        val bytes = r.bytes
        if (r.status !in 200..299 || bytes == null || bytes.isEmpty()) return null
        val bmp = withContext(Dispatchers.Default) { decode(bytes, maxSide) } ?: return null
        mem.put(key, bmp)
        if (file != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(file)) tmp.delete()
                    if (Math.random() < 0.05) file.parentFile?.let { trim(it) }
                }
            }
        }
        return bmp
    }

    fun clear() {
        mem.evictAll()
        dir?.listFiles()?.forEach { it.delete() }
    }
}

/** A small, upright preview of a picture the person has picked and not sent yet. */
object ChatImagePreview {
    fun load(c: Context, uri: Uri, side: Int = 320): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
            c.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (e: Exception) {
        null
    }
}

/** The picture inside a message bubble. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatImage(
    net: Net,
    peerHash: String,
    n: Int,
    w: Int,
    h: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var bmp by remember(peerHash, n) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(peerHash, n) { mutableStateOf(false) }
    LaunchedEffect(peerHash, n) {
        val b = ChatImageCache.load(net.token, peerHash, n, 640)
        if (b != null) bmp = b else failed = true
    }
    // the space is kept from the start (from the real size), so the chat does not jump when the picture arrives
    val ratio = if (w > 0 && h > 0) (w.toFloat() / h.toFloat()).coerceIn(0.6f, 1.8f) else 1f
    Box(
        modifier
            .width(230.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x22000000))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        when {
            b != null -> Image(bitmap = b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            failed -> Text("📷")
            else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

/** The picture full screen: pinch or double tap to zoom, tap to close. */
@Composable
fun ImageViewer(net: Net, peerHash: String, n: Int, onClose: () -> Unit) {
    var bmp by remember(peerHash, n) { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(peerHash, n) { bmp = ChatImageCache.load(net.token, peerHash, n, 2048) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClose() },
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            offset = Offset.Zero
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale > 1f) offset + pan else Offset.Zero
                            }
                        },
                )
            } else {
                CircularProgressIndicator(color = Color.White)
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                Text("✕", color = Color.White)
            }
        }
    }
}
