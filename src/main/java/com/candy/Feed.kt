package com.candy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val DRIFT_LIMIT = 210

@Composable
fun FeedScreen(
    net: Net,
    refreshKey: Int,
    onOpen: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onNewPost: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var drifts by remember { mutableStateOf<List<Drift>>(emptyList()) }
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var next by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var composing by remember { mutableStateOf(false) }

    suspend fun loadPosts(before: Long?) {
        val r = net.get("/candy/posts/feed" + if (before != null) "?before=$before" else "")
        if (r.ok) {
            val page = r.body?.optJSONArray("items").toPosts()
            posts = if (before == null) page else posts + page
            next = if (r.body?.isNull("next") == false) r.body?.optLong("next") else null
            error = ""
        } else {
            error = r.message(ctx)
        }
    }

    LaunchedEffect(reload, refreshKey) {
        loading = true
        val r = net.get("/candy/feed")
        if (r.ok) {
            // your own drifts come first, then the ones from people you follow
            drifts = r.body?.optJSONArray("mine").toDrifts() + r.body?.optJSONArray("items").toDrifts()
        } else {
            error = r.message(ctx)
        }
        loadPosts(null)
        loading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.nav_feed), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { reload++ }) { Text(stringResource(R.string.refresh)) }
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
        item { ErrorText(error) }
        if (drifts.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.feed_title)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(drifts) { DriftChip(it, net.token, onOpen) }
                }
            }
        }
        items(posts) { PostCard(net, it, onOpenPost, onOpen) }
        val more = next
        if (more != null) {
            item {
                TextButton(onClick = { scope.launch { loadPosts(more) } }) { Text(stringResource(R.string.load_more)) }
            }
        }
        if (loading && posts.isEmpty() && drifts.isEmpty()) item { Loading() }
        if (!loading && error.isEmpty() && posts.isEmpty() && drifts.isEmpty()) {
            item { Muted(stringResource(R.string.feed_empty), Modifier.padding(vertical = 16.dp)) }
        }
        item { Column(Modifier.padding(bottom = 16.dp)) {} }
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
