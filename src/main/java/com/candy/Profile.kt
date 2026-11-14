package com.candy

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val VIBE_LIMIT_FALLBACK = 160

private val LoopCardW = 120.dp
private val LoopCardH = 176.dp

/** Reports a smaller height than it really has and draws [amount] higher, so it overlaps what is above. */
private fun Modifier.overlapUp(amount: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val up = amount.roundToPx()
    layout(placeable.width, maxOf(placeable.height - up, 0)) { placeable.place(0, -up) }
}

/** The first loop, blurred, behind the cards. Blur only exists from Android 12, so older phones keep the plain gradient. */
@Composable
private fun LoopBackdrop(net: Net, id: String, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    var bmp by remember(id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(id) { bmp = MediaCache.load(net.token, id, true) }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.blur(32.dp),
        )
        Box(
            modifier.background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.45f), MaterialTheme.colorScheme.background),
                ),
            ),
        )
    }
}

/** The speech bubble above your photo: your live drift, or a hint. Tap it to write or change the drift. */
@Composable
private fun DriftBubble(text: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val has = !text.isNullOrBlank()
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.widthIn(min = 110.dp, max = 230.dp),
    ) {
        Text(
            if (has) text.orEmpty() else stringResource(R.string.prof_drift_placeholder),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = if (has) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** All the loops of one person (an account keeps at most 20, which is three pages). */
@Composable
private fun rememberLoops(net: Net, hash: String, refreshKey: Int): List<Post> {
    var loops by remember { mutableStateOf<List<Post>>(emptyList()) }
    LaunchedEffect(hash, refreshKey) {
        val all = ArrayList<Post>()
        var before: Long? = null
        var pages = 0
        while (pages < 3) {
            val q = "/candy/loops?hash=" + Uri.encode(bareHash(hash)) + (if (before != null) "&before=$before" else "")
            val r = net.get(q)
            if (!r.ok) break
            all.addAll(r.body?.optJSONArray("items").toPosts())
            before = if (r.body?.isNull("next") == false) r.body?.optLong("next") else null
            pages++
            if (before == null) break
        }
        loops = all
    }
    return loops
}

/**
 * The pull of the loops header. Pull the page down while you are at the top and the header grows taller;
 * scroll the page up and it shrinks back first, before the page itself starts to move.
 */
@Stable
private class LoopHeaderState(private val maxExtraPx: Float) {
    var extra by mutableStateOf(0f)

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy < 0f && extra > 0f) {
                val use = maxOf(dy, -extra)
                extra += use
                return Offset(0f, use)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy > 0f && extra < maxExtraPx) {
                val use = minOf(dy, maxExtraPx - extra)
                extra += use
                return Offset(0f, use)
            }
            return Offset.Zero
        }
    }
}

@Composable
private fun rememberLoopHeader(): LoopHeaderState {
    val maxExtra = with(LocalDensity.current) { 140.dp.toPx() }
    return remember { LoopHeaderState(maxExtra) }
}

/**
 * The loops of one person as tall cards across the top, each with how long ago it was posted.
 * It comes in at its normal size, grows when you pull the page down at the top, and as the page scrolls it
 * gets shorter, fainter and blurred (the blur needs Android 12).
 */
