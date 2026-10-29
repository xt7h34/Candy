package com.candy

import android.graphics.Bitmap
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

private const val STORY_MS = 5000L
private const val STAGE_VIDEO_MAX = 30L * 1024 * 1024

/** The row of circles at the top of the feed. The picture inside each circle is the stage itself. */
@Composable
fun StoriesRow(token: String, groups: List<StoryGroup>, onOpen: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(groups) { g ->
            Column(
                Modifier.width(72.dp).clickable { onOpen(g.ownerHash) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val ring = if (g.allSeen) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary
                Box(
                    Modifier
                        .size(68.dp)
                        .border(3.dp, ring, CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape),
                ) {
                    PostImage(g.items.last().id, token, true, 1f)
                }
                Text(
                    if (g.mine) stringResource(R.string.nav_me) else g.ownerName.ifBlank { g.ownerHash },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Full-screen stage: tap the right side for the next one, the left for the previous one. Photos last 5 seconds; videos play through. */
@Composable
fun StoryViewer(net: Net, hash: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var group by remember { mutableStateOf<StoryGroup?>(null) }
    var index by remember { mutableStateOf(0) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var videoPath by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var viewers by remember { mutableStateOf<List<Member>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }

    LaunchedEffect(hash) {
        val r = net.get("/candy/stories")
        if (r.ok) {
            val found = r.body?.optJSONArray("groups").toStoryGroups()
                .firstOrNull { bareHash(it.ownerHash).equals(bareHash(hash), ignoreCase = true) }
            if (found == null) {
                onBack()
            } else {
                group = found
                val firstUnseen = found.items.indexOfFirst { !it.seen }
                index = if (firstUnseen >= 0) firstUnseen else 0
            }
        } else {
            error = r.message(ctx)
        }
    }

    // stage videos are saved in the cache while you watch, and removed when you leave
    DisposableEffect(Unit) {
        onDispose { ctx.cacheDir.listFiles { f -> f.name.startsWith("stage_") }?.forEach { it.delete() } }
    }

    val g = group
    val item = g?.items?.getOrNull(index)

    fun next() {
        if (g != null && index < g.items.lastIndex) index++ else onBack()
    }

    // load the picture (or the video), and tell the bot it was seen (not for your own)
    LaunchedEffect(item?.id) {
        val current = item ?: return@LaunchedEffect
        bmp = null
        videoPath = null
        if (current.kind == "video") {
            bmp = MediaCache.load(net.token, current.id, true)
            val dest = File(ctx.cacheDir, "stage_${current.id}.mp4")
            val ok = CandyApi.download("/candy/media?id=" + Uri.encode(current.id) + "&video=1", net.token, dest, STAGE_VIDEO_MAX)
            if (ok) videoPath = dest.absolutePath else error = ctx.getString(R.string.err_generic)
        } else {
            bmp = MediaCache.load(net.token, current.id, false)
        }
        if (g != null && !g.mine) net.post("/candy/story/seen", JSONObject().put("id", current.id))
    }

    // photos move on by themselves after a few seconds (videos move on when they end)
    LaunchedEffect(item?.id, bmp != null, paused) {
        if (item == null || item.kind == "video" || bmp == null || paused) return@LaunchedEffect
        delay(STORY_MS)
        next()
    }

    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val shown = bmp
            val path = videoPath
            if (item != null && item.kind == "video" && path != null) {
                AndroidView(
                    factory = { c -> VideoView(c) },
                    update = { v ->
                        if (v.tag != path) {
                            v.tag = path
                            v.setVideoPath(path)
                            v.setOnPreparedListener { mp -> mp.isLooping = false; v.start() }
                            v.setOnCompletionListener { next() }
                        } else if (paused) {
                            v.pause()
                        } else if (!v.isPlaying) {
                            v.start()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (error.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            if (item != null) {
                for (o in item.overlays) {
                    OverlayText(o, Modifier.offset(x = maxWidth * o.x, y = maxHeight * o.y))
                }
            }
        }
        if (error.isNotEmpty()) {
            Text(error, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }

        if (g != null) {
            Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { if (index > 0) index-- },
                )
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { next() },
                )
            }

            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in g.items.indices) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (i <= index) Color.White else Color(0x55FFFFFF)),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Avatar(g.ownerHash, net.token, 32.dp)
                    Text(
                        g.ownerName.ifBlank { g.ownerHash },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onBack) { Text("✕", color = Color.White) }
                }
            }

            if (g.mine && item != null) {
                Row(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                    TextButton(
                        onClick = {
                            paused = true
                            scope.launch {
                                val r = net.get("/candy/story/viewers?id=" + Uri.encode(item.id))
                                val arr = r.body?.optJSONArray("viewers")
                                val people = ArrayList<Member>()
                                if (r.ok && arr != null) {
                                    for (i in 0 until arr.length()) {
                                        val v = arr.optJSONObject(i) ?: continue
                                        people.add(Member(v.optString("name"), v.optString("hash")))
                                    }
                                }
                                viewers = people
                            }
                        },
                    ) { Text("👁 " + item.views, color = Color.White) }
                    TextButton(onClick = { paused = true; confirmDelete = true }) { Text("🗑", color = Color.White) }
                }
            }
        }
    }

    val shownViewers = viewers
    if (shownViewers != null) {
        AlertDialog(
            onDismissRequest = { viewers = null; paused = false },
            title = { Text(stringResource(R.string.story_viewers_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shownViewers.isEmpty()) Text(stringResource(R.string.story_no_viewers))
                    for (v in shownViewers) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Avatar(v.hash, net.token, 32.dp)
                            Column {
                                Text(v.name.ifBlank { v.hash })
                                Muted(v.hash)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewers = null; paused = false }) { Text(stringResource(R.string.close)) } },
        )
    }

    if (confirmDelete && item != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false; paused = false },
            text = { Text(stringResource(R.string.story_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            val r = net.post("/candy/story/delete", JSONObject().put("id", item.id))
                            if (r.ok) onBack() else { error = r.message(ctx); paused = false }
                        }
                    },
                ) { Text(stringResource(R.string.post_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false; paused = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
