package com.candy

import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val DRIFT_LIMIT = 210

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    net: Net,
    refreshKey: Int,
    onOpen: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenComments: (String) -> Unit,
    onNewPost: () -> Unit,
    onOpenStory: (String) -> Unit,
    onOpenCamera: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var stories by remember { mutableStateOf<List<StoryGroup>>(emptyList()) }
    var drifts by remember { mutableStateOf<List<Drift>>(emptyList()) }
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var next by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var composing by remember { mutableStateOf(false) }

    fun applyPosts(r: ApiResult, before: Long?) {
        if (r.ok) {
            val page = r.body?.optJSONArray("items").toPosts()
            posts = if (before == null) page else posts + page
            next = if (r.body?.isNull("next") == false) r.body?.optLong("next") else null
            error = ""
        } else {
            error = r.message(ctx)
        }
    }

    suspend fun loadPosts(before: Long?) {
        applyPosts(net.get("/candy/posts/feed" + if (before != null) "?before=$before" else ""), before)
    }

    // your own drifts come first, then the ones from people you follow
    fun applyDrifts(body: JSONObject?) {
        drifts = body?.optJSONArray("mine").toDrifts() + body?.optJSONArray("items").toDrifts()
    }

    LaunchedEffect(reload, refreshKey) {
        loading = true
        // The feed as it was last time goes on screen at once (only when nothing is shown yet), so opening the app never starts
        // with a spinner; the fresh answers replace it a moment later.
        if (posts.isEmpty() && drifts.isEmpty() && stories.isEmpty()) {
            val savedFeed = net.peek("/candy/feed")
            val savedStories = net.peek("/candy/stories")
            val savedPosts = net.peek("/candy/posts/feed")
            if (posts.isEmpty() && drifts.isEmpty() && stories.isEmpty()) {
                if (savedFeed != null) applyDrifts(savedFeed)
                if (savedStories != null) stories = savedStories.optJSONArray("groups").toStoryGroups()
                if (savedPosts != null) {
                    posts = savedPosts.optJSONArray("items").toPosts()
                    next = if (!savedPosts.isNull("next")) savedPosts.optLong("next") else null
                }
                if (posts.isNotEmpty() || drifts.isNotEmpty() || stories.isNotEmpty()) loading = false
            }
        }
        // the three lists do not depend on each other: ask for them together
        val (r, s, p) = coroutineScope {
            val a = async { net.get("/candy/feed") }
            val b = async { net.get("/candy/stories") }
            val c = async { net.get("/candy/posts/feed") }
            Triple(a.await(), b.await(), c.await())
        }
        if (r.ok) applyDrifts(r.body) else error = r.message(ctx)
        if (s.ok) stories = s.body?.optJSONArray("groups").toStoryGroups()
        applyPosts(p, null)
        loading = false
    }

    PullToRefreshBox(
        isRefreshing = loading && (posts.isNotEmpty() || drifts.isNotEmpty()),
        onRefresh = { reload++ },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = LocalBarInset.current),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                // the + is always at the top left, even in Arabic: it opens the camera
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable { onOpenCamera() },
                            contentAlignment = Alignment.Center,
                        ) { Text("+", color = Color.White, style = MaterialTheme.typography.headlineMedium) }
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = { reload++ }) { Text(stringResource(R.string.refresh)) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onNewPost, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.feed_new_post))
                    }
                    OutlinedButton(onClick = { composing = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.feed_new))
                    }
                }
            }
            if (stories.isNotEmpty()) item { StoriesRow(net.token, stories, onOpenStory) }
            item { ErrorText(error) }
            if (drifts.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.feed_title)) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(drifts) { DriftChip(it, net.token, onOpen) }
                    }
                }
            }
            items(posts, key = { it.id }) { PostCard(net, it, onOpenPost, onOpen, onOpenComments) }
            val more = next
            if (more != null) {
                item {
                    TextButton(onClick = { scope.launch { loadPosts(more) } }) { Text(stringResource(R.string.load_more)) }
                }
            }
            if (loading && posts.isEmpty() && drifts.isEmpty()) item { SkeletonCards(3) }
            if (!loading && error.isEmpty() && posts.isEmpty() && drifts.isEmpty()) {
                item { Muted(stringResource(R.string.feed_empty), Modifier.padding(vertical = 16.dp)) }
            }
            item { Column(Modifier.padding(bottom = 16.dp)) {} }
        }
    }

    if (composing) {
        DriftComposer(
            net = net,
            onDismiss = { composing = false },
            onPosted = { composing = false; reload++ },
        )
    }
}

/** A drift as a small card in the strip at the top of the feed. */
@Composable
private fun DriftChip(d: Drift, token: String, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier
            .width(230.dp)
            .then(if (d.ownerHash.isNotEmpty()) Modifier.clickable { onOpen(d.ownerHash) } else Modifier),
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.ownerHash.isNotEmpty()) Avatar(d.ownerHash, token, 36.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    d.ownerName.ifBlank { d.ownerHash },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    d.text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Muted(timeLeft(ctx, d.expiresAt))
            }
        }
    }
}

/** One drift. [onOpen] is null when tapping should do nothing (your own drifts). */
@Composable
fun DriftCard(d: Drift, token: String, onOpen: ((String) -> Unit)?) {
    val ctx = LocalContext.current
    val tappable = onOpen != null && d.ownerHash.isNotEmpty()
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (tappable) Modifier.clickable { onOpen?.invoke(d.ownerHash) } else Modifier),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (d.ownerHash.isNotEmpty()) Avatar(d.ownerHash, token, 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (d.ownerHash.isNotEmpty()) {
                    Text(
                        d.ownerName.ifBlank { d.ownerHash },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Muted(d.ownerHash)
                }
                Text(d.text, style = MaterialTheme.typography.bodyLarge)
                val bubble = if (d.inBubble) " · " + stringResource(R.string.drift_bubble) else ""
                Muted(timeLeft(ctx, d.expiresAt) + bubble)
            }
        }
    }
}

@Composable
private fun AudienceOption(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

/** Write a drift and choose who sees it: your followers, or one of your bubbles. */
@Composable
fun DriftComposer(net: Net, onDismiss: () -> Unit, onPosted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var bubbles by remember { mutableStateOf<List<Bubble>>(emptyList()) }
    var audience by remember { mutableStateOf("") }   // empty = all my followers, otherwise a bubble name
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val r = net.get("/candy/bubbles")
        if (r.ok) bubbles = r.body?.optJSONArray("bubbles").toBubbles()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prof_post_drift)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(
                    stringResource(R.string.prof_drift_hint), text, { text = it.take(DRIFT_LIMIT) },
                    singleLine = false,
                )
                Muted(stringResource(R.string.prof_count, text.length, DRIFT_LIMIT))
                SectionTitle(stringResource(R.string.prof_audience))
                AudienceOption(audience.isEmpty(), stringResource(R.string.prof_audience_all)) { audience = "" }
                for (b in bubbles) {
                    AudienceOption(
                        audience == b.name,
                        stringResource(R.string.prof_audience_bubble, b.name),
                    ) { audience = b.name }
                }
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        busy = true
                        error = ""
                        val body = JSONObject().put("text", text.trim())
                        if (audience.isNotEmpty()) body.put("audience", audience)
                        val r = net.post("/candy/drift", body)
                        busy = false
                        if (r.ok) onPosted() else error = r.message(ctx)
                    }
                },
                enabled = !busy && text.isNotBlank(),
            ) { Text(stringResource(R.string.prof_post)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}