@Composable
private fun LoopsHeader(net: Net, loops: List<Post>, header: LoopHeaderState, scroll: ScrollState, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val baseH = LoopCardH + 32.dp
    val basePx = with(density) { baseH.toPx() }
    val extraDp = with(density) { header.extra.toDp() }
    val cardH = LoopCardH + extraDp
    val cardW = LoopCardW * (cardH.value / LoopCardH.value)

    Box(
        Modifier
            .fillMaxWidth()
            .height(baseH + extraDp)
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f), Color.Transparent),
                ),
            ),
    ) {
        if (loops.isNotEmpty()) {
            LoopBackdrop(net, loops[0].id, Modifier.matchParentSize())
            Row(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .graphicsLayer {
                        // all of this is read while drawing, so scrolling never recomposes the screen
                        val s = scroll.value.toFloat()
                        val p = (s / basePx).coerceIn(0f, 1f)
                        val k = 1f - 0.3f * p
                        scaleX = k
                        scaleY = k
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        translationY = s * 0.4f
                        alpha = 1f - 0.55f * p
                        renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && p > 0.02f) BlurEffect(24f * p, 24f * p) else null
                    }
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (l in loops) {
                    Box(
                        Modifier
                            .width(cardW)
                            .height(cardH)
                            .clip(MaterialTheme.shapes.large)
                            .clickable { onOpen(l.id) },
                    ) {
                        PostImage(l.id, net.token, true, LoopCardW / LoopCardH)
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                                ),
                            ),
                        )
                        Text(
                            agoShort(ctx, l.at),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                        )
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).size(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CountsRow(user: CUser, onFollowers: (() -> Unit)?, onFollowing: (() -> Unit)?) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(user.posts.toString(), style = MaterialTheme.typography.titleLarge)
            Muted(stringResource(R.string.prof_posts))
        }
        Box(Modifier.width(1.dp).height(36.dp).background(line))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.then(if (onFollowers != null) Modifier.clickable { onFollowers() } else Modifier),
        ) {
            Text(user.followers.toString(), style = MaterialTheme.typography.titleLarge)
            Muted(stringResource(R.string.prof_followers))
        }
        Box(Modifier.width(1.dp).height(36.dp).background(line))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.then(if (onFollowing != null) Modifier.clickable { onFollowing() } else Modifier),
        ) {
            Text(user.following.toString(), style = MaterialTheme.typography.titleLarge)
            Muted(stringResource(R.string.prof_following))
        }
    }
}

