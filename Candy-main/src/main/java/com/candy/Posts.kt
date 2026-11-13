package com.candy

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

/** A track with several pictures in the feed: three cards fanned out, with how many there are. */
@Composable
private fun FanStack(net: Net, post: Post, onClick: () -> Unit) {
    val shown = minOf(post.imageCount, 3)
    Box(
        Modifier.fillMaxWidth().aspectRatio(1.05f).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (shown >= 3) FanCard(net, post, 2, 8f, 70.dp, false)
        if (shown >= 2) FanCard(net, post, 1, -8f, (-70).dp, false)
        FanCard(net, post, 0, 0f, 0.dp, true)
        PicBadge("🖼️ " + post.imageCount, Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

@Composable
private fun FanCard(net: Net, post: Post, index: Int, rotation: Float, shift: Dp, front: Boolean) {
    Box(
        Modifier
            .fillMaxWidth(if (front) 0.66f else 0.58f)
            .fillMaxHeight(if (front) 0.92f else 0.80f)
            .graphicsLayer {
                rotationZ = rotation
                translationX = shift.toPx()
            }
            .clip(RoundedCornerShape(22.dp)),
    ) {
        // the front card is the full picture, the two behind it are the small squares
        PostImageFill(post.id, net.token, !front, index, true)
    }
}

/** All the pictures of one track: swipe sideways, with "2/4" on top. */
@Composable
private fun PostPager(net: Net, p: Post) {
    val pager = rememberPagerState(pageCount = { p.imageCount })
    val aspect = (p.sizes.firstOrNull() ?: 1f).coerceIn(0.75f, 1.6f)
    Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(22.dp))) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            PostImageFill(p.id, net.token, false, page, false)
        }
        PicBadge((pager.currentPage + 1).toString() + "/" + p.imageCount, Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

private fun aspectOf(p: Post): Float =
    if (p.w > 0 && p.h > 0) (p.w.toFloat() / p.h.toFloat()).coerceIn(0.75f, 1.6f) else 1f

/** Reads the picked photo, turns it upright and shrinks it so the upload stays small. */
object PostImagePrep {
    private const val MAX_SIDE = 1080
    private const val MAX_BYTES = 900_000

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
 * A track in the feed: who and when, the picture, the caption, then like and comment.
 * [onComments] opens the comments sheet; it defaults to [onOpenPost] for screens that do not need the sheet.
 */
@Composable
fun PostCard(
    net: Net,
    post: Post,
    onOpenPost: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onComments: (String) -> Unit = onOpenPost,
) {
    val ctx = LocalContext.current
    var sharing by remember { mutableStateOf(false) }
    CandyCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onOpenProfile(post.ownerHash) },
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
            if (post.imageCount > 1) {
                FanStack(net, post) { onOpenPost(post.id) }
            } else if (post.hasImage) {
                Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)) {
                    PostImage(post.id, net.token, false, aspectOf(post), Modifier.clickable { onOpenPost(post.id) })
                }
            }
            if (post.caption.isNotBlank()) {
                Text(
                    taggedText(post.caption, MaterialTheme.colorScheme.primary),
                    style = if (post.hasImage) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { onOpenPost(post.id) }.padding(horizontal = 4.dp),
                )
            }
            PostActions(net, post.id, post.liked, post.likes, post.saved, post.comments, onComments = { onComments(post.id) }, onShare = { sharing = true })
        }
    }
    if (sharing) SendPostDialog(net, post.id) { sharing = false }
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

private class Photo(val bytes: ByteArray, val preview: Bitmap)

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
                        Box(Modifier.size(if (photos.size == 1) 240.dp else 120.dp).clip(RoundedCornerShape(16.dp))) {
                            Image(
                                bitmap = ph.preview.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Surface(
                                color = Color.Black.copy(alpha = 0.55f),
                                shape = CircleShape,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clickable(enabled = !busy) { photos.removeAt(i) },
                            ) {
                                Text("✕", color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
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
            Field(
                stringResource(R.string.post_caption_hint), caption, { caption = it.take(captionLimit) },
                singleLine = false,
            )
            Muted(stringResource(R.string.prof_count, caption.length, captionLimit))
            ErrorText(error)
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    val first = photos.firstOrNull()?.bytes
                    val rest = photos.drop(1).map { it.bytes }
                    if (first != null || caption.isNotBlank()) {
                        scope.launch {
                            busy = true
                            error = ""
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
                                    Muted(p.ownerHash + " · " + agoShort(ctx, p.at) + (p.location?.let { " · 📍 " + it } ?: ""))
                                }
                            }
                            Box {
                                TextButton(onClick = { menu = true }) {
                                    Text("⋮", style = MaterialTheme.typography.titleLarge)
                                }
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
                            PostPager(net, p)
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