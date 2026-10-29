package com.candy

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private class ChatThread(
    val name: String,
    val hash: String,
    val lastText: String,
    val lastMine: Boolean,
    val unread: Int,
)

private class ChatMsg(val n: Int, val mine: Boolean, val text: String)

/** All your conversations, newest first, with a badge for the messages you have not read. */
@Composable
fun ChatsScreen(net: Net, refreshKey: Int, onOpenChat: (String) -> Unit) {
    val ctx = LocalContext.current
    var threads by remember { mutableStateOf<List<ChatThread>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(refreshKey) {
        while (true) {
            val r = net.get("/candy/dm/inbox")
            loading = false
            if (r.ok) {
                val arr = r.body?.optJSONArray("threads")
                val out = ArrayList<ChatThread>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val peer = o.optJSONObject("peer")
                        val last = o.optJSONObject("last")
                        out.add(
                            ChatThread(
                                name = peer?.optString("name").orEmpty(),
                                hash = peer?.optString("hash").orEmpty(),
                                lastText = last?.optString("text").orEmpty(),
                                lastMine = last?.optBoolean("mine") == true,
                                unread = o.optInt("unread"),
                            ),
                        )
                    }
                }
                threads = out
                error = ""
            } else {
                error = r.message(ctx)
            }
            delay(15_000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text(stringResource(R.string.chats_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp)) }
        item { ErrorText(error) }
        if (loading) item { Loading() }
        if (!loading && error.isEmpty() && threads.isEmpty()) {
            item { Muted(stringResource(R.string.chats_empty), Modifier.padding(vertical = 16.dp)) }
        }
        items(threads) { t ->
            Card(Modifier.fillMaxWidth().clickable { onOpenChat(t.hash) }) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(t.hash, net.token, 48.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.name.ifBlank { t.hash },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            t.lastText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (t.unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (t.unread > 0) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                    if (t.unread > 0) {
                        Box(
                            Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 8.dp, vertical = 2.dp),
                        ) { Text(t.unread.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
        item { Column(Modifier.padding(bottom = 16.dp)) {} }
    }
}

/**
 * One conversation. New messages are fetched every few seconds while it is open.
 * A stranger can send two messages; to keep going they must follow the other person.
 */
@Composable
fun ChatScreen(net: Net, hash: String, onBack: () -> Unit, onOpenProfile: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<ChatMsg>() }
    var latest by remember { mutableStateOf(0) }
    var peerName by remember { mutableStateOf("") }
    var peerHash by remember { mutableStateOf(hash) }
    var canSend by remember { mutableStateOf(true) }
    var remaining by remember { mutableStateOf<Int?>(null) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(hash) {
        while (true) {
            val r = net.get("/candy/dm/thread?hash=" + Uri.encode(bareHash(hash)) + "&after=" + latest)
            if (r.ok) {
                val body = r.body
                val peer = body?.optJSONObject("peer")
                peerName = peer?.optString("name").orEmpty()
                peerHash = peer?.optString("hash").orEmpty().ifEmpty { hash }
                val arr = body?.optJSONArray("messages")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        val n = m.optInt("n")
                        if (messages.none { it.n == n }) messages.add(ChatMsg(n, m.optBoolean("mine"), m.optString("text")))
                    }
                }
                latest = maxOf(latest, body?.optInt("latest", latest) ?: latest)
                canSend = body?.optBoolean("canSend", true) ?: true
                remaining = if (body != null && !body.isNull("remaining")) body.optInt("remaining") else null
                error = ""
            } else {
                error = r.message(ctx)
            }
            delay(4000)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        scope.launch {
            busy = true
            val r = net.post("/candy/dm/send", JSONObject().put("hash", bareHash(hash)).put("text", text))
            busy = false
            if (r.ok) {
                input = ""
                error = ""
                val m = r.body?.optJSONObject("message")
                if (m != null && messages.none { it.n == m.optInt("n") }) {
                    messages.add(ChatMsg(m.optInt("n"), true, m.optString("text")))
                    latest = maxOf(latest, m.optInt("n"))
                }
                canSend = r.body?.optBoolean("canSend", true) ?: true
                remaining = if (r.body != null && !r.body.isNull("remaining")) r.body.optInt("remaining") else null
            } else {
                error = r.message(ctx)
                if (r.status == 403) canSend = false
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Row(
                Modifier.clickable { onOpenProfile(peerHash) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Avatar(peerHash, net.token, 36.dp)
                Text(peerName.ifBlank { peerHash }, style = MaterialTheme.typography.titleMedium)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages) { m ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
                ) {
                    Box(
                        Modifier
                            .widthIn(max = 280.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (m.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            m.text,
                            color = if (m.mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ErrorText(error)
            val left = remaining
            if (!canSend) {
                Text(
                    stringResource(R.string.chat_limited, peerName.ifBlank { peerHash }),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = {
                        scope.launch {
                            val r = net.post("/candy/follow", JSONObject().put("hash", bareHash(hash)))
                            if (r.ok) {
                                canSend = true
                                remaining = null
                                error = ""
                            } else {
                                error = r.message(ctx)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.prof_follow)) }
            } else {
                if (left != null) Muted(stringResource(R.string.chat_remaining, left))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(
                        stringResource(R.string.chat_hint), input, { input = it.take(1000) },
                        singleLine = false,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { send() }, enabled = !busy && input.isNotBlank()) {
                        Text(stringResource(R.string.chat_send))
                    }
                }
            }
        }
    }
}
