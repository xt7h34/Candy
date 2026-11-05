package com.candy

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
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

/** A pack of stickers you made, and what the sticker lists from the server look like. */
class StickerPack(val id: String, val name: String, val ids: List<String>)

class StickerLists(val ids: List<String>, val favs: Set<String>, val packs: List<StickerPack>)

fun JSONObject.toStickerLists(): StickerLists {
    val ids = ArrayList<String>()
    val favs = HashSet<String>()
    val arr = optJSONArray("stickers")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            ids.add(id)
            if (o.optBoolean("fav")) favs.add(id)
        }
    }
    val packs = ArrayList<StickerPack>()
    val pArr = optJSONArray("packs")
    if (pArr != null) {
        for (i in 0 until pArr.length()) {
            val o = pArr.optJSONObject(i) ?: continue
            val pIds = ArrayList<String>()
            val a = o.optJSONArray("ids")
            if (a != null) for (j in 0 until a.length()) pIds.add(a.optString(j))
            packs.add(StickerPack(o.optString("id"), o.optString("name"), pIds))
        }
    }
    return StickerLists(ids, favs, packs)
}

@Composable
private fun StickerTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
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
    var favs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var packs by remember { mutableStateOf<List<StickerPack>>(emptyList()) }
    var tab by remember { mutableStateOf("all") }
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
            val lists = r.body?.toStickerLists()
            if (lists != null) {
                ids = lists.ids
                favs = lists.favs
                packs = lists.packs
            }
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
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StickerTab("⭐", tab == "fav") { tab = "fav" }
                    StickerTab(stringResource(R.string.stk_tab_all), tab == "all") { tab = "all" }
                    for (p in packs) StickerTab(p.name, tab == p.id) { tab = p.id }
                }
                val shown = when (tab) {
                    "all" -> ids
                    "fav" -> ids.filter { it in favs }
                    else -> packs.firstOrNull { it.id == tab }?.ids ?: ids
                }
                if (!loading && shown.isEmpty()) Muted(stringResource(R.string.stk_empty))
                if (tab != "all" && tab != "fav") {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val r = net.post("/candy/sticker/pack/delete", JSONObject().put("pack", tab))
                                if (r.ok) {
                                    tab = "all"
                                    reload++
                                } else {
                                    error = r.message(ctx)
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.stk_delete_pack)) }
                }
                for (row in shown.chunked(4)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (id in row) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clickable {
                                        if (deleteMode) {
                                            scope.launch {
                                                val r = when {
                                                    tab == "fav" -> net.post("/candy/sticker/fav", JSONObject().put("id", id).put("on", false))
                                                    tab != "all" -> net.post("/candy/sticker/pack/remove", JSONObject().put("pack", tab).put("id", id))
                                                    else -> net.post("/candy/sticker/delete", JSONObject().put("id", id))
                                                }
                                                if (r.ok) {
                                                    if (tab == "all") StickerCache.forget(id)
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

/** Under a sticker you opened in a chat: save it, make it a favourite, or put it in one of your packs. */
@Composable
fun StickerSheetActions(net: Net, id: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<StickerLists?>(null) }
    var reload by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var choosingPack by remember { mutableStateOf(false) }

    LaunchedEffect(id, reload) {
        val r = net.get("/candy/stickers")
        if (r.ok) lists = r.body?.toStickerLists()
    }

    val l = lists
    val mine = l != null && id in l.ids
    val fav = l != null && id in l.favs

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (l != null && !mine) {
            TextButton(
                onClick = {
                    scope.launch {
                        val r = net.post("/candy/sticker/save", JSONObject().put("id", id))
                        if (r.ok) {
                            notice = ctx.getString(R.string.stk_saved)
                            error = ""
                            reload++
                        } else {
                            error = r.message(ctx)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("➕  " + stringResource(R.string.stk_save)) }
        }
        TextButton(
            onClick = {
                scope.launch {
                    val r = net.post("/candy/sticker/fav", JSONObject().put("id", id).put("on", !fav))
                    if (r.ok) {
                        error = ""
                        reload++
                    } else {
                        error = r.message(ctx)
                    }
                }
            },
            enabled = l != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("⭐  " + stringResource(if (fav) R.string.stk_fav_remove else R.string.stk_fav_add)) }
        TextButton(onClick = { choosingPack = true }, enabled = l != null, modifier = Modifier.fillMaxWidth()) {
            Text("📁  " + stringResource(R.string.stk_to_pack))
        }
        if (notice.isNotEmpty()) Text(notice, color = MaterialTheme.colorScheme.primary)
        ErrorText(error)
    }

    if (choosingPack && l != null) {
        var newName by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }

        fun addTo(packId: String) {
            scope.launch {
                busy = true
                val r = net.post("/candy/sticker/pack/add", JSONObject().put("pack", packId).put("id", id))
                busy = false
                if (r.ok) {
                    notice = ctx.getString(R.string.stk_saved)
                    error = ""
                    choosingPack = false
                    reload++
                } else {
                    error = r.message(ctx)
                    choosingPack = false
                }
            }
        }

        AlertDialog(
            onDismissRequest = { choosingPack = false },
            title = { Text(stringResource(R.string.stk_pack_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in l.packs) {
                        TextButton(onClick = { addTo(p.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(p.name + "  (" + p.ids.size + ")")
                        }
                    }
                    Field(stringResource(R.string.stk_new_pack), newName, { newName = it.take(20) })
                    TextButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                val made = net.post("/candy/sticker/pack", JSONObject().put("name", newName.trim()))
                                val newId = made.body?.optJSONObject("pack")?.optString("id").orEmpty()
                                busy = false
                                if (made.ok && newId.isNotEmpty()) addTo(newId) else error = made.message(ctx)
                            }
                        },
                        enabled = !busy && newName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.stk_create_pack)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingPack = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