/** The part both profiles share: name, hash, vibe and join date. */
@Composable
private fun ProfileInfo(u: CUser) {
    NameWithBadge(
        u.name.ifBlank { u.hash },
        u.verified,
        MaterialTheme.typography.headlineMedium,
        FontWeight.SemiBold,
    )
    Text(u.hash, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
    if (u.followsMe && !u.isMe) Muted(stringResource(R.string.prof_follows_you))
    val vibe = u.vibe
    Text(
        vibe ?: stringResource(R.string.prof_no_vibe),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = if (vibe != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val joined = u.joinDate
    if (joined != null) Muted(stringResource(R.string.prof_joined, joined))
}

/** Up to three friends you both have as followers, and an arrow to the full list. */
@Composable
private fun MutualCircles(net: Net, people: List<Member>, onMore: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).clickable(onClick = onMore),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (m in people) {
            Avatar(m.hash, net.token, 40.dp, 0, Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape))
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

/** Someone else's profile, with a follow button and the drifts you can see. */
@Composable
fun ProfileScreen(
    net: Net,
    hash: String,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
    onMessage: (String) -> Unit,
    onOpenLoop: (String, String) -> Unit,
    onMutuals: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<CUser?>(null) }
    var mutuals by remember { mutableStateOf<List<Member>>(emptyList()) }
    var drifts by remember { mutableStateOf<List<Drift>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var reporting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var confirmBlock by remember { mutableStateOf(false) }

    LaunchedEffect(hash, reload) {
        loading = true
        val r = net.get("/candy/user?hash=" + Uri.encode(bareHash(hash)))
        loading = false
        if (r.ok) {
            user = r.body?.toUser()
            drifts = r.body?.optJSONArray("drifts").toDrifts()
            val list = ArrayList<Member>()
            val arr = r.body?.optJSONArray("mutuals")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(Member(o.optString("name"), o.optString("hash")))
                }
            }
            mutuals = list
            error = ""
        } else {
            error = r.message(ctx)
        }
    }

    val scroll = rememberScrollState()
    val header = rememberLoopHeader()
    Column(Modifier.fillMaxSize().nestedScroll(header.connection).verticalScroll(scroll)) {
        ScreenHeader(hash, onBack)
        if (loading && user == null) Loading()
        if (error.isNotBlank()) Column(Modifier.padding(horizontal = 16.dp)) { ErrorText(error) }
        val u = user
        if (u != null) {
            // a private account you do not follow, or someone you blocked: no tracks, loops, stages or drifts
            val hidden = u.locked || u.blockedByMe
            val loops = rememberLoops(net, u.hash, reload)
            LoopsHeader(net, loops, header, scroll) { id -> onOpenLoop(u.hash, id) }
            Box(Modifier.fillMaxWidth().overlapUp(48.dp), contentAlignment = Alignment.TopCenter) {
                Avatar(u.hash, net.token, 96.dp, 0, Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape))
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = LocalBarInset.current),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProfileInfo(u)
                CountsRow(u, null, null)
                if (!u.isMe && mutuals.isNotEmpty()) {
                    MutualCircles(net, mutuals) { onMutuals(u.hash) }
                }
                if (!u.isMe) {
                    val haptic = rememberAppHaptic()
                    // the bell: a notification for every new track, loop or story from this person (only for people you follow)
                    var bell by remember(u.hash) { mutableStateOf(false) }
                    LaunchedEffect(u.hash, u.followedByMe) {
                        if (u.followedByMe) {
                            val r = net.get("/candy/notify/sub?hash=" + Uri.encode(bareHash(u.hash)))
                            if (r.ok) bell = r.body?.optBoolean("on") == true
                        } else {
                            bell = false
                        }
                    }
                    val toggle: () -> Unit = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch {
                            busy = true
                            error = ""
                            val path = if (u.followedByMe || u.requestedByMe) "/candy/unfollow" else "/candy/follow"
                            val r = net.post(path, JSONObject().put("hash", bareHash(u.hash)))
                            busy = false
                            if (r.ok) reload++ else error = r.message(ctx)
                        }
                    }
                    if (u.blockedByMe) {
                        Muted(stringResource(R.string.prof_blocked_note))
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    error = ""
                                    val r = net.post("/candy/unblock", JSONObject().put("hash", bareHash(u.hash)))
                                    busy = false
                                    if (r.ok) reload++ else error = r.message(ctx)
                                }
                            },
                            enabled = !busy,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.prof_unblock)) }
                    } else if (u.followedByMe) {
                        OutlinedButton(
                            onClick = toggle,
                            enabled = !busy,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.prof_unfollow)) }
                    } else if (u.requestedByMe) {
                        OutlinedButton(
                            onClick = toggle,
                            enabled = !busy,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.prof_requested)) }
                    } else {
                        Button(
                            onClick = toggle,
                            enabled = !busy,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.prof_follow)) }
                    }
                    if (u.followedByMe) {
                        OutlinedButton(
                            onClick = {
                                val want = !bell
                                bell = want
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    val r = net.post("/candy/notify/sub", JSONObject().put("hash", bareHash(u.hash)).put("on", want))
                                    if (!r.ok) {
                                        bell = !want
                                        error = r.message(ctx)
                                    }
                                }
                            },
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(if (bell) Icons.Rounded.Notifications else Icons.Rounded.NotificationsOff, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.sm))
                            Text(stringResource(if (bell) R.string.prof_bell_on else R.string.prof_bell_off))
                        }
                    }
                    if (!u.blockedByMe) {
                        OutlinedButton(
                            onClick = { onMessage(u.hash) },
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.prof_message)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { reporting = true }) { Text(stringResource(R.string.post_report)) }
                        if (!u.blockedByMe) {
                            TextButton(onClick = { confirmBlock = true }) {
                                Text(stringResource(R.string.prof_block), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (notice.isNotEmpty()) Muted(notice)
                }
                if (u.locked && !u.blockedByMe) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                        Text(stringResource(R.string.prof_private_note), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Muted(stringResource(R.string.prof_private_hint))
                    }
                }
                if (!hidden) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResource(R.string.prof_drifts))
                        if (drifts.isEmpty()) Muted(stringResource(R.string.prof_no_drifts))
                        for (d in drifts) DriftCard(d, net.token, null)
                    }
                    PostsSection(net, u.hash, reload, onOpenPost)
                }
                if (confirmBlock) {
                    AlertDialog(
                        onDismissRequest = { confirmBlock = false },
                        title = { Text(stringResource(R.string.prof_block_title, u.name.ifBlank { u.hash })) },
                        text = { Text(stringResource(R.string.prof_block_text)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmBlock = false
                                scope.launch {
                                    busy = true
                                    error = ""
                                    val r = net.post("/candy/block", JSONObject().put("hash", bareHash(u.hash)))
                                    busy = false
                                    if (r.ok) reload++ else error = r.message(ctx)
                                }
                            }) { Text(stringResource(R.string.prof_block), color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text(stringResource(R.string.cancel)) } },
                    )
                }
                if (reporting) {
                    ReportDialog(
                        onSend = { reason ->
                            reporting = false
                            scope.launch {
                                val r = net.post(
                                    "/candy/report",
                                    JSONObject().put("type", "user").put("id", bareHash(u.hash)).put("reason", reason),
                                )
                                if (r.ok) notice = ctx.getString(R.string.report_sent) else error = r.message(ctx)
                            }
                        },
                        onDismiss = { reporting = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun VibeDialog(initial: String, max: Int, onSave: (String) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prof_vibe_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("", text, { text = it.take(max) }, singleLine = false)
                Muted(stringResource(R.string.prof_count, text.length, max))
                TextButton(onClick = onClear) { Text(stringResource(R.string.prof_clear)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Your own profile: photo, vibe, drifts, bubbles, Plus tools, Memories and sign out. */
@Composable
fun MeScreen(
    net: Net,
    onUsers: (String) -> Unit,
    onOpenSettings: () -> Unit,
    refreshKey: Int,
    onOpenPost: (String) -> Unit,
    onNewStage: () -> Unit,
    onOpenLoop: (String, String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<JSONObject?>(null) }
    var reload by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf("") }
    var editingVibe by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }
    var editingDrift by remember { mutableStateOf<Drift?>(null) }

    LaunchedEffect(reload, refreshKey) {
        val r = net.get("/candy/me")
        if (r.ok) {
            me = r.body
            error = ""
        } else {
            error = r.message(ctx)
        }
    }

    val scroll = rememberScrollState()
    val header = rememberLoopHeader()
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().nestedScroll(header.connection).verticalScroll(scroll)) {
        val data = me
        if (data == null) {
            if (error.isEmpty()) Loading() else Column(Modifier.padding(16.dp)) { ErrorText(error) }
        } else {
            val u = data.toUser()
            val vibeMax = data.optInt("vibeMax", VIBE_LIMIT_FALLBACK)

            // the drift lives in a bubble above the photo (your newest one that is still alive)
            val latestDrift = data.optJSONArray("drifts").toDrifts().maxByOrNull { it.postedAt }
            val loops = rememberLoops(net, u.hash, reload + refreshKey)
            Box(Modifier.fillMaxWidth()) {
                LoopsHeader(net, loops, header, scroll) { id -> onOpenLoop(u.hash, id) }
                DriftBubble(
                    text = latestDrift?.text,
                    onClick = { if (latestDrift != null) editingDrift = latestDrift else composing = true },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 54.dp, start = 16.dp, end = 16.dp),
                )
            }
            Box(Modifier.fillMaxWidth().overlapUp(48.dp), contentAlignment = Alignment.TopCenter) {
                AvatarEditor(net, u.hash, data.optJSONObject("avatar"), onAddStage = onNewStage) { reload++ }
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = LocalBarInset.current),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ErrorText(error)
                ProfileInfo(u)
                CountsRow(u, { onUsers("followers") }, { onUsers("following") })
                FilledTonalButton(onClick = { editingVibe = true }, shape = CircleShape) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.sm))
                    Text(stringResource(R.string.prof_edit_vibe))
                }

                PostsSection(net, null, reload + refreshKey, onOpenPost)

                UpdateSection()

                if (editingVibe) {
                    VibeDialog(
                        initial = u.vibe.orEmpty(),
                        max = vibeMax,
                        onSave = { text ->
                            scope.launch {
                                val r = net.post("/candy/vibe", JSONObject().put("text", text))
                                editingVibe = false
                                if (r.ok) reload++ else error = r.message(ctx)
                            }
                        },
                        onClear = {
                            scope.launch {
                                val r = net.post("/candy/vibe/clear")
                                editingVibe = false
                                if (r.ok) reload++ else error = r.message(ctx)
                            }
                        },
                        onDismiss = { editingVibe = false },
                    )
                }
                if (composing) {
                    DriftComposer(
                        net = net,
                        onDismiss = { composing = false },
                        onPosted = { composing = false; reload++ },
                    )
                }
                val toEdit = editingDrift
                if (toEdit != null) {
                    DriftEditor(
                        net = net,
                        drift = toEdit,
                        onDismiss = { editingDrift = null },
                        onSaved = { editingDrift = null; reload++ },
                    )
                }
            }
        }
    }
    SettingsMenuButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
    }
}