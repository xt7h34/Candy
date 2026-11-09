package com.candy

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

private const val VIDEO_MAX_BYTES = 25L * 1024 * 1024
private const val VIDEO_MAX_MS = 30_000L

val OVERLAY_COLORS = longArrayOf(
    0xFFFFFFFF, 0xFF000000, 0xFFE95F6F, 0xFFFFD54F, 0xFF64B5F6, 0xFF81C784, 0xFFFFB74D, 0xFFBA68C8,
)
val OVERLAY_SIZES = intArrayOf(18, 26, 38)

/** Text written on top of a photo or video. Shown the same way in the editor and in the stage viewer. */
@Composable
fun OverlayText(o: Overlay, modifier: Modifier = Modifier) {
    Text(
        o.t,
        color = Color(OVERLAY_COLORS[o.c.coerceIn(0, OVERLAY_COLORS.size - 1)]),
        fontSize = OVERLAY_SIZES[o.s.coerceIn(0, OVERLAY_SIZES.size - 1)].sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

fun List<Overlay>.toJson(): JSONArray {
    val arr = JSONArray()
    for (o in this) arr.put(JSONObject().put("t", o.t).put("x", o.x.toDouble()).put("y", o.y.toDouble()).put("c", o.c).put("s", o.s))
    return arr
}

private fun videoLooksOk(c: android.content.Context, uri: Uri): Boolean = try {
    val size = c.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    val mmr = MediaMetadataRetriever()
    try {
        mmr.setDataSource(c, uri)
        val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        size in 1..VIDEO_MAX_BYTES && ms in 1..(VIDEO_MAX_MS + 500)
    } finally {
        mmr.release()
    }
} catch (e: Throwable) {
    false
}

/** The first frame of the video as a small JPEG, used as its cover. */
private fun videoCover(c: android.content.Context, uri: Uri): ByteArray? = try {
    val mmr = MediaMetadataRetriever()
    try {
        mmr.setDataSource(c, uri)
        val frame = mmr.getFrameAtTime(0)
        if (frame == null) {
            null
        } else {
            val longest = maxOf(frame.width, frame.height)
            val scaled = if (longest > 720) {
                val k = 720f / longest
                Bitmap.createScaledBitmap(frame, maxOf(1, (frame.width * k).toInt()), maxOf(1, (frame.height * k).toInt()), true)
            } else {
                frame
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
    } finally {
        mmr.release()
    }
} catch (e: Throwable) {
    null
}

/**
 * Edit a photo or video before it is sent. [mode] is "stage", "loop" or "flare".
 * For a Stage or a Flare you can write text on it, move the text with your finger and choose its
 * colour and size (kept as text, so it also works on videos). A Loop gets a caption instead.
 * A Flare goes to friends only (people who follow each other with you), and must come from the camera.
 * The disk button saves it in Memories, on this phone.
 */
@Composable
fun StageEditorScreen(net: Net, picked: Picked, mode: String, presetTo: String?, onBack: () -> Unit, onPosted: () -> Unit) {
    val loop = mode == "loop"
    val flare = mode == "flare"
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val overlays = remember { mutableStateListOf<Overlay>() }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var editing by remember { mutableStateOf(-1) }       // -1 none, -2 a new text, otherwise the index
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var friends by remember { mutableStateOf<List<CUser>>(emptyList()) }
    var friendsLoaded by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }

    LaunchedEffect(picked.uri) {
        if (!picked.isVideo) {
            photo = withContext(Dispatchers.IO) {
                val bytes = PostImagePrep.prepare(ctx, picked.uri)
                if (bytes == null) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
    }

    // a Flare goes to friends: people you follow who follow you back
    LaunchedEffect(flare, presetTo) {
        if (flare) {
            if (presetTo != null) {
                selected.add(presetTo)
            } else {
                val r = net.get("/candy/following")
                if (r.ok) friends = r.body?.optJSONArray("users").toUsers().filter { it.followsMe }
                friendsLoaded = true
            }
        }
    }

    fun share() {
        scope.launch {
            busy = true
            error = ""
            progress = 0f
            if (flare && !picked.fromCamera) {
                busy = false
                error = ctx.getString(R.string.flare_camera_only)
                return@launch
            }
            if (flare && selected.isEmpty()) {
                busy = false
                error = ctx.getString(R.string.flare_pick)
                return@launch
            }
            if (loop && !picked.isVideo) {
                busy = false
                error = ctx.getString(R.string.cam_loop_video)
                return@launch
            }
            val body = JSONObject()
            if (loop) {
                body.put("loop", true).put("caption", caption.trim())
            } else if (flare) {
                body.put("flare", true).put("to", JSONArray(selected.map { bareHash(it) })).put("overlays", overlays.toList().toJson())
            } else {
                body.put("story", true).put("overlays", overlays.toList().toJson())
            }
            if (picked.isVideo) {
                val ok = withContext(Dispatchers.IO) { videoLooksOk(ctx, picked.uri) }
                if (!ok) {
                    busy = false
                    error = ctx.getString(R.string.stage_video_big)
                    return@launch
                }
                val cover = withContext(Dispatchers.IO) { videoCover(ctx, picked.uri) }
                val length = withContext(Dispatchers.IO) { ctx.contentResolver.openAssetFileDescriptor(picked.uri, "r")?.use { it.length } ?: 0L }
                val input = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(picked.uri) }
                if (cover == null || input == null || length <= 0) {
                    busy = false
                    error = ctx.getString(R.string.avatar_unreadable)
                    return@launch
                }
                val up = input.use { CandyApi.uploadVideo(net.token, it, length) { p -> progress = p } }
                val id = up.body?.optString("id").orEmpty()
                if (!up.ok || id.isEmpty()) {
                    busy = false
                    error = up.message(ctx)
                    return@launch
                }
                body.put("video", id).put("image", Base64.encodeToString(cover, Base64.NO_WRAP))
            } else {
                val jpeg = withContext(Dispatchers.IO) { PostImagePrep.prepare(ctx, picked.uri) }
                if (jpeg == null) {
                    busy = false
                    error = ctx.getString(R.string.avatar_unreadable)
                    return@launch
                }
                body.put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            }
            val r = net.post("/candy/post", body)
            busy = false
            if (r.ok) onPosted() else error = r.message(ctx)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()

            if (picked.isVideo) {
                AndroidView(
                    factory = { c ->
                        VideoView(c).apply {
                            setVideoURI(picked.uri)
                            setOnPreparedListener { mp ->
                                mp.isLooping = true
                                start()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val shown = photo
                if (shown != null) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            overlays.forEachIndexed { index, o ->
                OverlayText(
                    o,
                    Modifier
                        .offset(x = maxWidth * o.x, y = maxHeight * o.y)
                        .pointerInput(index) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val current = overlays.getOrNull(index) ?: return@detectDragGestures
                                overlays[index] = current.copy(
                                    x = (current.x + drag.x / widthPx).coerceIn(0f, 0.95f),
                                    y = (current.y + drag.y / heightPx).coerceIn(0f, 0.95f),
                                )
                            }
                        }
                        .clickable { editing = index },
                )
            }
        }

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack, enabled = !busy) { Text("✕", color = Color.White, style = MaterialTheme.typography.titleLarge) }
            Row {
                TextButton(
                    onClick = {
                        scope.launch {
                            saved = withContext(Dispatchers.IO) { MemoriesStore.add(ctx, picked) }
                        }
                    },
                    enabled = !busy && !saved,
                ) { Text(if (saved) "✅" else "💾", style = MaterialTheme.typography.titleLarge) }
                if (!loop) {
                    TextButton(onClick = { editing = -2 }, enabled = !busy && overlays.size < 8) {
                        Text("Aa", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (saved) Text(stringResource(R.string.mem_saved), color = Color.White, style = MaterialTheme.typography.labelMedium)
            if (busy && picked.isVideo) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.stage_uploading, (progress * 100).toInt()), color = Color.White)
            } else if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (error.isNotEmpty()) Text(error, color = Color(0xFFFFB4AB))
            if (loop) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(8.dp),
                ) {
                    Field(stringResource(R.string.loop_caption_hint), caption, { caption = it.take(300) }, singleLine = false)
                }
            }
            if (flare && presetTo == null) {
                Text(stringResource(R.string.flare_to), color = Color.White, style = MaterialTheme.typography.labelLarge)
                if (friendsLoaded && friends.isEmpty()) {
                    Text(stringResource(R.string.flare_friends_empty), color = Color(0xCCFFFFFF))
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(friends) { u ->
                        val on = selected.contains(u.hash)
                        Column(
                            Modifier.clickable { if (on) selected.remove(u.hash) else if (selected.size < 5) selected.add(u.hash) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else Color(0x66FFFFFF), CircleShape).padding(3.dp)) {
                                Avatar(u.hash, net.token, 44.dp)
                            }
                            Text(u.name.ifBlank { u.hash }, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
            }
            Button(onClick = { share() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (loop) R.string.loop_share else if (flare) R.string.flare_send else R.string.stage_share))
            }
        }
    }

    if (editing != -1) {
        val existing = if (editing >= 0) overlays.getOrNull(editing) else null
        TextDialog(
            initial = existing ?: Overlay("", 0.2f, 0.4f, 0, 1),
            canDelete = existing != null,
            onSave = { o ->
                if (o.t.isNotBlank()) {
                    if (editing >= 0 && editing < overlays.size) overlays[editing] = o else overlays.add(o)
                }
                editing = -1
            },
            onDelete = {
                if (editing >= 0 && editing < overlays.size) overlays.removeAt(editing)
                editing = -1
            },
            onDismiss = { editing = -1 },
        )
    }
}

@Composable
private fun TextDialog(initial: Overlay, canDelete: Boolean, onSave: (Overlay) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial.t) }
    var color by remember { mutableStateOf(initial.c) }
    var size by remember { mutableStateOf(initial.s) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stage_add_text)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(stringResource(R.string.stage_text_hint), text, { text = it.take(80) }, singleLine = false)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in OVERLAY_COLORS.indices) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(OVERLAY_COLORS[i]))
                                .border(if (i == color) 3.dp else 1.dp, if (i == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { color = i },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in OVERLAY_SIZES.indices) {
                        OutlinedButton(onClick = { size = i }) {
                            Text("A", fontSize = (14 + i * 6).sp, fontWeight = if (i == size) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(initial.copy(t = text.trim(), c = color, s = size)) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                if (canDelete) TextButton(onClick = onDelete) { Text(stringResource(R.string.post_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
