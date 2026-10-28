package com.candy

import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max

private const val CAPTION_LIMIT = 500
private const val COMMENT_LIMIT = 300

// ───────────────────────── Pictures ─────────────────────────

/** Post pictures, kept in memory (up to about 24 MB) while the app is open. */
object MediaCache {
    private val mem = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun load(token: String, id: String, thumb: Boolean): Bitmap? {
        val key = id + (if (thumb) "t" else "f")
        val cached = mem.get(key)
        if (cached != null) return cached
        val path = "/candy/media?id=" + Uri.encode(id) + (if (thumb) "&thumb=1" else "")
        val r = CandyApi.fetchBytes(path, token)
        val bytes = r.bytes
        if (r.status in 200..299 && bytes != null && bytes.isNotEmpty()) {
            val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            if (bmp != null) {
                mem.put(key, bmp)
                return bmp
            }
        }
        return null
    }

    fun clear() = mem.evictAll()
}

/** A post picture that keeps its place while it loads. [thumb] is the small square one used in grids. */
@Composable
fun PostImage(id: String, token: String, thumb: Boolean, aspect: Float, modifier: Modifier = Modifier) {
    var bmp by remember(id, thumb) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(id, thumb, token) {
        bmp = MediaCache.load(token, id, thumb)
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

@Composable
fun LikeButton(net: Net, postId: String, initialLiked: Boolean, initialLikes: Int) {
    val scope = rememberCoroutineScope()
    var liked by remember(postId) { mutableStateOf(initialLiked) }
    var likes by remember(postId) { mutableStateOf(initialLikes) }

    TextButton(
        onClick = {
            val was = liked
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
    ) { Text((if (liked) "❤️" else "🤍") + " " + likes) }
}

/** A post in the feed: who, the picture, like and comment buttons, and the caption. */
@Composable
fun PostCard(net: Net, post: Post, onOpenPost: (String) -> Unit, onOpenProfile: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { onOpenProfile(post.ownerHash) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Avatar(post.ownerHash, net.token, 36.dp)
                Column {
                    Text(
                        post.ownerName.ifBlank { post.ownerHash },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Muted(post.ownerHash)
                }
            }
            PostImage(post.id, net.token, false, aspectOf(post), Modifier.clickable { onOpenPost(post.id) })
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                LikeButton(net, post.id, post.liked, post.likes)
                TextButton(onClick = { onOpenPost(post.id) }) { Text("💬 " + post.comments) }
            }
            if (post.caption.isNotBlank()) {
                Text(
                    post.caption,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/** The photos of one person as a grid of squares. [hash] is null for your own. */
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
                    PostImage(p.id, net.token, true, 1f, Modifier.weight(1f).clickable { onOpenPost(p.id) })
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

@Composable
fun NewPostScreen(net: Net, onBack: () -> Unit, onPosted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var jpeg by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var caption by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { PostImagePrep.prepare(ctx, uri) }
                if (bytes == null) {
                    error = ctx.getString(R.string.avatar_unreadable)
                } else {
                    error = ""
                    jpeg = bytes
                    preview = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(R.string.feed_new_post), onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val shown = preview
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                )
            }
            OutlinedButton(onClick = { pick.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (shown == null) R.string.post_pick else R.string.avatar_change))
            }
            Field(
                stringResource(R.string.post_caption_hint), caption, { caption = it.take(CAPTION_LIMIT) },
                singleLine = false,
            )
            Muted(stringResource(R.string.prof_count, caption.length, CAPTION_LIMIT))
            ErrorText(error)
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    val bytes = jpeg
                    if (bytes != null) {
                        scope.launch {
                            busy = true
                            error = ""
                            val body = JSONObject()
                                .put("image", Base64.encodeToString(bytes, Base64.NO_WRAP))
                                .put("caption", caption.trim())
                            val r = net.post("/candy/post", body)
                            busy = false
                            if (r.ok) onPosted() else error = r.message(ctx)
                        }
                    }
                },
                enabled = !busy && jpeg != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.prof_post)) }
        }
    }
}

// ───────────────────────── One post, with its comments ─────────────────────────

@Composable
fun PostScreen(
    net: Net,
    postId: String,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var post by remember { mutableStateOf<Post?>(null) }
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var newComment by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reportingPost by remember { mutableStateOf(false) }
    var reportingComment by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(postId, reload) {
        val r = net.get("/candy/post?id=" + Uri.encode(postId))
        loading = false
        if (r.ok) {
            post = r.body?.toPost()
            comments = r.body?.optJSONArray("commentList").toComments()
            error = ""
        } else {
            error = r.message(ctx)
        }
    }

    fun report(type: String, id: String, reason: String, extra: JSONObject? = null) {
        scope.launch {
            val body = JSONObject().put("type", type).put("id", id).put("reason", reason)
            if (extra != null) body.put("postId", extra.optString("postId"))
            val r = net.post("/candy/report", body)
            if (r.ok) notice = ctx.getString(R.string.report_sent) else error = r.message(ctx)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(R.string.post_title), onBack)
        if (loading) Loading()
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorText(error)
            if (notice.isNotEmpty()) Muted(notice)
            val p = post
            if (p != null) {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenProfile(p.ownerHash) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Avatar(p.ownerHash, net.token, 40.dp)
                    Column {
                        Text(
                            p.ownerName.ifBlank { p.ownerHash },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Muted(p.ownerHash)
                    }
                }
                PostImage(p.id, net.token, false, aspectOf(p))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LikeButton(net, p.id, p.liked, p.likes)
                    if (p.mine) {
                        TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.post_delete)) }
                    } else {
                        TextButton(onClick = { reportingPost = true }) { Text(stringResource(R.string.post_report)) }
                    }
                }
                if (p.caption.isNotBlank()) Text(p.caption, style = MaterialTheme.typography.bodyLarge)

                SectionTitle(stringResource(R.string.post_comments_title))
                if (comments.isEmpty()) Muted(stringResource(R.string.post_no_comments))
                for (c in comments) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Avatar(c.ownerHash, net.token, 32.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.ownerName.ifBlank { c.ownerHash },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(c.text, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (c.canDelete) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        val r = net.post(
                                            "/candy/comment/delete",
                                            JSONObject().put("postId", p.id).put("commentId", c.id),
                                        )
                                        if (r.ok) reload++ else error = r.message(ctx)
                                    }
                                },
                            ) { Text(stringResource(R.string.post_delete)) }
                        } else {
                            TextButton(onClick = { reportingComment = c.id }) { Text(stringResource(R.string.post_report)) }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Field(
                        stringResource(R.string.post_comment_hint), newComment, { newComment = it.take(COMMENT_LIMIT) },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                val r = net.post(
                                    "/candy/comment",
                                    JSONObject().put("id", p.id).put("text", newComment.trim()),
                                )
                                busy = false
                                if (r.ok) {
                                    newComment = ""
                                    error = ""
                                    reload++
                                } else {
                                    error = r.message(ctx)
                                }
                            }
                        },
                        enabled = !busy && newComment.isNotBlank(),
                    ) { Text(stringResource(R.string.post_send)) }
                }

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
                        onSend = { reportingPost = false; report("post", p.id, it) },
                        onDismiss = { reportingPost = false },
                    )
                }
                val reportedComment = reportingComment
                if (reportedComment != null) {
                    ReportDialog(
                        onSend = {
                            reportingComment = null
                            report("comment", reportedComment, it, JSONObject().put("postId", p.id))
                        },
                        onDismiss = { reportingComment = null },
                    )
                }
            }
        }
    }
}
