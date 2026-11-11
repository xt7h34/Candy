package com.candy

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The longest a voice message can be: 5 minutes. */
const val VOICE_MAX_MS = 5 * 60 * 1000L

fun formatMs(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return (s / 60).toString() + ":" + (s % 60).toString().padStart(2, '0')
}

/**
 * Records a voice message to an .m4a file: small (about 4 KB a second), mono, AAC.
 * It can be paused and resumed, and the time shown is only the time actually recorded.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var accumulated = 0L
    var paused = false
        private set

    val elapsedMs: Long
        get() = accumulated + (if (recorder != null && !paused) System.currentTimeMillis() - startedAt else 0L)

    /** How loud it is right now, 0 to 32767. */
    val amplitude: Int
        get() = try { recorder?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }

    fun start(): Boolean {
        val out = File(context.cacheDir, "voice_" + System.currentTimeMillis() + ".m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(32000)
            r.setAudioSamplingRate(22050)
            r.setAudioChannels(1)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = System.currentTimeMillis()
            accumulated = 0L
            paused = false
            true
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) { }
            out.delete()
            false
        }
    }

    fun pause() {
        val r = recorder ?: return
        if (paused) return
        try {
            r.pause()
            accumulated += System.currentTimeMillis() - startedAt
            paused = true
        } catch (_: Exception) { }
    }

    fun resume() {
        val r = recorder ?: return
        if (!paused) return
        try {
            r.resume()
            startedAt = System.currentTimeMillis()
            paused = false
        } catch (_: Exception) { }
    }

    /** Closes the file and gives it back with how long it is. Null if nothing usable came out. */
    fun finish(): Pair<File, Long>? {
        val r = recorder ?: return null
        val ms = elapsedMs.coerceAtMost(VOICE_MAX_MS)
        recorder = null
        val out = file
        return try {
            r.stop()
            r.release()
            if (out != null) out to ms else null
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) { }
            out?.delete()
            null
        }
    }

    fun cancel() {
        val r = recorder
        recorder = null
        try { r?.stop() } catch (_: Exception) { }
        try { r?.release() } catch (_: Exception) { }
        file?.delete()
        file = null
    }
}

/** Plays one voice message at a time. The downloaded copy is deleted as soon as it stops, so nothing stays on the phone. */
object VoicePlayer {
    var playingKey by mutableStateOf<String?>(null)
        private set
    var positionMs by mutableStateOf(0)
        private set
    var durationMs by mutableStateOf(0)
        private set
    private var player: MediaPlayer? = null
    private var current: File? = null

    fun toggle(key: String, file: File) {
        if (playingKey == key) {
            stop()
            return
        }
        stop()
        val p = MediaPlayer()
        try {
            p.setDataSource(file.absolutePath)
            p.prepare()
            durationMs = p.duration
            positionMs = 0
            p.setOnCompletionListener { stop() }
            p.start()
            player = p
            current = file
            playingKey = key
        } catch (e: Exception) {
            try { p.release() } catch (_: Exception) { }
            file.delete()
        }
    }

    /** Jumps to a point in the message being played, 0 to 1. */
    fun seekTo(fraction: Float) {
        val p = player ?: return
        val to = (fraction.coerceIn(0f, 1f) * durationMs).toInt()
        try { p.seekTo(to) } catch (_: Exception) { return }
        positionMs = to
    }

    fun tick() {
        val p = player ?: return
        positionMs = try { p.currentPosition } catch (e: Exception) { 0 }
    }

    fun stop() {
        try { player?.stop() } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }
        player = null
        current?.delete()
        current = null
        playingKey = null
        positionMs = 0
    }
}

/** Bar heights that look like a voice (smooth bursts with quiet gaps) but are only drawn from the message number. */
private fun barHeights(seed: Int, count: Int): List<Float> {
    var x = seed * 2654435761L + 12345L
    val out = ArrayList<Float>(count)
    var prev = 0.5f
    for (i in 0 until count) {
        x = x * 6364136223846793005L + 1442695040888963407L
        val r = ((x ushr 33) % 1000L).toInt() / 1000f
        val mixed = prev * 0.4f + r * 0.6f
        prev = mixed
        val edge = (minOf(i, count - 1 - i) / 3f).coerceIn(0f, 1f)
        val h = (0.12f + 1.5f * mixed * mixed) * (0.6f + 0.4f * edge)
        out.add(h.coerceIn(0.14f, 1f))
    }
    return out
}

