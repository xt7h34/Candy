package com.candy

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Spacer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

private class ChatThread(
    val name: String,
    val hash: String,
    val lastText: String,
    val unread: Int,
)

private class ChatMsg(
    val n: Int,
    val mine: Boolean,
    val text: String,
    val loopId: String?,
    val loopOwnerHash: String,
    val loopOwnerName: String,
    val loopGone: Boolean,
    val stickerId: String?,
    val replyN: Int,
    val replyText: String,
    val replyMine: Boolean,
    val replyKind: String,
    val trackId: String?,
    val trackOwnerHash: String,
    val trackOwnerName: String,
    val trackCaption: String,
    val trackHasImage: Boolean,
    val trackGone: Boolean,
)

private fun JSONObject.toChatMsg(): ChatMsg {
    val loop = optJSONObject("loop")
    val owner = loop?.optJSONObject("owner")
    val reply = optJSONObject("reply")
    val track = optJSONObject("track")
    val trackOwner = track?.optJSONObject("owner")
    return ChatMsg(
        n = optInt("n"),
        mine = optBoolean("mine"),
        text = optString("text"),
        loopId = loop?.optString("id")?.takeIf { it.isNotEmpty() },
        loopOwnerHash = owner?.optString("hash").orEmpty(),
        loopOwnerName = owner?.optString("name").orEmpty(),
        loopGone = loop?.optBoolean("gone") == true,
        stickerId = optJSONObject("sticker")?.optString("id")?.takeIf { it.isNotEmpty() },
        replyN = reply?.optInt("n") ?: 0,
        replyText = reply?.optString("text").orEmpty(),
        replyMine = reply?.optBoolean("mine") == true,
        replyKind = reply?.optString("kind").orEmpty(),
        trackId = track?.optString("id")?.takeIf { it.isNotEmpty() },
        trackOwnerHash = trackOwner?.optString("hash").orEmpty(),
        trackOwnerName = trackOwner?.optString("name").orEmpty(),
        trackCaption = track?.optString("caption").orEmpty(),
        trackHasImage = track?.optBoolean("hasImage") == true,
        trackGone = track?.optBoolean("gone") == true,
    )
}

private fun hoursLeft(ms: Long): Int = maxOf(1L, (ms + 3_599_999L) / 3_600_000L).toInt()

/**
 * Flares and Flows on top, then all your conversations with a badge for unread ones.
 * A Flare friend with something waiting opens it; otherwise the row opens the camera to send one.
 */
