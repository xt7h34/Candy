package com.candy

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val VIBE_LIMIT_FALLBACK = 160

@Composable
private fun CountsRow(user: CUser, onFollowers: (() -> Unit)?, onFollowing: (() -> Unit)?) {
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(user.posts.toString(), style = MaterialTheme.typography.titleLarge)
            Muted(stringResource(R.string.prof_posts))
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.then(if (onFollowers != null) Modifier.clickable { onFollowers() } else Modifier),
        ) {
            Text(user.followers.toString(), style = MaterialTheme.typography.titleLarge)
            Muted(stringResource(R.string.prof_followers))
        }
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
    Text(u.name.ifBlank { u.hash }, style = MaterialTheme.typography.headlineSmall)
    Text(u.hash, color = MaterialTheme.colorScheme.primary)
    if (u.followsMe && !u.isMe) Muted(stringResource(R.string.prof_follows_you))
    val vibe = u.vibe
    Text(
        vibe ?: stringResource(R.string.prof_no_vibe),
        style = MaterialTheme.typography.bodyLarge,
        color = if (vibe != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val joined = u.joinDate
    if (joined != null) Muted(stringResource(R.string.prof_joined, joined))
}

/** Someone else's profile, with a follow button and the drifts you can see. */
@Composable
fun ProfileScreen(net: Net, hash: String, onBack: () -> Unit, onOpenPost: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<CUser?>(null) }
    var drifts by remember { mutableStateOf<List<Drift>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var reporting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    LaunchedEffect(hash, reload) {
        loading = true
        val r = net.get("/candy/user?hash=" + Uri.encode(bareHash(hash)))
        loading = false
        if (r.ok) {
            user = r.body?.toUser()
            drifts = r.body?.optJSONArray("drifts").toDrifts()
            error = ""
        } else {
            error = r.message(ctx)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(hash, onBack)
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loading && user == null) Loading()
            ErrorText(error)
            val u = user
            if (u != null) {
                Avatar(u.hash, net.token, 96.dp)
                ProfileInfo(u)
                CountsRow(u, null, null)
                if (!u.isMe) {
                    val toggle: () -> Unit = {
                        scope.launch {
                            busy = true
                            error = ""
                            val path = if (u.followedByMe) "/candy/unfollow" else "/candy/follow"
                            val r = net.post(path, JSONObject().put("hash", bareHash(u.hash)))
                            busy = false
                            if (r.ok) reload++ else error = r.message(ctx)
                        }
                    }
                    if (u.followedByMe) {
                        OutlinedButton(onClick = toggle, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.prof_unfollow))
                        }
                    } else {
                        Button(onClick = toggle, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.prof_follow))
                        }
                    }
                }
                if (!u.isMe) {
                    TextButton(onClick = { reporting = true }) { Text(stringResource(R.string.post_report)) }
                    if (notice.isNotEmpty()) Muted(notice)
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.prof_drifts))
                    if (drifts.isEmpty()) Muted(stringResource(R.string.prof_no_drifts))
                    for (d in drifts) DriftCard(d, net.token, null)
                }
                PostsSection(net, u.hash, reload, onOpenPost)
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

/** Your own profile: photo, vibe, drifts, bubbles, Plus tools and sign out. */
@Composable
fun MeScreen(
    net: Net,
    onUsers: (String) -> Unit,
    onBubbles: () -> Unit,
    onVisits: () -> Unit,
    onGrowth: () -> Unit,
    onSignOut: () -> Unit,
    refreshKey: Int,
    onOpenPost: (String) -> Unit,
    onNewPost: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<JSONObject?>(null) }
    var reload by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf("") }
    var editingVibe by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }

    LaunchedEffect(reload, refreshKey) {
        val r = net.get("/candy/me")
        if (r.ok) {
            me = r.body
            error = ""
        } else {
            error = r.message(ctx)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ErrorText(error)
        val data = me
        if (data == null) {
            if (error.isEmpty()) Loading()
        } else {
            val u = data.toUser()
            val vibeMax = data.optInt("vibeMax", VIBE_LIMIT_FALLBACK)
            AvatarEditor(net, u.hash, data.optJSONObject("avatar")) { reload++ }
            ProfileInfo(u)
            OutlinedButton(onClick = { editingVibe = true }) { Text(stringResource(R.string.prof_edit_vibe)) }
            CountsRow(u, { onUsers("followers") }, { onUsers("following") })

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.prof_drifts))
                val drifts = data.optJSONArray("drifts").toDrifts()
                if (drifts.isEmpty()) Muted(stringResource(R.string.prof_no_drifts))
                for (d in drifts) DriftCard(d, net.token, null)
                Button(onClick = { composing = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.prof_post_drift))
                }
            }

            Button(onClick = onNewPost, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.feed_new_post))
            }
            PostsSection(net, null, reload + refreshKey, onOpenPost)

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onBubbles, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.prof_bubbles))
                }
                OutlinedButton(onClick = onVisits, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.prof_visits))
                }
                OutlinedButton(onClick = onGrowth, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.prof_growth))
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.prof_show_join))
                Switch(
                    checked = data.optBoolean("showJoinDate", true),
                    onCheckedChange = { on ->
                        scope.launch {
                            val r = net.post("/candy/joindate", JSONObject().put("visible", on))
                            if (r.ok) reload++ else error = r.message(ctx)
                        }
                    },
                )
            }

            UpdateSection()

            TextButton(onClick = onSignOut) { Text(stringResource(R.string.prof_signout)) }

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
        }
    }
}
