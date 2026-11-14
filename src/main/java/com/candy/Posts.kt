package com.candy

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.PageSize
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import kotlin.math.abs
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Close
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

private const val CAPTION_LIMIT = 500
private const val COMMENT_LIMIT = 300

// ───────────────────────── Pictures ─────────────────────────

/** Post pictures, kept in memory (up to about 24 MB) while the app is open. */
object MediaCache {
    private val mem = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    // The pictures of a post never change, so they are also kept on the phone (the oldest go first when it is full).
    private const val DISK_MAX = 120L * 1024 * 1024
    @Volatile private var dir: File? = null

    fun init(c: Context) {
        dir = File(c.applicationContext.cacheDir, "post_media").apply { mkdirs() }
    }

    private fun fileOf(key: String): File? = dir?.let { File(it, key.replace(Regex("[^A-Za-z0-9._-]"), "_")) }

    private fun trim(d: File) {
        val files = d.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_MAX) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_MAX * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    suspend fun load(token: String, id: String, thumb: Boolean, index: Int = 0): Bitmap? {
        val key = id + (if (thumb) "t" else "f") + (if (index > 0) "#$index" else "")
        val cached = mem.get(key)
        if (cached != null) return cached

        val file = fileOf(key)
        if (file != null && file.length() > 0) {
            val saved = withContext(Dispatchers.IO) {
                val b = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                if (b != null) file.setLastModified(System.currentTimeMillis()) else file.delete()
                b
            }
            if (saved != null) {
                mem.put(key, saved)
                return saved
            }
        }

        val path = "/candy/media?id=" + Uri.encode(id) + (if (thumb) "&thumb=1" else "") + (if (index > 0) "&i=$index" else "")
        val r = CandyApi.fetchBytes(path, token)
        val bytes = r.bytes
        if (r.status in 200..299 && bytes != null && bytes.isNotEmpty()) {
            val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            if (bmp != null) {
                mem.put(key, bmp)
                if (file != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val tmp = File(file.parentFile, file.name + ".tmp")
                            tmp.writeBytes(bytes)
                            if (!tmp.renameTo(file)) tmp.delete()
                            if (Math.random() < 0.05) file.parentFile?.let { trim(it) }
                        }
                    }
                }
                return bmp
            }
        }
        return null
    }

    fun clear() {
        mem.evictAll()
        dir?.listFiles()?.forEach { it.delete() }
    }
}

