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

/** Bar heights that look like a voice but are only drawn from the message number. */
private fun barHeights(seed: Int, count: Int): List<Float> {
    var x = seed * 2654435761L + 12345L
    val out = ArrayList<Float>(count)
    for (i in 0 until count) {
        x = x * 6364136223846793005L + 1442695040888963407L
        val r = ((x ushr 33) % 1000L).toInt() / 1000f
        out.add(0.25f + 0.75f * r)
    }
    return out
}

@Composable
fun Waveform(heights: List<Float>, progress: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(28.dp)) {
        val n = heights.size
        if (n == 0) return@Canvas
        val gap = 3.dp.toPx()
        val barW = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = size.height * heights[i]
            val x = i * (barW + gap)
            val played = (i + 0.5f) / n <= progress
            drawRoundRect(
                color = if (played) color else color.copy(alpha = 0.4f),
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

/** A voice message in the chat: play button, the bars filling as it plays, and the time. */
@Composable
fun VoiceBubble(net: Net, peerHash: String, n: Int, ms: Long, color: Color) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = peerHash + "#" + n
    val playing = VoicePlayer.playingKey == key
    var loading by remember(key) { mutableStateOf(false) }
    var failed by remember(key) { mutableStateOf(false) }
    val progress = if (playing && VoicePlayer.durationMs > 0) VoicePlayer.positionMs.toFloat() / VoicePlayer.durationMs else 0f
    val heights = remember(n) { barHeights(n, 28) }

    LaunchedEffect(playing) {
        while (playing) {
            VoicePlayer.tick()
            delay(100)
        }
    }

    Row(
        Modifier.width(220.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = color.copy(alpha = 0.2f),
            modifier = Modifier.size(40.dp).clickable(enabled = !loading) {
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
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (loading) "…" else if (failed) "!" else if (playing) "⏸" else "▶", color = color)
            }
        }
        Waveform(heights, progress, color, Modifier.weight(1f))
        Text(
            formatMs(if (playing) VoicePlayer.positionMs.toLong() else ms),
            color = color,
            style = MaterialTheme.typography.labelSmall,
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
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onDelete) { Text("🗑", style = MaterialTheme.typography.titleLarge) }
            Text(
                formatMs(elapsedMs),
                style = MaterialTheme.typography.titleMedium,
                color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Waveform(bars, 1f, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            if (!limitReached) {
                TextButton(onClick = onTogglePause) {
                    Text(if (paused) "🎙" else "⏸", style = MaterialTheme.typography.titleLarge)
                }
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp).clickable(onClick = onSend),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("↑", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}
