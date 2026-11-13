package com.candy

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.WindowManager
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private const val FLARE_PHOTO_MS = 7000L
private const val FLARE_VIDEO_MAX = 30L * 1024 * 1024

/**
 * Opens the Flares one friend sent you. Each one is seen once: when you leave it, it is deleted.
 * Screenshots are blocked while it is on screen.
 */
@Composable
fun FlareViewer(net: Net, hash: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<FlareItem>>(emptyList()) }
    var index by remember { mutableStateOf(0) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var videoPath by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }

    // no screenshots of a Flare
    DisposableEffect(Unit) {
        val window = (ctx as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            ctx.cacheDir.listFiles { f -> f.name.startsWith("flare_") }?.forEach { it.delete() }
        }
    }

    LaunchedEffect(hash) {
        val r = net.get("/candy/flares")
        if (r.ok) {
            items = r.body?.optJSONArray("inbox").toFlareItems()
                .filter { bareHash(it.fromHash).equals(bareHash(hash), ignoreCase = true) }
            if (items.isEmpty()) onBack()
        } else {
            error = r.message(ctx)
        }
    }

    val item = items.getOrNull(index)

    // the current one is done: tell the bot (it deletes the file) and go on
    fun finish() {
        val cur = item ?: return
        scope.launch { net.post("/candy/flare/open", JSONObject().put("id", cur.id)) }
        if (index < items.lastIndex) index++ else onBack()
    }

    LaunchedEffect(item?.id) {
        val cur = item ?: return@LaunchedEffect
        bmp = null
        videoPath = null
        val pic = CandyApi.fetchBytes("/candy/flare/media?id=" + Uri.encode(cur.id), net.token)
        val bytes = pic.bytes
        if (pic.status in 200..299 && bytes != null) {
            bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        }
        if (cur.kind == "video") {
            val dest = File(ctx.cacheDir, "flare_${cur.id}.mp4")
            val ok = CandyApi.download("/candy/flare/media?id=" + Uri.encode(cur.id) + "&video=1", net.token, dest, FLARE_VIDEO_MAX)
            if (ok) videoPath = dest.absolutePath
        }
        if (bmp == null && videoPath == null) error = ctx.getString(R.string.err_generic)
    }

    // photos close by themselves after a few seconds, videos when they end
    LaunchedEffect(item?.id, bmp != null) {
        val cur = item ?: return@LaunchedEffect
        if (cur.kind != "video" && bmp != null) {
            delay(FLARE_PHOTO_MS)
            finish()
        }
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
                            v.setOnCompletionListener { finish() }
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
                for (o in item.overlays) OverlayText(o, Modifier.offset(x = maxWidth * o.x, y = maxHeight * o.y))
            }
        }
        if (error.isNotEmpty()) {
            Text(error, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }

        // tap anywhere to move on
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (item != null) finish() else onBack() },
        )

        if (item != null) {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Avatar(item.fromHash, net.token, 32.dp)
                Text(
                    item.fromName.ifBlank { item.fromHash },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${index + 1}/${items.size}", color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { finish() }) { Text("✕", color = Color.White) }
            }
        }
    }
}