/** A post picture that keeps its place while it loads. [thumb] is the small square one used in grids. */
@Composable
fun PostImage(id: String, token: String, thumb: Boolean, aspect: Float, modifier: Modifier = Modifier, index: Int = 0) {
    var bmp by remember(id, thumb, index) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(id, thumb, token, index) {
        bmp = MediaCache.load(token, id, thumb, index)
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** A picture that fills whatever space it is given: cropped to fit, or whole ([crop] = false). */
@Composable
fun PostImageFill(id: String, token: String, thumb: Boolean, index: Int, crop: Boolean, modifier: Modifier = Modifier) {
    var bmp by remember(id, thumb, index) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(id, thumb, token, index) {
        bmp = MediaCache.load(token, id, thumb, index)
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** A small dark pill over a picture, like "1/4" or "🖼️ 4". */
@Composable
private fun PicBadge(text: String, modifier: Modifier = Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.55f), shape = CircleShape, modifier = modifier) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** The front card of the fan is this share of the stack's width, and 3 wide for 4 tall. The post composer crops to the same shape. */
private const val FAN_FRONT = 0.66f
private const val FAN_STACK_ASPECT = 1.05f
const val FAN_CARD_ASPECT = 0.75f

/**
 * All the pictures of a track as one fan: the picture you are on is always in the middle with one on each side, tilted
 * behind it, and you swipe either way for ever (after the last one comes the first again). A dot for each picture
 * underneath, the one you are on bigger. Every picture is cropped to the same card, so nothing changes shape, and
 * nothing opens when you tap.
 */
@Composable
fun PostCarousel(net: Net, post: Post, modifier: Modifier = Modifier) {
    val count = post.imageCount
    val scope = rememberCoroutineScope()
    // where the fan is, counted in pictures: 0 is the first, 1.5 is half way from the second to the third, -1 is the last
    val pos = remember(post.id) { Animatable(0f) }
    val base by remember(post.id) { derivedStateOf { pos.value.roundToInt() } }
    // the fan leans the same way in every language
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(FAN_STACK_ASPECT)) {
                val gapPx = with(LocalDensity.current) { maxWidth.toPx() } * 0.22f   // how far the next card is from the front one
                Box(
                    Modifier
                        .fillMaxSize()
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { dx -> scope.launch { pos.snapTo(pos.value - dx / gapPx) } },
                            onDragStopped = { velocity ->
                                scope.launch {
                                    val here = pos.value.roundToInt()
                                    val target = (pos.value - velocity / gapPx * 0.2f).roundToInt().coerceIn(here - 2, here + 2)
                                    pos.animateTo(target.toFloat(), spring(dampingRatio = 0.8f, stiffness = 300f))
                                }
                            },
                        ),
                ) {
                    for (r in -2..2) {
                        val card = base + r
                        key(card) {
                            Box(
                                Modifier
                                    .align(Alignment.Center)
                                    .zIndex(10f - abs(r))
                                    .fillMaxWidth(FAN_FRONT)
                                    .aspectRatio(FAN_CARD_ASPECT)
                                    .graphicsLayer {
                                        // read while drawing: swiping never recomposes the cards
                                        val rel = card - pos.value
                                        val a = abs(rel)
                                        translationX = rel * gapPx
                                        rotationZ = 8f * rel.coerceIn(-1f, 1f)
                                        val k = 1f - 0.12f * a.coerceAtMost(1f)
                                        scaleX = k
                                        scaleY = k
                                        alpha = (2f - a).coerceIn(0f, 1f)
                                    }
                                    .clip(MaterialTheme.shapes.small),
                            ) {
                                FanPicture(net, post.id, Math.floorMod(card, count), r == 0)
                            }
                        }
                    }
                }
                PicBadge("🖼️ " + count, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            PageDots(count, Math.floorMod(base, count))
        }
    }
}

/** One card of the fan: the small picture at once, and the full one as soon as the card is the one in the middle. */
@Composable
private fun FanPicture(net: Net, id: String, index: Int, front: Boolean) {
    var bmp by remember(id, index) { mutableStateOf<Bitmap?>(null) }
    var whole by remember(id, index) { mutableStateOf(false) }
    LaunchedEffect(id, index, front) {
        if (bmp == null) bmp = MediaCache.load(net.token, id, true, index)
        if (front && !whole) {
            val full = MediaCache.load(net.token, id, false, index)
            if (full != null) {
                bmp = full
                whole = true
            }
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** One round dot for each picture; the one you are on is bigger and rose. */
@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        Modifier.height(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until count) {
            val on = i == current
            val size by animateDpAsState(if (on) 10.dp else 6.dp, label = "dot-size")
            val c by animateColorAsState(
                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                label = "dot-colour",
            )
            Box(Modifier.size(size).clip(CircleShape).background(c))
        }
    }
}

private fun aspectOf(p: Post): Float =
    if (p.w > 0 && p.h > 0) (p.w.toFloat() / p.h.toFloat()).coerceIn(0.75f, 1.6f) else 1f

/** Reads the picked photo, turns it upright and shrinks it so the upload stays small. */
object PostImagePrep {
    private const val MAX_SIDE = 1080
    private const val MAX_BYTES = 900_000

    /**
     * The part of [src] that fits a card of [FAN_CARD_ASPECT], as a JPEG. [biasX] and [biasY] (-1 to 1) say which part:
     * 0 is the middle, -1 is the left or top edge, 1 the right or bottom edge.
     */
    fun cropToCard(src: Bitmap, biasX: Float, biasY: Float): ByteArray? {
        try {
            val w = src.width
            val h = src.height
            val cw: Int
            val ch: Int
            if (w.toFloat() / h.toFloat() > FAN_CARD_ASPECT) {
                ch = h
                cw = max(1, minOf(w, (h * FAN_CARD_ASPECT).toInt()))
            } else {
                cw = w
                ch = max(1, minOf(h, (w / FAN_CARD_ASPECT).toInt()))
            }
            val x = ((w - cw) * (1f + biasX.coerceIn(-1f, 1f)) / 2f).toInt().coerceIn(0, max(0, w - cw))
            val y = ((h - ch) * (1f + biasY.coerceIn(-1f, 1f)) / 2f).toInt().coerceIn(0, max(0, h - ch))
            val part = Bitmap.createBitmap(src, x, y, cw, ch)
            var quality = 85
            while (true) {
                val out = ByteArrayOutputStream()
                part.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val data = out.toByteArray()
                if (data.size <= MAX_BYTES || quality <= 50) return data
                quality -= 10
            }
        } catch (e: Throwable) {
            return null
        }
    }

    fun prepare(c: Context, uri: Uri): ByteArray? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null

            val orientation = c.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            }
            val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)

            val longest = max(upright.width, upright.height)
            val done = if (longest > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / longest.toFloat()
                Bitmap.createScaledBitmap(
                    upright,
                    max(1, (upright.width * scale).toInt()),
                    max(1, (upright.height * scale).toInt()),
                    true,
                )
            } else {
                upright
            }

            var quality = 82
            while (true) {
                val out = ByteArrayOutputStream()
                done.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val data = out.toByteArray()
                if (data.size <= MAX_BYTES || quality <= 50) return data
                quality -= 10
            }
        } catch (e: Throwable) {
            return null
        }
    }
}