/** Thin rounded bars. Played bars are solid, the rest are dimmed. */
@Composable
fun Waveform(
    heights: List<Float>,
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 30.dp,
    maxBar: Dp = 3.dp,
) {
    Canvas(modifier.height(height)) {
        val n = heights.size
        if (n == 0) return@Canvas
        val pitch = size.width / n
        val barW = minOf(pitch * 0.56f, maxBar.toPx())
        for (i in 0 until n) {
            val h = maxOf(size.height * heights[i], barW)
            val cx = pitch * i + pitch / 2f
            val played = (i + 0.5f) / n <= progress
            drawRoundRect(
                color = if (played) color else color.copy(alpha = 0.38f),
                topLeft = Offset(cx - barW / 2f, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

// ───── small vector icons, so nothing depends on emoji or an icon library ─────

private fun DrawScope.drawPlayIcon(color: Color, blend: BlendMode = BlendMode.SrcOver) {
    val s = size.minDimension
    val cx = size.width / 2f + s * 0.03f
    val cy = size.height / 2f
    val r = s * 0.17f
    val p = Path().apply {
        moveTo(cx - r * 0.8f, cy - r * 1.25f)
        lineTo(cx - r * 0.8f, cy + r * 1.25f)
        lineTo(cx + r * 1.3f, cy)
        close()
    }
    drawPath(p, color, blendMode = blend)
    drawPath(p, color, style = Stroke(width = s * 0.07f, join = StrokeJoin.Round), blendMode = blend)
}

private fun DrawScope.drawPauseIcon(color: Color, blend: BlendMode = BlendMode.SrcOver) {
    val s = size.minDimension
    val cx = size.width / 2f
    val cy = size.height / 2f
    val bw = s * 0.1f
    val bh = s * 0.36f
    val gap = s * 0.1f
    drawRoundRect(color, Offset(cx - gap / 2f - bw, cy - bh / 2f), Size(bw, bh), CornerRadius(bw / 2f), blendMode = blend)
    drawRoundRect(color, Offset(cx + gap / 2f, cy - bh / 2f), Size(bw, bh), CornerRadius(bw / 2f), blendMode = blend)
}

private fun DrawScope.drawBangIcon(color: Color, blend: BlendMode = BlendMode.SrcOver) {
    val s = size.minDimension
    val cx = size.width / 2f
    val cy = size.height / 2f
    drawLine(color, Offset(cx, cy - s * 0.18f), Offset(cx, cy + s * 0.03f), strokeWidth = s * 0.09f, cap = StrokeCap.Round, blendMode = blend)
    drawCircle(color, radius = s * 0.05f, center = Offset(cx, cy + s * 0.17f), blendMode = blend)
}

private fun DrawScope.drawArrowUpIcon(color: Color) {
    val s = size.minDimension
    val cx = size.width / 2f
    val cy = size.height / 2f
    val w = s * 0.075f
    drawLine(color, Offset(cx, cy + s * 0.18f), Offset(cx, cy - s * 0.17f), strokeWidth = w, cap = StrokeCap.Round)
    drawLine(color, Offset(cx - s * 0.15f, cy - s * 0.02f), Offset(cx, cy - s * 0.17f), strokeWidth = w, cap = StrokeCap.Round)
    drawLine(color, Offset(cx + s * 0.15f, cy - s * 0.02f), Offset(cx, cy - s * 0.17f), strokeWidth = w, cap = StrokeCap.Round)
}

private fun DrawScope.drawTrashIcon(color: Color) {
    val s = size.minDimension
    val cx = size.width / 2f
    val cy = size.height / 2f
    val w = s * 0.06f
    drawLine(color, Offset(cx - s * 0.2f, cy - s * 0.14f), Offset(cx + s * 0.2f, cy - s * 0.14f), strokeWidth = w, cap = StrokeCap.Round)
    drawLine(color, Offset(cx - s * 0.07f, cy - s * 0.22f), Offset(cx + s * 0.07f, cy - s * 0.22f), strokeWidth = w, cap = StrokeCap.Round)
    val body = Path().apply {
        moveTo(cx - s * 0.15f, cy - s * 0.08f)
        lineTo(cx - s * 0.12f, cy + s * 0.22f)
        lineTo(cx + s * 0.12f, cy + s * 0.22f)
        lineTo(cx + s * 0.15f, cy - s * 0.08f)
    }
    drawPath(body, color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A voice message in the chat: a soft glass pill with a round play button, thin bars you can tap or drag to seek, and the time. */
@Composable
fun VoiceBubble(net: Net, peerHash: String, n: Int, ms: Long, color: Color) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = peerHash + "#" + n
    val playing = VoicePlayer.playingKey == key
    var loading by remember(key) { mutableStateOf(false) }
    var failed by remember(key) { mutableStateOf(false) }
    val rawProgress = if (playing && VoicePlayer.durationMs > 0) VoicePlayer.positionMs.toFloat() / VoicePlayer.durationMs else 0f
    val progress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = if (playing) tween(120, easing = LinearEasing) else snap(),
        label = "voiceProgress",
    )
    val heights = remember(n) { barHeights(n, 28) }
    val pill = RoundedCornerShape(28.dp)

    LaunchedEffect(playing) {
        while (playing) {
            VoicePlayer.tick()
            delay(80)
        }
    }

    Row(
        Modifier
            .width(250.dp)
            .clip(pill)
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.22f), pill)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .clickable(enabled = !loading) {
                    scope.launch {
                        val safe = peerHash.filter { it.isLetterOrDigit() }
                        val f = File(ctx.cacheDir, "vm_" + safe + "_" + n + ".m4a")
                        if (!f.exists()) {
                            loading = true
                            val r = CandyApi.fetchBytes("/candy/dm/voice?hash=" + Uri.encode(bareHash(peerHash)) + "&n=" + n, net.token)
                            val bytes = r.bytes
                            if (r.status in 200..299 && bytes != null && bytes.isNotEmpty()) {
                                withContext(Dispatchers.IO) { f.writeBytes(bytes) }
                                failed = false
                            } else {
                                failed = true
                            }
                            loading = false
                        }
                        if (f.exists()) VoicePlayer.toggle(key, f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // The icon is cut out of the circle, so it takes whatever colour the bubble behind it has.
            Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
                drawCircle(if (loading) color.copy(alpha = 0.25f) else color)
                when {
                    loading -> Unit
                    failed -> drawBangIcon(Color.Black, BlendMode.Clear)
                    playing -> drawPauseIcon(Color.Black, BlendMode.Clear)
                    else -> drawPlayIcon(Color.Black, BlendMode.Clear)
                }
            }
            if (loading) {
                CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
            }
        }
        Waveform(
            heights, progress, color,
            Modifier
                .weight(1f)
                .pointerInput(playing) {
                    detectTapGestures { o -> if (playing && size.width > 0) VoicePlayer.seekTo(o.x / size.width) }
                }
                .pointerInput(playing) {
                    detectHorizontalDragGestures { change, _ ->
                        if (playing && size.width > 0) {
                            change.consume()
                            VoicePlayer.seekTo(change.position.x / size.width)
                        }
                    }
                },
        )
        Text(
            formatMs(if (playing) VoicePlayer.positionMs.toLong() else ms),
            color = color,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.widthIn(min = 34.dp).padding(end = 6.dp),
        )
    }
}

/** What replaces the message box while you record: delete, time, the bars moving, pause or continue, send. */
@Composable
fun RecordingBar(
    elapsedMs: Long,
    amps: List<Float>,
    paused: Boolean,
    limitReached: Boolean,
    onDelete: () -> Unit,
    onTogglePause: () -> Unit,
    onSend: () -> Unit,
) {
    val bars = if (amps.isEmpty()) listOf(0.15f) else amps.map { 0.15f + 0.85f * it }
    val pill = RoundedCornerShape(32.dp)
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val error = MaterialTheme.colorScheme.error
    Surface(
        shape = pill,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().border(1.dp, primary.copy(alpha = 0.22f), pill),
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) { drawTrashIcon(onSurface.copy(alpha = 0.8f)) }
            }
            Text(
                formatMs(elapsedMs),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else error,
            )
            Waveform(bars, 1f, primary, Modifier.weight(1f))
            if (!limitReached) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onTogglePause), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        if (paused) {
                            drawCircle(error, radius = size.minDimension * 0.14f)
                            drawCircle(error.copy(alpha = 0.35f), radius = size.minDimension * 0.24f, style = Stroke(width = size.minDimension * 0.045f))
                        } else {
                            drawPauseIcon(onSurface.copy(alpha = 0.85f))
                        }
                    }
                }
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(primary).clickable(onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                val arrow = MaterialTheme.colorScheme.onPrimary
                Canvas(Modifier.fillMaxSize()) { drawArrowUpIcon(arrow) }
            }
        }
    }
}
