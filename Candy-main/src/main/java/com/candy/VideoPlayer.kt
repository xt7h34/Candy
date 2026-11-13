package com.candy

import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Videos (loops and stories) play while they download, and what was downloaded stays on the phone, up to a limit.
 * So a loop you saw before opens at once, and the next ones are fetched in the background before you swipe to them.
 */
@OptIn(UnstableApi::class)
object VideoCache {
    private const val MAX_BYTES = 200L * 1024 * 1024
    private const val HEAD_BYTES = 1_500_000L

    private var cache: SimpleCache? = null

    @Synchronized
    private fun store(ctx: Context): SimpleCache {
        cache?.let { return it }
        val c = SimpleCache(
            File(ctx.applicationContext.cacheDir, "video_cache"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(ctx.applicationContext),
        )
        cache = c
        return c
    }

    private fun factory(ctx: Context, token: String): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token", "ngrok-skip-browser-warning" to "1"))
        return CacheDataSource.Factory()
            .setCache(store(ctx))
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun newPlayer(ctx: Context, token: String): ExoPlayer =
        ExoPlayer.Builder(ctx.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory(ctx, token)))
            .build()

    /** Fetches the first part of a video into the cache, so it starts instantly when its turn comes. */
    suspend fun preload(ctx: Context, token: String, path: String) {
        withContext(Dispatchers.IO) {
            try {
                val source = factory(ctx, token).createDataSource() as CacheDataSource
                val spec = DataSpec.Builder().setUri(Uri.parse(CandyApi.urlOf(path))).setPosition(0).setLength(HEAD_BYTES).build()
                CacheWriter(source, spec, null, null).cache()
            } catch (e: Exception) {
                // Only a head start: the player fetches it itself when needed.
            }
        }
    }

    /** Throws away one video (stories are meant to be gone once you leave them). */
    fun forget(ctx: Context, path: String) {
        try {
            store(ctx).removeResource(CandyApi.urlOf(path))
        } catch (e: Exception) {
            // Not cached, or already gone.
        }
    }

    /** Sign-out. */
    fun clear(ctx: Context) {
        try {
            val c = store(ctx)
            for (k in c.keys.toList()) c.removeResource(k)
        } catch (e: Exception) {
            // Nothing to clear.
        }
    }
}

/**
 * Plays [path] (a bot address) filling its space, picture fitted. [playing] pauses and resumes; the video also pauses when the app
 * leaves the screen. [onFirstFrame] tells the caller the picture is up (so a cover image can go away).
 */
@OptIn(UnstableApi::class)
@Composable
fun CandyVideo(
    net: Net,
    path: String,
    playing: Boolean,
    loop: Boolean,
    modifier: Modifier = Modifier,
    onEnded: () -> Unit = {},
    onFailed: () -> Unit = {},
    onFirstFrame: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val owner = ctx as LifecycleOwner
    val exo = remember(path) { VideoCache.newPlayer(ctx, net.token) }
    val wantsPlay by rememberUpdatedState(playing)
    val ended by rememberUpdatedState(onEnded)
    val failed by rememberUpdatedState(onFailed)
    val firstFrame by rememberUpdatedState(onFirstFrame)

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ended()
            }

            override fun onPlayerError(error: PlaybackException) {
                failed()
            }

            override fun onRenderedFirstFrame() {
                firstFrame()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) exo.playWhenReady = false
            else if (event == Lifecycle.Event.ON_START) exo.playWhenReady = wantsPlay
        }
        exo.addListener(listener)
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            exo.removeListener(listener)
            exo.release()
        }
    }

    LaunchedEffect(exo, path) {
        exo.setMediaItem(MediaItem.fromUri(CandyApi.urlOf(path)))
        exo.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        exo.playWhenReady = wantsPlay
        exo.prepare()
    }
    LaunchedEffect(exo, playing) { exo.playWhenReady = playing }

    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                // see-through until the first frame, so the cover image under the video stays visible
                setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                setKeepContentOnPlayerReset(true)
                this.player = exo
            }
        },
        update = { v -> if (v.player !== exo) v.player = exo },
        modifier = modifier,
    )
}