// ───────────────────────── Likes, cards, grid ─────────────────────────

/** One tap target under a post: an icon, and a number or nothing next to it. */
@Composable
fun ActionPill(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    iconScale: Float = 1f,
) {
    Row(
        modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = desc }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale },
        )
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** The like heart with its number. It pops when you like. */
@Composable
fun LikeButton(net: Net, postId: String, initialLiked: Boolean, initialLikes: Int, idleTint: Color = MaterialTheme.colorScheme.onSurface) {
    val scope = rememberCoroutineScope()
    val haptic = rememberAppHaptic()
    var liked by remember(postId) { mutableStateOf(initialLiked) }
    var likes by remember(postId) { mutableStateOf(initialLikes) }
    val pop = remember { Animatable(1f) }

    ActionPill(
        icon = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        desc = stringResource(R.string.like_desc),
        label = likes.toString(),
        tint = if (liked) Color(0xFFFF4D6D) else idleTint,
        iconScale = pop.value,
        onClick = {
            val was = liked
            if (!was) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)   // a small tap when you like
                scope.launch {
                    pop.animateTo(1.3f, tween(90))
                    pop.animateTo(1f, spring(dampingRatio = 0.45f))
                }
            }
            liked = !was
            likes += if (was) -1 else 1
            scope.launch {
                val r = net.post(if (was) "/candy/unlike" else "/candy/like", JSONObject().put("id", postId))
                if (r.ok) {
                    likes = r.body?.optInt("likes", likes) ?: likes
                } else {
                    liked = was
                    likes += if (was) 1 else -1
                }
            }
        },
    )
}

@Composable
fun SaveButton(net: Net, postId: String, initialSaved: Boolean, tint: Color = MaterialTheme.colorScheme.onSurface) {
    val scope = rememberCoroutineScope()
    val haptic = rememberAppHaptic()
    var saved by remember(postId) { mutableStateOf(initialSaved) }

    ActionPill(
        icon = if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
        desc = stringResource(R.string.save_desc),
        tint = if (saved) MaterialTheme.colorScheme.primary else tint,
        onClick = {
            val want = !saved
            saved = want
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            scope.launch {
                val r = net.post("/candy/save", JSONObject().put("id", postId).put("saved", want))
                if (!r.ok) saved = !want
            }
        },
    )
}

