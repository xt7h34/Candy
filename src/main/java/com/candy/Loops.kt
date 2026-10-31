package com.candy

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

private const val LOOP_VIDEO_MAX = 30L * 1024 * 1024

private fun loopFile(c: Context, id: String) = File(c.cacheDir, "loop_$id.mp4")

/**
 * Loops: short videos, one per screen. Swipe up for the next one, tap to pause.
 * With [hash] it plays one person's loops, starting from [startId]. Videos are saved in the cache while you watch.
 */
@Composable
fun LoopsScreen(
    net: Net,
    hash: String?,
    startId: String?,
    refreshKey: Int,
    onBack: (() -> Unit)?,
    onOpenProfile: (String) -> Unit,
    onOpenComments: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var loops by remember { mutableStateOf<List<Post>>(emptyList()) }
    var next by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    suspend fun load(before: Long?) {
        loading = true
        val q = StringBuilder("/candy/loops?")
        if (hash != null) q.append("hash=").append(Uri.encode(bareHash(hash))).append('&')
        if (before != null) q.append("before=").append(before)
        val r = net.get(q.toString())
        loading = false
        if (r.ok) {
            val page = r.body?.optJSONArray("items").toPosts()
            loops = if (before == null) page else loops + page
            next = if (r.body?.isNull("next") == false) r.body?.optLong("next") else null
            error = ""
        } else {
            error = r.message(ctx)
        }
    }

    LaunchedEffect(hash, refreshKey) { load(null) }

    DisposableEffect(Unit) {
        onDispose { ctx.cacheDir.listFiles { f -> f.name.startsWith("loop_") }?.forEach { it.delete() } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (loops.isEmpty()) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (loading) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        error.ifEmpty { stringResource(R.string.loops_empty) },
                        color = if (error.isEmpty()) Color.White else MaterialTheme.colorScheme.error,
                    )
                }
            }
        } else {
            val start = remember { loops.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
            val pager = rememberPagerState(initialPage = start) { loops.size }

            // fetch more near the end, and keep only the videos next to the one on screen
            LaunchedEffect(pager.currentPage, loops.size) {
                val more = next
                if (more != null && !loading && pager.currentPage >= loops.size - 2) load(more)
                val keep = HashSet<String>()
                for (i in (pager.currentPage - 1)..(pager.currentPage + 1)) {
                    loops.getOrNull(i)?.let { keep.add("loop_${it.id}.mp4") }
                }
                ctx.cacheDir.listFiles { f -> f.name.startsWith("loop_") && f.name !in keep }?.forEach { it.delete() }
            }

            VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                LoopPage(
                    net = net,
                    loop = loops[page],
                    active = pager.currentPage == page,
                    near = abs(pager.currentPage - page) <= 1,
                    onOpenProfile = onOpenProfile,
                    onOpenComments = onOpenComments,
                )
            }
        }

        if (onBack != null) {
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).systemBarsPadding()) {
                Text("✕", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun LoopPage(
    net: Net,
    loop: Post,
    active: Boolean,
    near: Boolean,
    onOpenProfile: (String) -> Unit,
    onOpenComments: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var path by remember(loop.id) { mutableStateOf<String?>(null) }
    var poster by remember(loop.id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(loop.id) { mutableStateOf(false) }
    var playing by remember(loop.id) { mutableStateOf(true) }

    LaunchedEffect(loop.id) { poster = MediaCache.load(net.token, loop.id, false) }

    // download the video when it is on screen or next to it
    LaunchedEffect(loop.id, near) {
        if (near && path == null && !failed) {
            val file = loopFile(ctx, loop.id)
            if (file.exists() && file.length() > 0) {
                path = file.absolutePath
            } else if (CandyApi.download("/candy/media?id=" + Uri.encode(loop.id) + "&video=1", net.token, file, LOOP_VIDEO_MAX)) {
                path = file.absolutePath
            } else {
                failed = true
            }
        }
    }

    // count a view when it comes on screen
    LaunchedEffect(loop.id, active) {
        if (active) net.post("/candy/loop/view", JSONObject().put("id", loop.id))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val cover = poster
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val shownPath = path
        if (active && shownPath != null) {
            AndroidView(
                factory = { c -> VideoView(c) },
                update = { v ->
                    if (v.tag != shownPath) {
                        v.tag = shownPath
                        v.setVideoPath(shownPath)
                        v.setOnPreparedListener { mp ->
                            mp.isLooping = true
                            if (playing) v.start()
                        }
                    } else if (!playing) {
                        v.pause()
                    } else if (!v.isPlaying) {
                        v.start()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (cover == null && shownPath == null && !failed) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        // tap anywhere to pause or play
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { playing = !playing },
        )

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(0.72f).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.clickable { onOpenProfile(loop.ownerHash) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Avatar(loop.ownerHash, net.token, 36.dp)
                Text(
                    loop.ownerName.ifBlank { loop.ownerHash },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (loop.caption.isNotBlank()) {
                Text(loop.caption, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LikeButton(net, loop.id, loop.liked, loop.likes)
            TextButton(onClick = { onOpenComments(loop.id) }) { Text("💬 " + loop.comments) }
            Text(stringResource(R.string.loop_views, loop.views), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Someone's loops as a grid of squares with a play mark. */
@Composable
fun LoopsSection(net: Net, hash: String, refreshKey: Int, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loops by remember { mutableStateOf<List<Post>>(emptyList()) }
    var next by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }

    suspend fun load(before: Long?) {
        loading = true
        val q = "/candy/loops?hash=" + Uri.encode(bareHash(hash)) + (if (before != null) "&before=$before" else "")
        val r = net.get(q)
        loading = false
        if (r.ok) {
            val page = r.body?.optJSONArray("items").toPosts()
            loops = if (before == null) page else loops + page
            next = if (r.body?.isNull("next") == false) r.body?.optLong("next") else null
        }
    }

    LaunchedEffect(hash, refreshKey) {
        loops = emptyList()
        load(null)
    }

    if (loops.isEmpty() && !loading) return

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(stringResource(R.string.prof_loops))
        for (row in loops.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (l in row) {
                    Box(Modifier.weight(1f).clickable { onOpen(l.id) }) {
                        PostImage(l.id, net.token, true, 1f)
                        Text("▶", color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
                    }
                }
                for (i in row.size until 3) Spacer(Modifier.weight(1f))
            }
        }
        if (loading) Loading()
        val more = next
        if (more != null && !loading) {
            TextButton(onClick = { scope.launch { load(more) } }) { Text(stringResource(R.string.load_more)) }
        }
    }
}
