package com.candy

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Makes a video lighter before it is sent: at most 1280 on its long side (720p), H.264 at about 2.8 Mbit/s. A phone video of
 * 30 seconds goes from tens of megabytes to a few, so it uploads faster, loads faster for everyone, and weighs less on the bot.
 */
@OptIn(UnstableApi::class)
object VideoPrep {
    private const val LONG_SIDE = 1280
    private const val BITRATE = 2_800_000
    private const val ALREADY_SMALL_BYTES = 6L * 1024 * 1024

    private class Info(val w: Int, val h: Int, val bytes: Long)

    private fun probe(ctx: Context, uri: Uri): Info? = try {
        val bytes = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            // as it is shown: a phone video held upright is stored sideways with a rotation flag
            if (w > 0 && h > 0) (if (rotation == 90 || rotation == 270) Info(h, w, bytes) else Info(w, h, bytes)) else null
        } finally {
            mmr.release()
        }
    } catch (e: Throwable) {
        null
    }

    private fun even(v: Float): Int = maxOf(2, (v.toInt() / 2) * 2)

    /**
     * A lighter copy of the video in the cache folder, or null when the original should be sent as it is: it is already small,
     * or it could not be made lighter (the caller then falls back to the original). The caller deletes the file after sending.
     */
    suspend fun compress(ctx: Context, uri: Uri): File? {
        val info = withContext(Dispatchers.IO) { probe(ctx, uri) } ?: return null
        val longest = maxOf(info.w, info.h)
        if (info.bytes in 1..ALREADY_SMALL_BYTES && longest <= LONG_SIDE) return null
        val k = minOf(1f, LONG_SIDE.toFloat() / longest)
        val out = File(ctx.cacheDir, "upload_${System.currentTimeMillis()}.mp4")
        // the transformer must be driven from the main thread
        val done = withContext(Dispatchers.Main) { run(ctx, uri, out, even(info.w * k), even(info.h * k)) }
        val ok = done && out.exists() && out.length() > 0 && (info.bytes <= 0 || out.length() < info.bytes)
        if (!ok) {
            out.delete()
            return null
        }
        return out
    }

    private suspend fun run(ctx: Context, uri: Uri, out: File, width: Int, height: Int): Boolean =
        suspendCancellableCoroutine { cont ->
            val encoder = DefaultEncoderFactory.Builder(ctx.applicationContext)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(BITRATE).build())
                .setEnableFallback(true)
                .build()
            val transformer = Transformer.Builder(ctx.applicationContext)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                )
                .build()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))))
                .build()
            cont.invokeOnCancellation { transformer.cancel() }
            try {
                transformer.start(item, out.absolutePath)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }
}
