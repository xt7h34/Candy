package com.candy

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

/** What the camera (or the gallery) gave us. [fromCamera] is false for a file picked from the gallery. */
class Picked(val uri: Uri, val isVideo: Boolean, val fromCamera: Boolean)

private const val DEST_TRACK = "track"
private const val DEST_STAGE = "stage"
private const val DEST_LOOP = "loop"
private const val DEST_FLARE = "flare"
private const val MAX_LOOP_MS = 30_000L
private const val MAX_RECORD_MS = 15_000L

private fun hasPermission(c: android.content.Context, p: String): Boolean =
    ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

/**
 * The camera. Tap the round button for a photo, hold it for a video (Stage only).
 * Under it you choose where it goes: Track or Stage. The square at the bottom left picks from the gallery.
 */
@Composable
fun CameraScreen(
    startDest: String,
    flareLocked: Boolean,
    onBack: () -> Unit,
    onMemories: () -> Unit,
    onPicked: (Picked, String) -> Unit,
) {
    val ctx = LocalContext.current
    val owner = ctx as LifecycleOwner
    val executor = remember { ContextCompat.getMainExecutor(ctx) }

    var hasCamera by remember { mutableStateOf(hasPermission(ctx, Manifest.permission.CAMERA)) }
    var hasAudio by remember { mutableStateOf(hasPermission(ctx, Manifest.permission.RECORD_AUDIO)) }
    var dest by remember { mutableStateOf(startDest) }
    var lens by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var message by remember { mutableStateOf("") }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }

    val previewView = remember {
        PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasCamera = result[Manifest.permission.CAMERA] == true || hasPermission(ctx, Manifest.permission.CAMERA)
        hasAudio = result[Manifest.permission.RECORD_AUDIO] == true || hasPermission(ctx, Manifest.permission.RECORD_AUDIO)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val isVideo = ctx.contentResolver.getType(uri).orEmpty().startsWith("video/")
            if (isVideo && dest == DEST_TRACK) {
                message = ctx.getString(R.string.cam_video_track)
            } else if (!isVideo && dest == DEST_LOOP) {
                message = ctx.getString(R.string.cam_loop_video)
            } else {
                onPicked(Picked(uri, isVideo, false), dest)
            }
        }
    }

    // ask for the camera the first time
    DisposableEffect(Unit) {
        if (!hasCamera) permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        onDispose { }
    }

    // start the camera, and again when the front/back lens changes
    DisposableEffect(hasCamera, lens) {
        val future = ProcessCameraProvider.getInstance(ctx)
        if (hasCamera) {
            future.addListener({
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    val image = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                        .build()
                    val video = VideoCapture.withOutput(recorder)
                    val selector = CameraSelector.Builder().requireLensFacing(lens).build()
                    provider.unbindAll()
                    try {
                        provider.bindToLifecycle(owner, selector, preview, image, video)
                        videoCapture = video
                    } catch (e: Exception) {
                        // some phones cannot run photo and video together: keep the photo only
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, selector, preview, image)
                        videoCapture = null
                    }
                    imageCapture = image
                    message = ""
                } catch (e: Exception) {
                    message = ctx.getString(R.string.cam_failed)
                }
            }, executor)
        }
        onDispose {
            runCatching { recording?.stop() }
            runCatching { if (future.isDone) future.get().unbindAll() }
        }
    }

    fun takePhoto() {
        val capture = imageCapture ?: return
        val file = File(ctx.cacheDir, "cap_${System.currentTimeMillis()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onPicked(Picked(Uri.fromFile(file), false, true), dest)
                }

                override fun onError(exception: ImageCaptureException) {
                    message = ctx.getString(R.string.cam_failed)
                }
            },
        )
    }

    fun startVideo() {
        val capture = videoCapture ?: return
        if (recording != null) return
        val file = File(ctx.cacheDir, "cap_${System.currentTimeMillis()}.mp4")
        var pending = capture.output.prepareRecording(
            ctx,
            FileOutputOptions.Builder(file).setDurationLimitMillis(if (dest == DEST_LOOP) MAX_LOOP_MS else MAX_RECORD_MS).build(),
        )
        if (hasAudio) pending = pending.withAudioEnabled()
        recording = pending.start(executor) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                if (!event.hasError()) onPicked(Picked(Uri.fromFile(file), true, true), dest) else file.delete()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            // no permission yet: say why we need it, and ask
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    Modifier.size(88.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                }
                Text(
                    stringResource(R.string.cam_permission),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = { permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) },
                    shape = CircleShape,
                    modifier = Modifier.height(48.dp),
                ) { Text(stringResource(R.string.cam_allow)) }
            }
        }

        // soft dark edges so the white buttons can always be seen
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(140.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(280.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))),
        )

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().systemBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))) {
                AppIconButton(Icons.Rounded.Close, null, onBack, tint = Color.White)
            }
            Surface(onClick = onMemories, shape = CircleShape, color = Color.Black.copy(alpha = 0.35f)) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.cam_memories), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
            Box(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))) {
                AppIconButton(
                    Icons.Rounded.FlipCameraAndroid,
                    null,
                    { lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK },
                    tint = Color.White,
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (message.isNotEmpty()) {
                Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.55f)) {
                    Text(
                        message,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                Text(
                    stringResource(R.string.cam_hint),
                    color = Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    // a Flare must be taken here, so there is no gallery for it
                    if (dest != DEST_FLARE) {
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.16f))
                                .border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                                .clickable { gallery.launch(arrayOf("image/*", "video/*")) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Image, contentDescription = null, tint = Color.White) }
                    }
                }

                // the shutter: a ring with a button in it that shrinks a little when pressed, and turns into a red square while it records
                var pressed by remember { mutableStateOf(false) }
                val press by animateFloatAsState(if (pressed) 0.9f else 1f, label = "shutter-press")
                val inner by animateDpAsState(if (recording != null) 34.dp else 66.dp, label = "shutter-size")
                val corner by animateDpAsState(if (recording != null) 10.dp else 33.dp, label = "shutter-corner")
                val ringColor = if (recording != null) Color(0xFFFF4D4D) else Color.White
                Box(
                    Modifier
                        .size(84.dp)
                        .graphicsLayer { scaleX = press; scaleY = press }
                        .border(4.dp, ringColor, CircleShape)
                        .pointerInput(dest, hasCamera) {
                            detectTapGestures(
                                onPress = {
                                    pressed = true
                                    tryAwaitRelease()
                                    pressed = false
                                    recording?.stop()
                                },
                                onTap = { if (dest == DEST_LOOP) message = ctx.getString(R.string.cam_loop_video) else takePhoto() },
                                onLongPress = { if (dest != DEST_TRACK) startVideo() },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(inner).clip(RoundedCornerShape(corner)).background(ringColor))
                }
                Box(Modifier.weight(1f))
            }

            // where it goes: Track, Story, Loop or Flare, in one pill with the chosen one lit
            val choices = if (flareLocked) {
                listOf(DEST_FLARE to R.string.cam_flare)
            } else {
                listOf(DEST_TRACK to R.string.cam_track, DEST_STAGE to R.string.cam_stage, DEST_LOOP to R.string.cam_loop, DEST_FLARE to R.string.cam_flare)
            }
            Row(
                Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for ((id, label) in choices) {
                    val selected = dest == id
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .then(if (selected) Modifier.background(CandyBrand.gradient()) else Modifier)
                            .clickable { dest = id }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(label),
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else Color(0xB3FFFFFF),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}
