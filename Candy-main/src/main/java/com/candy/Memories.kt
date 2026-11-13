package com.candy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Memory(val file: String, val isVideo: Boolean, val fromCamera: Boolean, val at: Long)

/**
 * Memories: photos and videos you keep inside the app, on this phone only (private app storage).
 * Nothing is uploaded until you send one somewhere.
 */
object MemoriesStore {
    private const val MAX_ITEMS = 200

    private fun dir(c: Context): File = File(c.filesDir, "memories").also { it.mkdirs() }
    private fun index(c: Context): File = File(dir(c), "index.json")

    fun fileOf(c: Context, m: Memory): File = File(dir(c), m.file)
    fun uriOf(c: Context, m: Memory): Uri = Uri.fromFile(fileOf(c, m))

    fun list(c: Context): List<Memory> {
        val raw = try { index(c).readText() } catch (e: Exception) { return emptyList() }
        val out = ArrayList<Memory>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val m = Memory(o.optString("file"), o.optBoolean("video"), o.optBoolean("camera"), o.optLong("at"))
                if (fileOf(c, m).exists()) out.add(m)
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out.sortedByDescending { it.at }
    }

    private fun write(c: Context, items: List<Memory>) {
        val arr = JSONArray()
        for (m in items) arr.put(JSONObject().put("file", m.file).put("video", m.isVideo).put("camera", m.fromCamera).put("at", m.at))
        index(c).writeText(arr.toString())
    }

    /** Copies the photo or video into the app's private storage. */
    fun add(c: Context, picked: Picked): Boolean = try {
        val name = "m_${System.currentTimeMillis()}" + if (picked.isVideo) ".mp4" else ".jpg"
        val target = File(dir(c), name)
        val input = c.contentResolver.openInputStream(picked.uri)
        if (input == null) {
            false
        } else {
            input.use { src -> target.outputStream().use { src.copyTo(it) } }
            val all = list(c).toMutableList()
            all.add(0, Memory(name, picked.isVideo, picked.fromCamera, System.currentTimeMillis()))
            // keep the newest items, and delete the files that fall off the list
            for (old in all.drop(MAX_ITEMS)) fileOf(c, old).delete()
            write(c, all.take(MAX_ITEMS))
            true
        }
    } catch (e: Exception) {
        false
    }

    fun delete(c: Context, m: Memory) {
        fileOf(c, m).delete()
        write(c, list(c).filter { it.file != m.file })
    }
}

private val thumbs = LruCache<String, Bitmap>(80)

private fun thumbOf(c: Context, m: Memory): Bitmap? {
    thumbs.get(m.file)?.let { return it }
    val f = MemoriesStore.fileOf(c, m)
    val bmp: Bitmap? = try {
        if (m.isVideo) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(f.absolutePath)
                mmr.getFrameAtTime(0)
            } finally {
                mmr.release()
            }
        } else {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(f.absolutePath, opts)
        }
    } catch (e: Throwable) {
        null
    }
    if (bmp != null) thumbs.put(m.file, bmp)
    return bmp
}

/** Your saved photos and videos. Tap one to send it somewhere or delete it. */
@Composable
fun MemoriesScreen(onBack: () -> Unit, onUse: (Picked, String) -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<Memory>>(emptyList()) }
    var chosen by remember { mutableStateOf<Memory?>(null) }

    LaunchedEffect(Unit) { items = withContext(Dispatchers.IO) { MemoriesStore.list(ctx) } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.mem_title), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Muted(stringResource(R.string.mem_hint), Modifier.padding(bottom = 8.dp))
            if (items.isEmpty()) Muted(stringResource(R.string.mem_empty))
            for (row in items.chunked(3)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (m in row) {
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { chosen = m },
                        ) {
                            var bmp by remember(m.file) { mutableStateOf<Bitmap?>(null) }
                            LaunchedEffect(m.file) { bmp = withContext(Dispatchers.IO) { thumbOf(ctx, m) } }
                            val b = bmp
                            if (b != null) {
                                Image(bitmap = b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            if (m.isVideo) Text("▶", color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
                        }
                    }
                    for (i in row.size until 3) Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    val m = chosen
    if (m != null) {
        val picked = Picked(MemoriesStore.uriOf(ctx, m), m.isVideo, m.fromCamera)
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(stringResource(R.string.mem_title)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { chosen = null; onUse(picked, "stage") }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.mem_stage))
                    }
                    if (m.isVideo) {
                        TextButton(onClick = { chosen = null; onUse(picked, "loop") }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.mem_loop))
                        }
                    } else {
                        TextButton(onClick = { chosen = null; onUse(picked, "track") }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.mem_track))
                        }
                    }
                    if (m.fromCamera) {
                        TextButton(onClick = { chosen = null; onUse(picked, "flare") }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.mem_flare))
                        }
                    }
                    TextButton(
                        onClick = {
                            MemoriesStore.delete(ctx, m)
                            thumbs.remove(m.file)
                            items = MemoriesStore.list(ctx)
                            chosen = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.post_delete), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { chosen = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}
