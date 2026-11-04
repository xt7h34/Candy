package com.candy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val STICKER_MAX_BYTES = 400 * 1024

/** Stickers kept in memory while the app is open. */
object StickerCache {
    private val mem = LruCache<String, Bitmap>(60)

    suspend fun load(token: String, id: String): Bitmap? {
        val cached = mem.get(id)
        if (cached != null) return cached
        val r = CandyApi.fetchBytes("/candy/sticker?id=" + Uri.encode(id), token)
        val bytes = r.bytes
        if (r.status in 200..299 && bytes != null && bytes.isNotEmpty()) {
            val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            if (bmp != null) {
                mem.put(id, bmp)
                return bmp
            }
        }
        return null
    }

    fun forget(id: String) {
        mem.remove(id)
    }

    fun clear() = mem.evictAll()
}

/** A sticker, with no frame around it. */
@Composable
fun StickerImage(id: String, token: String, size: Dp, modifier: Modifier = Modifier) {
    var bmp by remember(id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(id) { bmp = StickerCache.load(token, id) }
    Box(modifier.size(size)) {
        val b = bmp
        if (b != null) {
            Image(bitmap = b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
        }
    }
}

/**
 * Your stickers. Tap one to send it. Import your WhatsApp stickers from the phone storage
 * (they are .webp files), or in delete mode tap one to remove it.
 */
@Composable
fun StickerPicker(net: Net, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ids by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var deleteMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        val r = net.get("/candy/stickers")
        loading = false
        if (r.ok) {
            val arr = r.body?.optJSONArray("stickers")
            val out = ArrayList<String>()
            if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("id")?.let { out.add(it) }
            ids = out
        } else {
            error = r.message(ctx)
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                busy = true
                error = ""
                var added = 0
                for (uri in uris) {
                    val bytes = withContext(Dispatchers.IO) {
                        try {
                            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (bytes == null || bytes.isEmpty() || bytes.size > STICKER_MAX_BYTES) continue
                    val body = JSONObject().put("sticker", true).put("image", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    val r = net.post("/candy/post", body)
                    if (r.ok) {
                        added++
                    } else {
                        error = r.message(ctx)
                        break
                    }
                }
                busy = false
                notice = ctx.getString(R.string.stk_imported, added)
                reload++
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_stickers)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!loading && ids.isEmpty()) Muted(stringResource(R.string.stk_empty))
                for (row in ids.chunked(4)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (id in row) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clickable {
                                        if (deleteMode) {
                                            scope.launch {
                                                val r = net.post("/candy/sticker/delete", JSONObject().put("id", id))
                                                if (r.ok) {
                                                    StickerCache.forget(id)
                                                    reload++
                                                } else {
                                                    error = r.message(ctx)
                                                }
                                            }
                                        } else {
                                            onPick(id)
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) { StickerImage(id, net.token, 64.dp) }
                        }
                        for (i in row.size until 4) Spacer(Modifier.weight(1f))
                    }
                }
                if (notice.isNotEmpty()) Text(notice, color = MaterialTheme.colorScheme.primary)
                ErrorText(error)
                OutlinedButton(
                    onClick = { importer.launch(arrayOf("image/webp", "image/png")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.stk_import)) }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.stk_delete))
                    Switch(checked = deleteMode, onCheckedChange = { deleteMode = it })
                }
                Muted(stringResource(R.string.stk_hint))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