/** The row under a post: like, comments and send on one side, save on the other. */
@Composable
fun PostActions(
    net: Net,
    id: String,
    liked: Boolean,
    likes: Int,
    saved: Boolean,
    comments: Int,
    onComments: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LikeButton(net, id, liked, likes)
            ActionPill(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.comment_desc), onComments, label = comments.toString())
            ActionPill(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.share_desc), onShare)
        }
        SaveButton(net, id, saved)
    }
}

/**
 * A track in the feed: who and when, the picture (or the swipeable fan), the caption, then like and comment.
 * Nothing opens when you tap the pictures: you swipe them where they are, and the ⋮ menu is right in the card.
 * [onComments] opens the comments sheet; it defaults to [onOpenPost] for screens that do not need the sheet.
 * [onRemoved] is told when you delete your own track from the menu.
 */
@Composable
fun PostCard(
    net: Net,
    post: Post,
    onOpenPost: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onComments: (String) -> Unit = onOpenPost,
    onRemoved: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    CandyCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clip(MaterialTheme.shapes.small).clickable { onOpenProfile(post.ownerHash) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Avatar(post.ownerHash, net.token, 42.dp)
                    Column {
                        NameWithBadge(
                            post.ownerName.ifBlank { post.ownerHash },
                            post.ownerVerified,
                            MaterialTheme.typography.titleSmall,
                            FontWeight.Bold,
                        )
                        Muted(post.ownerHash + " · " + agoShort(ctx, post.at) + (post.location?.let { " · " + it } ?: ""))
                    }
                }
                Box {
                    AppIconButton(Icons.Rounded.MoreVert, null, { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.medium) {
                        if (post.mine) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.post_delete)) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.post_report)) },
                                leadingIcon = { Icon(Icons.Rounded.Flag, contentDescription = null) },
                                onClick = { menu = false; reporting = true },
                            )
                        }
                    }
                }
            }
            if (post.imageCount > 1) {
                PostCarousel(net, post)
            } else if (post.hasImage) {
                Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)) {
                    PostImage(post.id, net.token, false, aspectOf(post))
                }
            }
            if (post.caption.isNotBlank()) {
                Text(
                    taggedText(post.caption, MaterialTheme.colorScheme.primary),
                    style = if (post.hasImage) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }
            PostActions(net, post.id, post.liked, post.likes, post.saved, post.comments, onComments = { onComments(post.id) }, onShare = { sharing = true })
        }
    }
    if (sharing) SendPostDialog(net, post.id) { sharing = false }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.post_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            val r = net.post("/candy/post/delete", JSONObject().put("id", post.id))
                            if (r.ok) onRemoved(post.id) else Toast.makeText(ctx, r.message(ctx), Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text(stringResource(R.string.post_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (reporting) {
        ReportDialog(
            onSend = { reason ->
                reporting = false
                scope.launch {
                    val r = net.post("/candy/report", JSONObject().put("type", "post").put("id", post.id).put("reason", reason))
                    Toast.makeText(ctx, if (r.ok) ctx.getString(R.string.report_sent) else r.message(ctx), Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { reporting = false },
        )
    }
}

/** A track with words only, shown as a coloured square in the grid. */
@Composable
private fun TextTile(p: Post, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(8.dp),
    ) {
        Text(
            p.caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 7,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Saved tracks or loops as a grid of squares. */
@Composable
fun SavedGrid(net: Net, posts: List<Post>, loops: Boolean, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in posts.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (p in row) {
                    if (p.hasImage) {
                        Box(Modifier.weight(1f).clickable { onOpen(p.id) }) {
                            PostImage(p.id, net.token, true, 1f)
                            if (loops) {
                                PicBadge("▶", Modifier.align(Alignment.TopEnd).padding(4.dp))
                            } else if (p.imageCount > 1) {
                                PicBadge("🖼️ " + p.imageCount, Modifier.align(Alignment.TopEnd).padding(4.dp))
                            }
                        }
                    } else {
                        TextTile(p, Modifier.weight(1f).clickable { onOpen(p.id) })
                    }
                }
                for (i in row.size until 3) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The tracks of one person as a grid of squares. [hash] is null for your own. */
@Composable
fun PostsSection(net: Net, hash: String?, refreshKey: Int, onOpenPost: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var next by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    suspend fun load(before: Long?) {
        loading = true
        val q = StringBuilder("/candy/posts?")
        if (hash != null) q.append("hash=").append(Uri.encode(bareHash(hash))).append('&')
        if (before != null) q.append("before=").append(before)
        val r = net.get(q.toString())
        loading = false
        if (r.ok) {
            val page = r.body?.optJSONArray("items").toPosts()
            posts = if (before == null) page else posts + page
            next = if (r.body?.isNull("next") == false) r.body?.optLong("next") else null
            error = ""
        } else {
            error = r.message(ctx)
        }
    }

    LaunchedEffect(hash, refreshKey) {
        posts = emptyList()
        load(null)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(stringResource(R.string.prof_posts))
        ErrorText(error)
        if (posts.isEmpty() && !loading && error.isEmpty()) Muted(stringResource(R.string.post_none))
        for (row in posts.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (p in row) {
                    if (p.hasImage) {
                        Box(Modifier.weight(1f).clickable { onOpenPost(p.id) }) {
                            PostImage(p.id, net.token, true, 1f)
                            if (p.imageCount > 1) PicBadge("🖼️ " + p.imageCount, Modifier.align(Alignment.TopEnd).padding(4.dp))
                        }
                    } else {
                        TextTile(p, Modifier.weight(1f).clickable { onOpenPost(p.id) })
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

// ───────────────────────── Report ─────────────────────────

@Composable
fun ReportDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_title)) },
        text = { Field(stringResource(R.string.report_hint), reason, { reason = it.take(300) }, singleLine = false) },
        confirmButton = { TextButton(onClick = { onSend(reason.trim()) }) { Text(stringResource(R.string.post_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ───────────────────────── New post ─────────────────────────

private const val MAX_TRACK_IMAGES = 6

private class Photo(val bytes: ByteArray, val preview: Bitmap) {
    /** Which part of the picture shows in its card when the track has several pictures: -1..1 across and down, 0 is the middle. */
    var biasX by mutableStateOf(0f)
    var biasY by mutableStateOf(0f)
}

/** A new track: up to six photos (the first one makes the track, the rest follow), and the caption. */
@Composable
fun NewPostScreen(net: Net, initial: Uri?, onBack: () -> Unit, onPosted: () -> Unit) {
    val captionLimit = CAPTION_LIMIT
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val photos = remember { mutableStateListOf<Photo>() }
    var caption by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var cropping by remember { mutableStateOf<Photo?>(null) }

    suspend fun addUris(uris: List<Uri>) {
        for (uri in uris) {
            if (photos.size >= MAX_TRACK_IMAGES) break
            val bytes = withContext(Dispatchers.IO) { PostImagePrep.prepare(ctx, uri) }
            if (bytes == null) {
                error = ctx.getString(R.string.avatar_unreadable)
                continue
            }
            val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: continue
            photos.add(Photo(bytes, bmp))
        }
    }

    // a photo that came from the camera or the gallery screen
    LaunchedEffect(initial) {
        if (initial != null) addUris(listOf(initial))
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                error = ""
                addUris(uris)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(R.string.feed_new_post), onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (photos.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    photos.forEachIndexed { i, ph ->
                        val several = photos.size > 1
                        Box(
                            Modifier
                                .then(if (several) Modifier.width(120.dp).aspectRatio(FAN_CARD_ASPECT) else Modifier.size(240.dp))
                                .clip(MaterialTheme.shapes.medium)
                                .then(if (several) Modifier.clickable(enabled = !busy) { cropping = ph } else Modifier),
                        ) {
                            Image(
                                bitmap = ph.preview.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                alignment = BiasAlignment(ph.biasX, ph.biasY),
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (several) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.55f),
                                    shape = CircleShape,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                                ) {
                                    Icon(Icons.Rounded.OpenWith, contentDescription = null, tint = Color.White, modifier = Modifier.padding(6.dp).size(16.dp))
                                }
                            }
                            Surface(
                                color = Color.Black.copy(alpha = 0.55f),
                                shape = CircleShape,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clickable(enabled = !busy) { photos.removeAt(i) },
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.padding(6.dp).size(16.dp))
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { pick.launch("image/*") },
                enabled = !busy && photos.size < MAX_TRACK_IMAGES,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (photos.isEmpty()) R.string.post_pick else R.string.post_add_photo))
            }
            if (photos.isNotEmpty()) Muted(stringResource(R.string.post_photos_count, photos.size, MAX_TRACK_IMAGES))
            if (photos.size > 1) Muted(stringResource(R.string.post_crop_hint))
            Field(
                stringResource(R.string.post_caption_hint), caption, { caption = it.take(captionLimit) },
                singleLine = false,
            )
            Muted(stringResource(R.string.prof_count, caption.length, captionLimit))
            ErrorText(error)
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    if (photos.isNotEmpty() || caption.isNotBlank()) {
                        val snapshot = photos.toList()
                        scope.launch {
                            busy = true
                            error = ""
                            // with several pictures each one is cut to its card, the way you placed it
                            val several = snapshot.size > 1
                            val parts = withContext(Dispatchers.Default) {
                                snapshot.map { ph ->
                                    if (several) PostImagePrep.cropToCard(ph.preview, ph.biasX, ph.biasY) ?: ph.bytes else ph.bytes
                                }
                            }
                            val first = parts.firstOrNull()
                            val rest = parts.drop(1)
                            val body = JSONObject().put("caption", caption.trim())
                            if (first != null) body.put("image", Base64.encodeToString(first, Base64.NO_WRAP))
                            val r = net.post("/candy/post", body)
                            if (!r.ok) {
                                busy = false
                                error = r.message(ctx)
                                return@launch
                            }
                            // the other photos go one by one onto the track that was just made
                            val postId = r.body?.optJSONObject("post")?.optString("id").orEmpty()
                            var failed = 0
                            for (bytes in rest) {
                                val extra = net.post(
                                    "/candy/post/image",
                                    JSONObject().put("id", postId).put("image", Base64.encodeToString(bytes, Base64.NO_WRAP)),
                                )
                                if (!extra.ok) failed++
                            }
                            busy = false
                            if (failed > 0) {
                                Toast.makeText(ctx, ctx.getString(R.string.post_images_partial, failed), Toast.LENGTH_LONG).show()
                            }
                            onPosted()
                        }
                    }
                },
                enabled = !busy && (photos.isNotEmpty() || caption.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.prof_post)) }
        }
    }
    cropping?.let { ph -> CropDialog(ph) { cropping = null } }
}

/** One big card to drag a picture around in, to choose the part of it that shows in the track. */
@Composable
private fun CropDialog(photo: Photo, onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier.padding(Space.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Text(stringResource(R.string.post_crop_title), style = MaterialTheme.typography.titleMedium)
                var frame by remember { mutableStateOf(IntSize.Zero) }
                Box(
                    Modifier
                        .fillMaxWidth(0.8f)
                        .aspectRatio(FAN_CARD_ASPECT)
                        .clip(MaterialTheme.shapes.medium)
                        .onSizeChanged { frame = it }
                        .pointerInput(photo) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val w = photo.preview.width.toFloat()
                                val h = photo.preview.height.toFloat()
                                val k = maxOf(frame.width / w, frame.height / h)
                                val overX = w * k - frame.width
                                val overY = h * k - frame.height
                                if (overX > 1f) photo.biasX = (photo.biasX - 2f * drag.x / overX).coerceIn(-1f, 1f)
                                if (overY > 1f) photo.biasY = (photo.biasY - 2f * drag.y / overY).coerceIn(-1f, 1f)
                            }
                        },
                ) {
                    Image(
                        bitmap = photo.preview.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = BiasAlignment(photo.biasX, photo.biasY),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Muted(stringResource(R.string.post_crop_hint))
                Button(onClick = onDone) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

// ───────────────────────── One post ─────────────────────────

/**
 * A track on its own: the same card as in the feed, bigger, with a ⋮ menu.
 * Comments open in the sheet through [onOpenComments] — the same sheet used from the feed.
 */
@Composable
fun PostScreen(
    net: Net,
    postId: String,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenComments: (String) -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var post by remember { mutableStateOf<Post?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reportingPost by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    LaunchedEffect(postId) {
        val r = net.get("/candy/post?id=" + Uri.encode(postId))
        loading = false
        if (r.ok) {
            post = r.body?.toPost()
            error = ""
        } else {
            error = r.message(ctx)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(R.string.post_title), onBack)
        if (loading) Loading()
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = LocalBarInset.current),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ErrorText(error)
            if (notice.isNotEmpty()) Muted(notice)
            val p = post
            if (p != null) {
                CandyCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.weight(1f).clip(MaterialTheme.shapes.small).clickable { onOpenProfile(p.ownerHash) },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Avatar(p.ownerHash, net.token, 42.dp)
                                Column {
                                    NameWithBadge(
                                        p.ownerName.ifBlank { p.ownerHash },
                                        p.ownerVerified,
                                        MaterialTheme.typography.titleSmall,
                                        FontWeight.Bold,
                                    )
                                    Muted(p.ownerHash + " · " + agoShort(ctx, p.at) + (p.location?.let { " · " + it } ?: ""))
                                }
                            }
                            Box {
                                AppIconButton(Icons.Rounded.MoreVert, null, { menu = true })
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (p.mine) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.post_delete)) },
                                            onClick = { menu = false; confirmDelete = true },
                                        )
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.post_report)) },
                                            onClick = { menu = false; reportingPost = true },
                                        )
                                    }
                                }
                            }
                        }
                        if (p.imageCount > 1) {
                            PostCarousel(net, p)
                        } else if (p.hasImage) {
                            Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)) {
                                PostImage(p.id, net.token, false, aspectOf(p))
                            }
                        }
                        if (p.caption.isNotBlank()) {
                            Text(
                                taggedText(p.caption, MaterialTheme.colorScheme.primary),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                        PostActions(net, p.id, p.liked, p.likes, p.saved, p.comments, onComments = { onOpenComments(p.id) }, onShare = { sharing = true })
                    }
                }

                if (sharing) SendPostDialog(net, p.id) { sharing = false }
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        text = { Text(stringResource(R.string.post_delete_confirm)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmDelete = false
                                    scope.launch {
                                        val r = net.post("/candy/post/delete", JSONObject().put("id", p.id))
                                        if (r.ok) onChanged() else error = r.message(ctx)
                                    }
                                },
                            ) { Text(stringResource(R.string.post_delete)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
                        },
                    )
                }
                if (reportingPost) {
                    ReportDialog(
                        onSend = { reason ->
                            reportingPost = false
                            scope.launch {
                                val r = net.post(
                                    "/candy/report",
                                    JSONObject().put("type", "post").put("id", p.id).put("reason", reason),
                                )
                                if (r.ok) notice = ctx.getString(R.string.report_sent) else error = r.message(ctx)
                            }
                        },
                        onDismiss = { reportingPost = false },
                    )
                }
            }
        }
    }
}