@Composable
fun ChatsScreen(
    net: Net,
    refreshKey: Int,
    onOpenChat: (String) -> Unit,
    onOpenFlare: (String) -> Unit,
    onSendFlare: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var threads by remember { mutableStateOf<List<ChatThread>>(emptyList()) }
    var flows by remember { mutableStateOf<List<FlowInfo>>(emptyList()) }
    var flareFriends by remember { mutableStateOf<List<FlareFriend>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }

    PollingEffect(refreshKey to tick, 30_000L) {
        val r = net.get("/candy/dm/inbox")
        loading = false
        if (r.ok) {
            val arr = r.body?.optJSONArray("threads")
            val out = ArrayList<ChatThread>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val peer = o.optJSONObject("peer")
                    out.add(
                        ChatThread(
                            name = peer?.optString("name").orEmpty(),
                            hash = peer?.optString("hash").orEmpty(),
                            lastText = o.optJSONObject("last")?.optString("text").orEmpty(),
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
        val f = net.get("/candy/flows")
        if (f.ok) flows = f.body?.optJSONArray("flows").toFlows()
        val fl = net.get("/candy/flares")
        if (fl.ok) flareFriends = fl.body?.optJSONArray("friends").toFlareFriends()
    }

    fun answer(path: String, hash: String) {
        scope.launch {
            val r = net.post(path, JSONObject().put("hash", bareHash(hash)))
            if (r.ok) tick++ else error = r.message(ctx)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = LocalBarInset.current),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ErrorText(error) }

        if (flareFriends.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.flares_title)) }
            items(flareFriends) { ff ->
                Card(
                    Modifier.fillMaxWidth().clickable { if (ff.unopened > 0) onOpenFlare(ff.hash) else onSendFlare(ff.hash) },
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(ff.hash, net.token, 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                ff.name.ifBlank { ff.hash },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Muted(
                                if (ff.unopened > 0) {
                                    stringResource(R.string.flare_new, ff.unopened) + " · " + stringResource(R.string.flare_open)
                                } else if (ff.atRisk) {
                                    "⏳ " + stringResource(R.string.flow_at_risk, hoursLeft(ff.msLeft))
                                } else {
                                    stringResource(R.string.flare_tap_send)
                                },
                            )
                        }
                        Text("⚡ " + ff.streak, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        if (flows.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.flow_title)) }
            items(flows) { f ->
                Card(Modifier.fillMaxWidth().clickable { onOpenChat(f.peerHash) }) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(f.peerHash, net.token, 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                f.peerName.ifBlank { f.peerHash },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (f.status == "active") {
                                Muted(if (f.atRisk) "⏳ " + stringResource(R.string.flow_at_risk, hoursLeft(f.msLeft)) else stringResource(R.string.flow_rule))
                            } else if (f.incoming) {
                                Muted(stringResource(R.string.flow_incoming, f.peerName.ifBlank { f.peerHash }))
                            } else {
                                Muted(stringResource(R.string.flow_sent))
                            }
                        }
                        if (f.status == "active") {
                            Text("🔥 " + f.streak, style = MaterialTheme.typography.titleMedium)
                        } else if (f.incoming) {
                            Column {
                                Button(onClick = { answer("/candy/flow/accept", f.peerHash) }) { Text(stringResource(R.string.flow_accept)) }
                                TextButton(onClick = { answer("/candy/flow/end", f.peerHash) }) { Text(stringResource(R.string.flow_decline)) }
                            }
                        }
                    }
                }
            }
        }

        if (flows.isNotEmpty() || flareFriends.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.chats_title)) }
        } else {
            item {
                Text(
                    stringResource(R.string.chats_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
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
                            t.lastText.ifEmpty { "🎬" },
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

@Composable
private fun QuoteBlock(m: ChatMsg, who: String, textColor: Color) {
    val snippet = when (m.replyKind) {
        "sticker" -> "😊 " + stringResource(R.string.chat_kind_sticker)
        "loop" -> "🎬 " + stringResource(R.string.chat_kind_loop)
        "track" -> "🖼️ " + stringResource(R.string.chat_kind_track)
        else -> m.replyText
    }
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x22000000)).padding(6.dp)) {
        Column {
            Text(who, color = textColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(snippet, color = textColor, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * One conversation: the Flow with this person at the top, messages you can reply to, stickers,
 * and "typing..." when they are writing. New messages arrive every few seconds while it is open
 * (faster while the other person types). A stranger can send two messages; then they must follow you.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    net: Net,
    hash: String,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenLoop: (String, String) -> Unit,
    onOpenPost: (String) -> Unit,
    onFlare: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<ChatMsg>() }
    var latest by remember { mutableStateOf(0) }
    var peerName by remember { mutableStateOf("") }
    var peerHash by remember { mutableStateOf(hash) }
    var canSend by remember { mutableStateOf(true) }
    var remaining by remember { mutableStateOf<Int?>(null) }
    var flow by remember { mutableStateOf<FlowInfo?>(null) }
    var peerTyping by remember { mutableStateOf(false) }
    var replyTo by remember { mutableStateOf<ChatMsg?>(null) }
    var showStickers by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var lastTypingSent by remember { mutableStateOf(0L) }
    val listState = rememberLazyListState()
    var selected by remember { mutableStateOf<ChatMsg?>(null) }
    var forwarding by remember { mutableStateOf<ChatMsg?>(null) }
    var stickerMsg by remember { mutableStateOf<ChatMsg?>(null) }
    var textSelectN by remember { mutableStateOf(-1) }
    val menuStyle = remember { ChatPrefs.menuStyle(ctx) }
    val typingStyle = remember { ChatPrefs.typingStyle(ctx) }
    val clipboard = LocalClipboardManager.current
    val maxDrag = with(LocalDensity.current) { 72.dp.toPx() }

    PollingEffect(hash, { if (peerTyping) 1500L else 5000L }) {
        val r = net.get("/candy/dm/thread?hash=" + Uri.encode(bareHash(hash)) + "&after=" + latest)
        if (r.ok) {
            val body = r.body
            val peer = body?.optJSONObject("peer")
            peerName = peer?.optString("name").orEmpty()
            peerHash = peer?.optString("hash").orEmpty().ifEmpty { hash }
            val arr = body?.optJSONArray("messages")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i)?.toChatMsg() ?: continue
                    if (messages.none { it.n == m.n }) messages.add(m)
                }
            }
            latest = maxOf(latest, body?.optInt("latest", latest) ?: latest)
            canSend = body?.optBoolean("canSend", true) ?: true
            remaining = if (body != null && !body.isNull("remaining")) body.optInt("remaining") else null
            flow = body?.optJSONObject("flow")?.toFlow()
            peerTyping = body?.optBoolean("typing") == true
            error = ""
        } else {
            error = r.message(ctx)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    // the three-dots bubble is one more row at the end of the list
    LaunchedEffect(peerTyping) {
        if (peerTyping && typingStyle == "bubble" && messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
    }

    // tell the other person we are typing (at most every 3 seconds)
    LaunchedEffect(input) {
        if (input.isNotBlank()) {
            val now = System.currentTimeMillis()
            if (now - lastTypingSent > 3000) {
                lastTypingSent = now
                net.post("/candy/dm/typing", JSONObject().put("hash", bareHash(hash)))
            }
        }
    }

    fun send(sticker: String? = null) {
        val text = if (sticker == null) input.trim() else ""
        if ((text.isEmpty() && sticker == null) || busy) return
        scope.launch {
            busy = true
            val body = JSONObject().put("hash", bareHash(hash)).put("text", text)
            if (sticker != null) body.put("sticker", sticker)
            val quoted = replyTo
            if (quoted != null) body.put("reply", quoted.n)
            val r = net.post("/candy/dm/send", body)
            busy = false
            if (r.ok) {
                if (sticker == null) input = ""
                replyTo = null
                error = ""
                val m = r.body?.optJSONObject("message")?.toChatMsg()
                if (m != null && messages.none { it.n == m.n }) {
                    messages.add(m)
                    latest = maxOf(latest, m.n)
                }
                canSend = r.body?.optBoolean("canSend", true) ?: true
                remaining = if (r.body != null && !r.body.isNull("remaining")) r.body.optInt("remaining") else null
            } else {
                error = r.message(ctx)
                if (r.status == 403) canSend = false
            }
        }
    }

    fun flowAction(path: String) {
        scope.launch {
            val r = net.post(path, JSONObject().put("hash", bareHash(hash)))
            if (r.ok) {
                flow = r.body?.optJSONObject("flow")?.toFlow()
                error = ""
            } else {
                error = r.message(ctx)
            }
        }
    }

    fun copyMsg(m: ChatMsg) {
        if (m.text.isNotEmpty()) {
            clipboard.setText(AnnotatedString(m.text))
            Toast.makeText(ctx, ctx.getString(R.string.msg_copied), Toast.LENGTH_SHORT).show()
        }
    }

    val shownName = peerName.ifBlank { peerHash }

    Column(Modifier.fillMaxSize().imePadding()) {
        val sel = selected
        if (sel != null && menuStyle == "bar") {
            // a message is selected: the bar on top turns into its actions
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { selected = null }) { Text("✕", style = MaterialTheme.typography.titleLarge) }
                Text("1", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { replyTo = sel; selected = null }) { Text("↩", style = MaterialTheme.typography.titleLarge) }
                TextButton(onClick = { copyMsg(sel); selected = null }, enabled = sel.text.isNotEmpty()) {
                    Text("📋", style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = { forwarding = sel; selected = null }) { Text("↪", style = MaterialTheme.typography.titleLarge) }
                TextButton(onClick = { textSelectN = sel.n; selected = null }, enabled = sel.text.isNotEmpty()) {
                    Text("Aa", style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                Row(
                    Modifier.weight(1f).clickable { onOpenProfile(peerHash) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Avatar(peerHash, net.token, 36.dp)
                    Column {
                        Text(shownName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (peerTyping && typingStyle == "text") {
                            Text(stringResource(R.string.chat_typing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        val f = flow
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (f == null) {
                    TextButton(onClick = { flowAction("/candy/flow/request") }) {
                        Text("🔥 " + stringResource(R.string.flow_start))
                    }
                } else if (f.status == "pending") {
                    if (f.incoming) {
                        Text(stringResource(R.string.flow_incoming, shownName), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { flowAction("/candy/flow/accept") }) { Text(stringResource(R.string.flow_accept)) }
                            TextButton(onClick = { flowAction("/candy/flow/end") }) { Text(stringResource(R.string.flow_decline)) }
                        }
                    } else {
                        Muted(stringResource(R.string.flow_sent))
                        TextButton(onClick = { flowAction("/candy/flow/end") }) { Text(stringResource(R.string.flow_cancel)) }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("🔥 " + f.streak, style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { flowAction("/candy/flow/end") }) { Text(stringResource(R.string.flow_end)) }
                    }
                    Muted((if (f.iSent) "✅ " else "⬜ ") + stringResource(if (f.iSent) R.string.flow_you_sent else R.string.flow_you_not))
                    Muted((if (f.theySent) "✅ " else "⬜ ") + stringResource(if (f.theySent) R.string.flow_they_sent else R.string.flow_they_not, shownName))
                    if (f.atRisk) {
                        Text(
                            "⏳ " + stringResource(R.string.flow_at_risk, hoursLeft(f.msLeft)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Muted(stringResource(R.string.flow_hint))
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages) { m ->
                val isSel = selected?.n == m.n
                var drag by remember(m.n) { mutableStateOf(0f) }
                var dragging by remember(m.n) { mutableStateOf(false) }
                val shownDrag by animateFloatAsState(if (dragging) drag else 0f, label = "swipe")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSel && menuStyle == "bar") MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                        .pointerInput(m.n) {
                            // swipe a message sideways to reply to it
                            detectHorizontalDragGestures(
                                onDragStart = { dragging = true },
                                onDragEnd = {
                                    if (abs(drag) > maxDrag * 0.7f) replyTo = m
                                    dragging = false
                                    drag = 0f
                                },
                                onDragCancel = {
                                    dragging = false
                                    drag = 0f
                                },
                                onHorizontalDrag = { change, amount ->
                                    change.consume()
                                    drag = (drag + amount).coerceIn(-maxDrag, maxDrag)
                                },
                            )
                        },
                ) {
                    Text(
                        "↩",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(if (drag < 0f) Alignment.CenterEnd else Alignment.CenterStart)
                            .padding(horizontal = 16.dp)
                            .alpha((abs(shownDrag) / maxDrag).coerceIn(0f, 1f)),
                    )
                    Row(
                        Modifier.fillMaxWidth().offset { IntOffset(shownDrag.roundToInt(), 0) },
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
                    ) {
                        val stickerId = m.stickerId
                        val quoteWho = if (m.replyMine) stringResource(R.string.nav_me) else shownName
                        if (stickerId != null) {
                            Column(horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
                                if (m.replyN > 0) QuoteBlock(m, quoteWho, MaterialTheme.colorScheme.onSurface)
                                StickerImage(
                                    stickerId, net.token, 120.dp,
                                    Modifier.combinedClickable(onClick = { stickerMsg = m }, onLongClick = { selected = m }),
                                )
                            }
                        } else {
                            Column(
                                Modifier
                                    .widthIn(max = 260.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .combinedClickable(onClick = {}, onLongClick = { selected = m })
                                    .background(if (m.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val textColor = if (m.mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                if (m.replyN > 0) QuoteBlock(m, quoteWho, textColor)
                                val loopId = m.loopId
                                if (loopId != null) {
                                    if (m.loopGone) {
                                        Text(stringResource(R.string.chat_loop_gone), color = textColor, style = MaterialTheme.typography.bodySmall)
                                    } else {
                                        Box(
                                            Modifier
                                                .width(150.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .combinedClickable(
                                                    onClick = { onOpenLoop(m.loopOwnerHash, loopId) },
                                                    onLongClick = { selected = m },
                                                ),
                                        ) {
                                            PostImage(loopId, net.token, true, 1f)
                                            Text("▶", color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
                                        }
                                        Text(m.loopOwnerName.ifBlank { m.loopOwnerHash }, color = textColor, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                val trackId = m.trackId
                                if (trackId != null) {
                                    if (m.trackGone) {
                                        Text(stringResource(R.string.chat_track_gone), color = textColor, style = MaterialTheme.typography.bodySmall)
                                    } else {
                                        Column(
                                            Modifier
                                                .width(180.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .combinedClickable(
                                                    onClick = { onOpenPost(trackId) },
                                                    onLongClick = { selected = m },
                                                ),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            if (m.trackHasImage) PostImage(trackId, net.token, true, 1f)
                                            Text(m.trackOwnerName.ifBlank { m.trackOwnerHash }, color = textColor, style = MaterialTheme.typography.labelSmall)
                                            if (m.trackCaption.isNotBlank()) {
                                                Text(m.trackCaption, color = textColor, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                                            }
                                        }
                                    }
                                }
                                if (m.text.isNotEmpty()) {
                                    if (textSelectN == m.n) {
                                        SelectionContainer { Text(m.text, color = textColor) }
                                    } else {
                                        Text(m.text, color = textColor)
                                    }
                                }
                            }
                        }
                    }
                    DropdownMenu(expanded = isSel && menuStyle == "popup", onDismissRequest = { selected = null }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.msg_action_reply)) },
                            onClick = { replyTo = m; selected = null },
                        )
                        if (m.text.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.msg_action_copy)) },
                                onClick = { copyMsg(m); selected = null },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.msg_action_select_text)) },
                                onClick = { textSelectN = m.n; selected = null },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.msg_action_forward)) },
                            onClick = { forwarding = m; selected = null },
                        )
                    }
                }
            }
            if (peerTyping && typingStyle == "bubble") {
                item { TypingBubble() }
            }
        }

        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ErrorText(error)
            val left = remaining
            val quoted = replyTo
            if (!canSend) {
                Text(
                    stringResource(R.string.chat_limited, shownName),
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
                if (quoted != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Muted(
                            stringResource(R.string.chat_replying, if (quoted.mine) stringResource(R.string.nav_me) else shownName) + ": " +
                                (if (quoted.stickerId != null) "😊" else if (quoted.loopId != null) "🎬" else quoted.text.take(40)),
                            Modifier.weight(1f),
                        )
                        TextButton(onClick = { replyTo = null }) { Text("✕") }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(26.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { showStickers = true }) { Text("😊", style = MaterialTheme.typography.titleLarge) }
                            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                if (input.isEmpty()) {
                                    Text(stringResource(R.string.chat_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                BasicTextField(
                                    value = input,
                                    onValueChange = { input = it.take(1000) },
                                    textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    maxLines = 4,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            TextButton(onClick = { onFlare(peerHash) }) { Text("📸", style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                    val canPost = !busy && input.isNotBlank()
                    Surface(
                        shape = CircleShape,
                        color = if (canPost) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(48.dp).clickable(enabled = canPost) { send() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "↑",
                                style = MaterialTheme.typography.titleLarge,
                                color = if (canPost) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showStickers) {
        StickerPicker(
            net = net,
            onPick = { id ->
                showStickers = false
                send(id)
            },
            onDismiss = { showStickers = false },
        )
    }

    val fwd = forwarding
    if (fwd != null) ForwardDialog(net, fwd) { forwarding = null }

    val sm = stickerMsg
    val smSticker = sm?.stickerId
    if (sm != null && smSticker != null) {
        ModalBottomSheet(
            onDismissRequest = { stickerMsg = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StickerImage(smSticker, net.token, 240.dp)
                TextButton(onClick = { replyTo = sm; stickerMsg = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("↩  " + stringResource(R.string.msg_action_reply))
                }
                TextButton(onClick = { forwarding = sm; stickerMsg = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("↪  " + stringResource(R.string.msg_action_forward))
                }
            }
        }
    }
}

/** Three dots that fade in turn, at the end of the chat while the other person writes. */
@Composable
private fun TypingBubble() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            for (i in 0 until 3) {
                val a by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse, StartOffset(i * 160)),
                    label = "dot$i",
                )
                Box(Modifier.size(8.dp).alpha(a).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            }
        }
    }
}

/** Send a message you got to someone else. The people you talked to last are on top. */
@Composable
private fun ForwardDialog(net: Net, m: ChatMsg, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var recent by remember { mutableStateOf<List<Member>>(emptyList()) }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val r = net.get("/candy/dm/inbox")
        if (r.ok) {
            val arr = r.body?.optJSONArray("threads")
            val list = ArrayList<Member>()
            if (arr != null) {
                for (i in 0 until minOf(arr.length(), 6)) {
                    val peer = arr.optJSONObject(i)?.optJSONObject("peer") ?: continue
                    list.add(Member(peer.optString("name"), peer.optString("hash")))
                }
            }
            recent = list
        }
    }

    fun sendTo(hash: String) {
        scope.launch {
            busy = true
            error = ""
            val body = JSONObject().put("hash", bareHash(hash)).put("text", m.text)
            m.stickerId?.let { body.put("sticker", it) }
            if (!m.loopGone) m.loopId?.let { body.put("loop", it) }
            if (!m.trackGone) m.trackId?.let { body.put("track", it) }
            val r = net.post("/candy/dm/send", body)
            busy = false
            if (r.ok) done = ctx.getString(R.string.loop_sent) else error = r.message(ctx)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msg_forward_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in recent) {
                    TextButton(onClick = { sendTo(p.hash) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(p.name.ifBlank { p.hash })
                    }
                }
                Field(stringResource(R.string.loop_send_hash), typed, { typed = it })
                if (done.isNotEmpty()) Text(done, color = MaterialTheme.colorScheme.primary)
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(onClick = { sendTo(typed) }, enabled = !busy && typed.isNotBlank()) { Text(stringResource(R.string.post_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
