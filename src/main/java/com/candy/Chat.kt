package com.candy

import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.Manifest
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.sp
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
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
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
    val lastKind: String,
    val lastDeleted: Boolean,
    val nick: String,
    val lastSeen: Boolean,
    val unread: Int,
)

private data class ChatMsg(
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
    val at: Long,
    val edited: Boolean,
    val deleted: Boolean,
    val myReaction: String?,
    val theirReaction: String?,
    val forwarded: Boolean,
    val hidden: Boolean,
    val sys: String?,
    val sysValue: String,
    val voiceMs: Long?,
    val hasImage: Boolean = false,
    val imageW: Int = 0,
    val imageH: Int = 0,
)

/** A pinned message: which one, until when, and whether I pinned it. */
private class PinInfo(val n: Int, val until: Long, val mine: Boolean, val msg: ChatMsg)

private const val EDIT_WINDOW_MS = 15 * 60 * 1000L

/** One result of searching inside a chat. */
private class SearchHit(val n: Int, val mine: Boolean, val text: String, val at: Long)
private val QUICK_REACTIONS = listOf("❤️", "😂", "😮", "😢", "👍", "🔥")
private val MORE_REACTIONS = listOf(
    "❤️", "😂", "😮", "😢", "👍", "🔥", "😍", "🥰", "😘", "😎", "🤔", "😅",
    "🙏", "👏", "🎉", "💯", "😭", "😡", "🤣", "😴", "👎", "💔", "✨", "🙌",
    "😇", "🤝", "💪", "👀", "🫶", "😬",
)

private fun msgPreview(m: ChatMsg): String = when {
    m.stickerId != null -> "😊"
    m.voiceMs != null -> "🎤"
    m.hasImage -> "📷" + (if (m.text.isNotEmpty()) " " + m.text else "")
    m.text.isNotEmpty() -> m.text
    m.trackId != null -> "🖼️"
    m.loopId != null -> "🎬"
    else -> ""
}

private fun canEdit(m: ChatMsg): Boolean =
    m.mine && !m.deleted && m.stickerId == null && !m.hasImage && m.text.isNotEmpty() && System.currentTimeMillis() - m.at <= EDIT_WINDOW_MS

private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

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
        at = optLong("at"),
        edited = !isNull("edited") && optLong("edited") > 0L,
        deleted = optBoolean("deleted"),
        myReaction = optJSONObject("reactions")?.nullableString("mine"),
        theirReaction = optJSONObject("reactions")?.nullableString("theirs"),
        forwarded = optBoolean("forwarded"),
        hidden = optBoolean("hidden"),
        sys = nullableString("sys"),
        sysValue = optString("value"),
        voiceMs = optJSONObject("voice")?.optLong("ms"),
        hasImage = optJSONObject("image") != null,
        imageW = optJSONObject("image")?.optInt("w") ?: 0,
        imageH = optJSONObject("image")?.optInt("h") ?: 0,
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
    var composingDrift by remember { mutableStateOf(false) }
    var myDrifts by remember { mutableStateOf<List<Drift>>(emptyList()) }     // my drifts that are still alive
    var editingDrift by remember { mutableStateOf<Drift?>(null) }
    var tick by remember { mutableStateOf(0) }

    fun parseThreads(arr: org.json.JSONArray?): List<ChatThread> {
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
                        lastKind = o.optJSONObject("last")?.optString("kind").orEmpty(),
                        lastDeleted = o.optJSONObject("last")?.optBoolean("deleted") == true,
                        nick = o.optString("nick"),
                        lastSeen = o.optJSONObject("last")?.optBoolean("seen") == true,
                        unread = o.optInt("unread"),
                    ),
                )
            }
        }
        return out
    }

    // the inbox as it was last time, on screen at once; the fresh one replaces it a moment later
    LaunchedEffect(Unit) {
        if (threads.isEmpty()) {
            val saved = net.peek("/candy/dm/inbox")
            if (saved != null && threads.isEmpty()) {
                threads = parseThreads(saved.optJSONArray("threads"))
                loading = false
            }
        }
    }

    PollingEffect(Triple(refreshKey, tick, Events.dmTick), { if (Events.connected) 60_000L else 30_000L }) {
        // the lists are independent, so they are asked for together instead of one after the other
        val (r, f, fl, me) = coroutineScope {
            val a = async { net.get("/candy/dm/inbox") }
            val b = async { net.get("/candy/flows") }
            val c = async { net.get("/candy/flares") }
            val d = async { net.get("/candy/me") }
            listOf(a.await(), b.await(), c.await(), d.await())
        }
        loading = false
        if (r.ok) {
            threads = parseThreads(r.body?.optJSONArray("threads"))
            error = ""
        } else {
            error = r.message(ctx)
        }
        if (f.ok) flows = f.body?.optJSONArray("flows").toFlows()
        if (fl.ok) flareFriends = fl.body?.optJSONArray("friends").toFlareFriends()
        if (me.ok) myDrifts = me.body?.optJSONArray("drifts").toDrifts()
    }

    fun answer(path: String, hash: String) {
        scope.launch {
            val r = net.post(path, JSONObject().put("hash", bareHash(hash)))
            if (r.ok) tick++ else error = r.message(ctx)
        }
    }

    if (composingDrift) {
        DriftComposer(
            net = net,
            onDismiss = { composingDrift = false },
            onPosted = {
                composingDrift = false
                tick++
            },
        )
    }
    val toEdit = editingDrift
    if (toEdit != null) {
        DriftEditor(
            net = net,
            drift = toEdit,
            onDismiss = { editingDrift = null },
            onSaved = {
                editingDrift = null
                tick++
            },
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = LocalBarInset.current),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // my drifts that are alive (tap Edit to change the text) and a new drift, right above the chats
        item {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in myDrifts) {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("💭 " + d.text, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { editingDrift = d }) { Text(stringResource(R.string.drift_edit)) }
                        }
                    }
                }
                OutlinedButton(onClick = { composingDrift = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feed_new))
                }
            }
        }
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
                        Text(ChatPrefs.flareEmoji(ctx) + " " + ff.streak, style = MaterialTheme.typography.titleMedium)
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
                            Text(ChatPrefs.flowEmoji(ctx) + " " + f.streak, style = MaterialTheme.typography.titleMedium)
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
        if (loading) item { SkeletonCards(4, tall = false) }
        if (!loading && error.isEmpty() && threads.isEmpty()) {
            item { Muted(stringResource(R.string.chats_empty), Modifier.padding(vertical = 16.dp)) }
        }
        val seenInList = ChatPrefs.seenPlace(ctx) == "list"
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
                            t.nick.ifBlank { t.name.ifBlank { t.hash } },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when {
                                t.lastDeleted -> stringResource(R.string.chat_last_deleted)
                                t.lastKind == "sys" -> "⚙️"
                                t.lastKind == "image" -> "📷" + (if (t.lastText.isNotEmpty()) " " + t.lastText else "")
                                t.lastText.isNotEmpty() -> t.lastText
                                t.lastKind == "voice" -> "🎤"
                                t.lastKind == "sticker" -> "😊"
                                t.lastKind == "track" -> "🖼️"
                                else -> "🎬"
                            },
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
                    } else if (t.lastSeen && seenInList) {
                        // the other person read my last message (it never shows together with the unread count: that one is about their messages)
                        Text("👁", style = MaterialTheme.typography.titleMedium)
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
        "voice" -> "🎤 " + stringResource(R.string.chat_kind_voice)
        "image" -> "📷 " + stringResource(R.string.chat_kind_image)
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
    onOpenSettings: (String) -> Unit,
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
    var tempCounter by remember { mutableStateOf(0) }       // numbers for messages shown before the bot has answered (negative: they never clash with real ones)
    val haptic = rememberAppHaptic()
    var lastTypingSent by remember { mutableStateOf(0L) }
    val listState = rememberLazyListState()
    var selected by remember { mutableStateOf<ChatMsg?>(null) }
    var forwarding by remember { mutableStateOf<ChatMsg?>(null) }
    var stickerMsg by remember { mutableStateOf<ChatMsg?>(null) }
    var imageMsg by remember { mutableStateOf<ChatMsg?>(null) }              // picture open full screen
    var pendingImage by remember { mutableStateOf<Uri?>(null) }              // picked from the gallery, not sent yet
    var pendingPreview by remember { mutableStateOf<Bitmap?>(null) }
    var textSelectN by remember { mutableStateOf(-1) }
    val pins = remember { mutableStateListOf<PinInfo>() }
    var pinIdx by remember { mutableStateOf(0) }
    var rev by remember { mutableStateOf(0) }
    var deleteMode by remember { mutableStateOf("announce") }
    var proposal by remember { mutableStateOf<Boolean?>(null) }     // null: no request, true: mine, false: theirs
    var editing by remember { mutableStateOf<ChatMsg?>(null) }
    var pinning by remember { mutableStateOf<ChatMsg?>(null) }
    var deleting by remember { mutableStateOf<ChatMsg?>(null) }
    var emojiMore by remember { mutableStateOf<ChatMsg?>(null) }
    var seenUpTo by remember { mutableStateOf<Int?>(null) }
    var themeId by remember { mutableStateOf("default") }
    var nickname by remember { mutableStateOf("") }
    var disappearMs by remember { mutableStateOf(0L) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val hits = remember { mutableStateListOf<SearchHit>() }
    var jumped by remember { mutableStateOf(false) }
    var highlightN by remember { mutableStateOf(-1) }
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var recPaused by remember { mutableStateOf(false) }
    var limitReached by remember { mutableStateOf(false) }
    var recElapsed by remember { mutableStateOf(0L) }
    val amps = remember { mutableStateListOf<Float>() }
    var moreMenu by remember { mutableStateOf(false) }
    val menuStyle = remember { ChatPrefs.menuStyle(ctx) }
    val typingStyle = remember { ChatPrefs.typingStyle(ctx) }
    val clipboard = LocalClipboardManager.current
    val maxDrag = with(LocalDensity.current) { 72.dp.toPx() }

    // How many polls in a row brought nothing new. The longer it is quiet, the slower we ask (saves battery, data and the bot).
    var quietRounds by remember { mutableStateOf(0) }
    var showingSaved by remember { mutableStateOf(false) }

    // One answer from the bot, fresh or saved, put on screen. True when it brought something new.
    fun applyThread(body: JSONObject?): Boolean {
        var fresh = false
        val peer = body?.optJSONObject("peer")
        peerName = peer?.optString("name").orEmpty()
        peerHash = peer?.optString("hash").orEmpty().ifEmpty { hash }
        val arr = body?.optJSONArray("messages")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i)?.toChatMsg() ?: continue
                if (!jumped && !m.hidden && messages.none { it.n == m.n }) {
                    messages.add(m)
                    fresh = true
                }
            }
        }
        latest = maxOf(latest, body?.optInt("latest", latest) ?: latest)
        // old messages that were edited, reacted to or deleted, and ones that vanished without a trace
        val changes = body?.optJSONArray("changes")
        if (changes != null) {
            for (i in 0 until changes.length()) {
                val c = changes.optJSONObject(i)?.toChatMsg() ?: continue
                val idx = messages.indexOfFirst { it.n == c.n }
                if (idx >= 0) {
                    if (c.hidden) messages.removeAt(idx) else messages[idx] = c
                    fresh = true
                }
            }
        }
        val removedArr = body?.optJSONArray("removed")
        if (removedArr != null) {
            for (i in 0 until removedArr.length()) {
                val rn = removedArr.optInt(i)
                if (messages.removeAll { it.n == rn }) fresh = true
            }
        }
        rev = body?.optInt("rev", rev) ?: rev
        val pinArr = body?.optJSONArray("pins")
        if (pinArr != null) {
            val pinList = ArrayList<PinInfo>()
            for (i in 0 until pinArr.length()) {
                val p = pinArr.optJSONObject(i) ?: continue
                val pm = p.optJSONObject("message")?.toChatMsg() ?: continue
                pinList.add(PinInfo(p.optInt("n"), p.optLong("until"), p.optBoolean("mine"), pm))
            }
            pins.clear()
            pins.addAll(pinList)
        }
        val st = body?.optJSONObject("settings")
        if (st != null) {
            deleteMode = st.optString("deleteMode", "announce")
            val pr = st.optJSONObject("proposal")
            proposal = if (pr == null) null else pr.optBoolean("mine")
            themeId = st.optString("theme", "default")
            nickname = st.optString("nick")
            disappearMs = st.optLong("disappear", 0L)
        }
        canSend = body?.optBoolean("canSend", true) ?: true
        remaining = if (body != null && !body.isNull("remaining")) body.optInt("remaining") else null
        flow = body?.optJSONObject("flow")?.toFlow()
        seenUpTo = if (body == null || body.isNull("seenUpTo")) null else body.optInt("seenUpTo")
        peerTyping = body?.optBoolean("typing") == true
        error = ""
        return fresh
    }

    // the chat as it was last time, on screen at once while the fresh answer is on its way
    LaunchedEffect(hash) {
        if (messages.isEmpty() && latest == 0) {
            val saved = net.peek("/candy/dm/thread?hash=" + Uri.encode(bareHash(hash)) + "&after=0&rev=0")
            if (saved != null && messages.isEmpty() && latest == 0) {
                applyThread(saved)
                showingSaved = true
            }
        }
    }

    LaunchedEffect(messages.size) { quietRounds = 0 }

    // A tap from the bot (Events.dmTick) refreshes this at once. While the live line is open the regular round can be slow;
    // while the other person types it stays quick, because nothing taps us when they stop.
    PollingEffect(hash to Events.dmTick, { if (peerTyping) 1500L else if (Events.connected) 25_000L else if (quietRounds >= 6) 10_000L else 5000L }) {
        // while the saved copy is on screen, the first real answer is the whole chat again, and it replaces the copy
        val replacing = showingSaved
        val r = if (replacing) {
            net.get("/candy/dm/thread?hash=" + Uri.encode(bareHash(hash)) + "&after=0&rev=0")
        } else {
            net.get("/candy/dm/thread?hash=" + Uri.encode(bareHash(hash)) + "&after=" + latest + "&rev=" + rev)
        }
        if (r.ok) {
            if (replacing) {
                messages.clear()
                latest = 0
                rev = 0
                showingSaved = false
            }
            val fresh = applyThread(r.body)
            quietRounds = if (fresh || peerTyping) 0 else quietRounds + 1
        } else {
            error = r.message(ctx)
        }
    }

    LaunchedEffect(messages.size) {
        if (!jumped && messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
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

    // ───── search inside the chat, and jumping to an old message ─────
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            hits.clear()
            return@LaunchedEffect
        }
        delay(350)
        val r = net.get("/candy/dm/search?hash=" + Uri.encode(bareHash(hash)) + "&q=" + Uri.encode(searchQuery.trim()))
        if (r.ok) {
            val arr = r.body?.optJSONArray("hits")
            val fresh = ArrayList<SearchHit>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    fresh.add(SearchHit(o.optInt("n"), o.optBoolean("mine"), o.optString("text"), o.optLong("at")))
                }
            }
            hits.clear()
            hits.addAll(fresh)
        }
    }

    fun jumpTo(n: Int) {
        if (messages.any { it.n == n }) {
            searching = false
            searchQuery = ""
            hits.clear()
            highlightN = n
            return
        }
        scope.launch {
            val r = net.get("/candy/dm/thread?hash=" + Uri.encode(bareHash(hash)) + "&around=" + n)
            if (!r.ok) {
                error = r.message(ctx)
                return@launch
            }
            val arr = r.body?.optJSONArray("messages") ?: return@launch
            val fresh = ArrayList<ChatMsg>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i)?.toChatMsg() ?: continue
                if (!m.hidden) fresh.add(m)
            }
            jumped = true
            messages.clear()
            messages.addAll(fresh)
            searching = false
            searchQuery = ""
            hits.clear()
            highlightN = n
        }
    }

    LaunchedEffect(highlightN, messages.size) {
        if (highlightN > 0) {
            val idx = messages.indexOfFirst { it.n == highlightN }
            if (idx >= 0) {
                listState.scrollToItem(idx)
                delay(2200)
                highlightN = -1
            }
        }
    }

    // ───── pictures ─────
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pendingImage = uri
            editing = null
        }
    }
    LaunchedEffect(pendingImage) {
        val picked = pendingImage
        pendingPreview = if (picked == null) null else withContext(Dispatchers.IO) { ChatImagePreview.load(ctx, picked) }
    }

    // ───── voice messages ─────
    fun startRecording() {
        if (recorder.start()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            recording = true
            recPaused = false
            limitReached = false
            recElapsed = 0L
            amps.clear()
            error = ""
        } else {
            error = ctx.getString(R.string.voice_record_failed)
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else error = ctx.getString(R.string.voice_no_permission)
    }

    fun onMicClick() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun discardVoice() {
        recorder.cancel()
        recording = false
        recPaused = false
        limitReached = false
        amps.clear()
    }

    fun sendVoice() {
        val done = recorder.finish()
        recording = false
        recPaused = false
        limitReached = false
        amps.clear()
        if (done == null || done.second < 1000L) {
            done?.first?.delete()
            error = ctx.getString(R.string.voice_too_short)
            return
        }
        val quotedMsg = replyTo
        scope.launch {
            busy = true
            error = ""
            val bytes = withContext(Dispatchers.IO) { done.first.readBytes() }
            val up = net.post("/candy/dm/voice/upload", JSONObject().put("audio", Base64.encodeToString(bytes, Base64.NO_WRAP)))
            val voiceId = up.body?.optString("id").orEmpty()
            if (!up.ok || voiceId.isEmpty()) {
                busy = false
                error = up.message(ctx)
                done.first.delete()
                return@launch
            }
            val body = JSONObject().put("hash", bareHash(hash)).put("voice", JSONObject().put("id", voiceId).put("ms", done.second))
            if (quotedMsg != null) body.put("reply", quotedMsg.n)
            val r = net.post("/candy/dm/send", body)
            busy = false
            done.first.delete()
            if (r.ok) {
                r.body?.optJSONObject("message")?.toChatMsg()?.let { m ->
                    if (messages.none { it.n == m.n }) messages.add(m)
                    latest = maxOf(latest, m.n)
                }
                replyTo = null
            } else {
                error = r.message(ctx)
            }
        }
    }

    LaunchedEffect(recording, recPaused) {
        while (recording) {
            recElapsed = recorder.elapsedMs
            if (!recPaused) {
                amps.add((recorder.amplitude / 32767f).coerceIn(0f, 1f))
                if (amps.size > 40) amps.removeAt(0)
                if (recElapsed >= VOICE_MAX_MS) {
                    recorder.pause()
                    recPaused = true
                    limitReached = true
                }
            }
            delay(100)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
            VoicePlayer.stop()
        }
    }

    // no notification for the chat you are looking at, and its old one goes away
    DisposableEffect(hash) {
        PushState.openChat = bareHash(hash)
        Push.cancel(ctx, hash)
        onDispose { if (PushState.openChat == bareHash(hash)) PushState.openChat = null }
    }

    fun replaceMsg(m: ChatMsg) {
        val idx = messages.indexOfFirst { it.n == m.n }
        if (idx >= 0) messages[idx] = m
    }

    fun react(m: ChatMsg, emoji: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch {
            val r = net.post("/candy/dm/react", JSONObject().put("hash", bareHash(hash)).put("n", m.n).put("emoji", emoji))
            if (r.ok) r.body?.optJSONObject("message")?.toChatMsg()?.let { replaceMsg(it) } else error = r.message(ctx)
        }
    }

    fun deleteMsg(m: ChatMsg) {
        scope.launch {
            val r = net.post("/candy/dm/delete", JSONObject().put("hash", bareHash(hash)).put("n", m.n))
            if (r.ok) {
                if (r.body?.optString("mode") == "silent") {
                    messages.removeAll { it.n == m.n }
                } else {
                    replaceMsg(
                        m.copy(
                            text = "", deleted = true, loopId = null, stickerId = null, trackId = null,
                            replyN = 0, myReaction = null, theirReaction = null, edited = false, voiceMs = null, hasImage = false,
                        ),
                    )
                }
                pins.removeAll { it.n == m.n }
            } else {
                error = r.message(ctx)
            }
        }
    }

    fun hideMsg(m: ChatMsg) {
        scope.launch {
            val r = net.post("/candy/dm/hide", JSONObject().put("hash", bareHash(hash)).put("n", m.n))
            if (r.ok) messages.removeAll { it.n == m.n } else error = r.message(ctx)
        }
    }

    fun pinMsg(m: ChatMsg, duration: String) {
        scope.launch {
            val r = net.post("/candy/dm/pin", JSONObject().put("hash", bareHash(hash)).put("n", m.n).put("duration", duration))
            if (r.ok) {
                val ms = when (duration) {
                    "1h" -> 3_600_000L
                    "24h" -> 86_400_000L
                    "7d" -> 604_800_000L
                    else -> 2_592_000_000L
                }
                pins.removeAll { it.n == m.n }
                pins.add(PinInfo(m.n, System.currentTimeMillis() + ms, true, m))
            } else {
                error = r.message(ctx)
            }
        }
    }

    fun unpinMsg(m: ChatMsg) {
        scope.launch {
            val r = net.post("/candy/dm/unpin", JSONObject().put("hash", bareHash(hash)).put("n", m.n))
            if (r.ok) pins.removeAll { it.n == m.n } else error = r.message(ctx)
        }
    }

    fun applySettings(body: JSONObject?) {
        val st = body?.optJSONObject("settings") ?: return
        deleteMode = st.optString("deleteMode", "announce")
        val pr = st.optJSONObject("proposal")
        proposal = if (pr == null) null else pr.optBoolean("mine")
    }

    fun answerDeleteMode(accept: Boolean) {
        scope.launch {
            val r = net.post("/candy/dm/deletemode/answer", JSONObject().put("hash", bareHash(hash)).put("accept", accept))
            if (r.ok) applySettings(r.body) else error = r.message(ctx)
        }
    }

    // a picture from the gallery: it is shrunk on the phone, uploaded, then sent as a message (the text box becomes its caption)
    fun sendPicture(uri: Uri) {
        if (busy) return
        val caption = input.trim()
        val quoted = replyTo
        scope.launch {
            busy = true
            error = ""
            val bytes = withContext(Dispatchers.IO) { PostImagePrep.prepare(ctx, uri) }
            if (bytes == null) {
                busy = false
                error = ctx.getString(R.string.chat_image_failed)
                return@launch
            }
            val up = net.post("/candy/dm/image/upload", JSONObject().put("image", Base64.encodeToString(bytes, Base64.NO_WRAP)))
            val imageId = up.body?.optString("id").orEmpty()
            if (!up.ok || imageId.isEmpty()) {
                busy = false
                error = up.message(ctx)
                return@launch
            }
            val body = JSONObject().put("hash", bareHash(hash)).put("text", caption).put("image", JSONObject().put("id", imageId))
            if (quoted != null) body.put("reply", quoted.n)
            val r = net.post("/candy/dm/send", body)
            busy = false
            if (r.ok) {
                pendingImage = null
                input = ""
                replyTo = null
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

    fun send(sticker: String? = null) {
        val picked = pendingImage
        if (picked != null && sticker == null && editing == null) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            sendPicture(picked)
            return
        }
        if (sticker != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        // editing a message of mine: the same box, but it changes the old message instead of sending a new one
        val editTarget = editing
        if (editTarget != null && sticker == null) {
            val newText = input.trim()
            if (newText.isEmpty() || busy) return
            scope.launch {
                busy = true
                val r = net.post("/candy/dm/edit", JSONObject().put("hash", bareHash(hash)).put("n", editTarget.n).put("text", newText))
                busy = false
                if (r.ok) {
                    r.body?.optJSONObject("message")?.toChatMsg()?.let { replaceMsg(it) }
                    input = ""
                    editing = null
                    error = ""
                } else {
                    error = r.message(ctx)
                }
            }
            return
        }
        val text = if (sticker == null) input.trim() else ""
        if ((text.isEmpty() && sticker == null) || busy) return
        val quoted = replyTo
        // A text message shows at once, as sent, and the box empties; the real message replaces it when the bot answers.
        // If sending fails it goes back into the box, so nothing typed is lost.
        if (sticker == null) tempCounter -= 1
        val temp: ChatMsg? = if (sticker == null) {
            ChatMsg(
                n = tempCounter, mine = true, text = text, loopId = null, loopOwnerHash = "", loopOwnerName = "", loopGone = false,
                stickerId = null, replyN = quoted?.n ?: 0, replyText = quoted?.text.orEmpty(), replyMine = quoted?.mine ?: false,
                replyKind = "", trackId = null, trackOwnerHash = "", trackOwnerName = "", trackCaption = "", trackHasImage = false,
                trackGone = false, at = System.currentTimeMillis(), edited = false, deleted = false, myReaction = null,
                theirReaction = null, forwarded = false, hidden = false, sys = null, sysValue = "", voiceMs = null,
            )
        } else {
            null
        }
        if (temp != null) {
            messages.add(temp)
            input = ""
            replyTo = null
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        scope.launch {
            busy = true
            val body = JSONObject().put("hash", bareHash(hash)).put("text", text)
            if (sticker != null) body.put("sticker", sticker)
            if (quoted != null) body.put("reply", quoted.n)
            val r = net.post("/candy/dm/send", body)
            busy = false
            if (temp != null) messages.removeAll { it.n == temp.n }
            if (r.ok) {
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
                if (temp != null) {
                    input = text
                    replyTo = quoted
                }
                if (r.status == 403) canSend = false
            }
        }
    }

    fun flowAction(path: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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

    val shownName = nickname.ifBlank { peerName.ifBlank { peerHash } }
    val theme = chatThemeOf(themeId)

    Column(
        Modifier
            .fillMaxSize()
            .then(
                if (theme.tint != null) {
                    Modifier.background(Brush.verticalGradient(listOf(theme.tint.copy(alpha = 0.28f), Color.Transparent)))
                } else {
                    Modifier
                },
            )
            .imePadding(),
    ) {
        val sel = selected
        if (searching) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { searching = false; searchQuery = ""; hits.clear() }) {
                    Text("✕", style = MaterialTheme.typography.titleLarge)
                }
                Field(stringResource(R.string.chat_search_hint), searchQuery, { searchQuery = it.take(40) }, modifier = Modifier.weight(1f))
            }
        } else if (sel != null && menuStyle == "bar") {
            // a message is selected: the bar on top turns into its actions, with the reactions under it
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = { selected = null }) { Text("✕", style = MaterialTheme.typography.titleLarge) }
                    Text("1", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (!sel.deleted) {
                        TextButton(onClick = { replyTo = sel; selected = null }) { Text("↩", style = MaterialTheme.typography.titleLarge) }
                        TextButton(onClick = { copyMsg(sel); selected = null }, enabled = sel.text.isNotEmpty()) {
                            Text("📋", style = MaterialTheme.typography.titleLarge)
                        }
                        TextButton(onClick = { forwarding = sel; selected = null }, enabled = sel.voiceMs == null && !sel.hasImage) {
                            Text("↪", style = MaterialTheme.typography.titleLarge)
                        }
                        TextButton(onClick = { deleting = sel; selected = null }) { Text("🗑", style = MaterialTheme.typography.titleLarge) }
                        Box {
                            TextButton(onClick = { moreMenu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                if (sel.text.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.msg_action_select_text)) },
                                        onClick = { moreMenu = false; textSelectN = sel.n; selected = null },
                                    )
                                }
                                ExtraMenuItems(
                                    m = sel,
                                    pinned = pins.any { it.n == sel.n },
                                    onEdit = { moreMenu = false; editing = sel; input = sel.text; replyTo = null; selected = null },
                                    onPin = { moreMenu = false; pinning = sel; selected = null },
                                    onUnpin = { moreMenu = false; unpinMsg(sel); selected = null },
                                    onDelete = { moreMenu = false; deleting = sel; selected = null },
                                    showDelete = false,
                                )
                            }
                        }
                    }
                }
                if (!sel.deleted) {
                    ReactionRow(
                        onPick = { e -> react(sel, e); selected = null },
                        onMore = { emojiMore = sel; selected = null },
                    )
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
                    Modifier.weight(1f).clickable { onOpenSettings(peerHash) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Avatar(peerHash, net.token, 36.dp)
                    Column {
                        Text(shownName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (peerTyping && typingStyle == "text") {
                            Text(stringResource(R.string.chat_typing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (disappearMs > 0L && !(peerTyping && typingStyle == "text")) {
                            Text("⏳ " + durationText(disappearMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = { searching = true }) { Text("🔍", style = MaterialTheme.typography.titleLarge) }
            }
        }

        // the other person asked for silent deleting
        if (proposal == false) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.msg_delete_proposal_theirs, shownName), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { answerDeleteMode(true) }) { Text(stringResource(R.string.msg_accept)) }
                        TextButton(onClick = { answerDeleteMode(false) }) { Text(stringResource(R.string.msg_decline)) }
                    }
                }
            }
        }

        // pinned messages: tap to jump to the next one
        if (pins.isNotEmpty()) {
            val pin = pins[pinIdx % pins.size]
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clickable {
                        val idx = messages.indexOfFirst { it.n == pin.n }
                        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) } else jumpTo(pin.n)
                        pinIdx = (pinIdx + 1) % pins.size
                    },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("📌")
                    Text(
                        msgPreview(pin.msg),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (pins.size > 1) Muted((pinIdx % pins.size + 1).toString() + "/" + pins.size)
                }
            }
        }

        val f = flow
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (f == null) {
                    TextButton(onClick = { flowAction("/candy/flow/request") }) {
                        Text(ChatPrefs.flowEmoji(ctx) + " " + stringResource(R.string.flow_start))
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
                        Text(ChatPrefs.flowEmoji(ctx) + " " + f.streak, style = MaterialTheme.typography.titleLarge)
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

        if (searching && searchQuery.isNotBlank()) {
            SearchResults(hits, shownName, { n -> jumpTo(n) }, Modifier.weight(1f).fillMaxWidth())
        } else {
        // my last message the other person has read gets the little eye
        val lastSeenN = if (ChatPrefs.seenPlace(ctx) != "chat") null
        else seenUpTo?.let { up -> messages.lastOrNull { it.mine && !it.deleted && it.sys == null && it.n > 0 && it.n <= up }?.n }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages) { m ->
                if (m.sys != null) {
                    SystemNote(m, shownName)
                    return@items
                }
                val isSel = selected?.n == m.n
                var drag by remember(m.n) { mutableStateOf(0f) }
                var dragging by remember(m.n) { mutableStateOf(false) }
                val shownDrag by animateFloatAsState(if (dragging) drag else 0f, label = "swipe")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if ((isSel && menuStyle == "bar") || highlightN == m.n) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
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
                      Column(horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
                        if (m.deleted) {
                            Text(
                                if (m.mine) stringResource(R.string.msg_deleted_mine) else stringResource(R.string.msg_deleted_theirs, shownName),
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        } else {
                        val stickerId = m.stickerId
                        val quoteWho = if (m.replyMine) stringResource(R.string.nav_me) else shownName
                        if (stickerId != null) {
                            Column(horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
                                if (m.forwarded) ForwardedLabel(MaterialTheme.colorScheme.onSurfaceVariant)
                                if (m.replyN > 0) QuoteBlock(m, quoteWho, MaterialTheme.colorScheme.onSurface)
                                StickerImage(
                                    stickerId, net.token, 120.dp,
                                    Modifier.combinedClickable(onClick = { stickerMsg = m }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); selected = m }),
                                )
                            }
                        } else {
                            Column(
                                Modifier
                                    .widthIn(max = 260.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .combinedClickable(onClick = {}, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); selected = m })
                                    .background(if (m.mine) (theme.mine ?: MaterialTheme.colorScheme.primary) else MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val textColor = if (m.mine) (theme.onMine ?: MaterialTheme.colorScheme.onPrimary) else MaterialTheme.colorScheme.onSurface
                                if (m.forwarded) ForwardedLabel(textColor.copy(alpha = 0.8f))
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
                                                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); selected = m },
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
                                                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); selected = m },
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
                                val vms = m.voiceMs
                                if (vms != null) VoiceBubble(net, peerHash, m.n, vms, textColor)
                                if (m.hasImage) {
                                    ChatImage(
                                        net = net,
                                        peerHash = peerHash,
                                        n = m.n,
                                        w = m.imageW,
                                        h = m.imageH,
                                        onClick = { imageMsg = m },
                                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); selected = m },
                                    )
                                }
                                if (m.text.isNotEmpty()) {
                                    if (textSelectN == m.n) {
                                        SelectionContainer { Text(m.text, color = textColor) }
                                    } else {
                                        Text(m.text, color = textColor)
                                    }
                                }
                                if (m.edited) {
                                    Text(stringResource(R.string.msg_edited), color = textColor.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        ReactionChips(m) { react(m, m.myReaction.orEmpty()) }
                        if (m.mine && m.n == lastSeenN) {
                            Text("👁", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
                        }
                        }
                      }
                    }
                    DropdownMenu(expanded = isSel && menuStyle == "popup" && !m.deleted, onDismissRequest = { selected = null }) {
                        ReactionRow(
                            onPick = { e -> react(m, e); selected = null },
                            onMore = { emojiMore = m; selected = null },
                        )
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
                        if (m.voiceMs == null && !m.hasImage) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.msg_action_forward)) },
                                onClick = { forwarding = m; selected = null },
                            )
                        }
                        ExtraMenuItems(
                            m = m,
                            pinned = pins.any { it.n == m.n },
                            onEdit = { editing = m; input = m.text; replyTo = null; selected = null },
                            onPin = { pinning = m; selected = null },
                            onUnpin = { unpinMsg(m); selected = null },
                            onDelete = { deleting = m; selected = null },
                            showDelete = true,
                        )
                    }
                }
            }
            if (peerTyping && typingStyle == "bubble") {
                item { TypingBubble() }
            }
        }

        }

        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ErrorText(error)
            if (jumped) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.clickable {
                        messages.clear()
                        latest = 0
                        jumped = false
                    },
                ) {
                    Text(
                        "⬇  " + stringResource(R.string.chat_back_latest),
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
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
                val ed = editing
                if (ed != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Muted("✏️ " + stringResource(R.string.msg_editing) + ": " + ed.text.take(40), Modifier.weight(1f))
                        TextButton(onClick = { editing = null; input = "" }) { Text("✕") }
                    }
                }
                if (quoted != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Muted(
                            stringResource(R.string.chat_replying, if (quoted.mine) stringResource(R.string.nav_me) else shownName) + ": " +
                                (if (quoted.stickerId != null) "😊" else if (quoted.loopId != null) "🎬" else if (quoted.hasImage) "📷 " + quoted.text.take(30) else quoted.text.take(40)),
                            Modifier.weight(1f),
                        )
                        TextButton(onClick = { replyTo = null }) { Text("✕") }
                    }
                }
                if (recording) {
                    if (limitReached) Muted(stringResource(R.string.voice_limit))
                    RecordingBar(
                        elapsedMs = recElapsed,
                        amps = amps,
                        paused = recPaused,
                        limitReached = limitReached,
                        onDelete = { discardVoice() },
                        onTogglePause = {
                            if (recPaused) {
                                recorder.resume()
                                recPaused = false
                            } else {
                                recorder.pause()
                                recPaused = true
                            }
                        },
                        onSend = { sendVoice() },
                    )
                } else {
                if (pendingImage != null) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val preview = pendingPreview
                        if (preview != null) {
                            Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Muted(stringResource(R.string.chat_image_ready), Modifier.weight(1f))
                        TextButton(onClick = { pendingImage = null }) { Text("✕") }
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
                            TextButton(onClick = { pickImage.launch("image/*") }) { Text("🖼️", style = MaterialTheme.typography.titleLarge) }
                            TextButton(onClick = { onFlare(peerHash) }) { Text("📸", style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                    val canPost = !busy && (input.isNotBlank() || pendingImage != null)
                    val showMic = input.isBlank() && editing == null && pendingImage == null
                    Surface(
                        shape = CircleShape,
                        color = if (canPost || showMic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(48.dp).clickable(enabled = canPost || (showMic && !busy)) { if (showMic) onMicClick() else send() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (showMic) "🎙" else "↑",
                                style = MaterialTheme.typography.titleLarge,
                                color = if (canPost || showMic) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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

    val pinTarget = pinning
    if (pinTarget != null) {
        AlertDialog(
            onDismissRequest = { pinning = null },
            title = { Text(stringResource(R.string.msg_pin_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for ((key, label) in listOf("1h" to R.string.msg_pin_1h, "24h" to R.string.msg_pin_24h, "7d" to R.string.msg_pin_7d, "30d" to R.string.msg_pin_30d)) {
                        TextButton(onClick = { pinMsg(pinTarget, key); pinning = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(label))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pinning = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    val delTarget = deleting
    if (delTarget != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.msg_action_delete)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (delTarget.mine) {
                        TextButton(onClick = { deleteMsg(delTarget); deleting = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.msg_delete_everyone))
                        }
                    }
                    TextButton(onClick = { hideMsg(delTarget); deleting = null }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.msg_delete_me))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    val emojiTarget = emojiMore
    if (emojiTarget != null) {
        EmojiPickerDialog(onPick = { e -> react(emojiTarget, e); emojiMore = null }, onDismiss = { emojiMore = null })
    }


    val viewing = imageMsg
    if (viewing != null) ImageViewer(net, peerHash, viewing.n) { imageMsg = null }

    val sm = stickerMsg
    val smSticker = sm?.stickerId
    if (sm != null && smSticker != null) {
        ModalBottomSheet(
            onDismissRequest = { stickerMsg = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StickerImage(smSticker, net.token, 240.dp)
                StickerSheetActions(net, smSticker)
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
            val body = JSONObject().put("hash", bareHash(hash)).put("text", m.text).put("fwd", true)
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

/** The quick reactions in one row, with a plus for the rest. */
@Composable
private fun ReactionRow(onPick: (String) -> Unit, onMore: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (e in QUICK_REACTIONS) {
                Text(e, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable { onPick(e) }.padding(6.dp))
            }
            Text("＋", fontSize = 24.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onMore).padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

/** The reactions under a message. Tapping yours takes it back. */
@Composable
private fun ReactionChips(m: ChatMsg, onToggleMine: () -> Unit) {
    val theirs = m.theirReaction
    val mine = m.myReaction
    if (theirs == null && mine == null) return
    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (theirs != null) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(theirs, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
        if (mine != null) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.clickable(onClick = onToggleMine),
            ) {
                Text(mine, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
    }
}

/** Edit, pin and delete: the actions that depend on whose message it is. */
@Composable
private fun ExtraMenuItems(
    m: ChatMsg,
    pinned: Boolean,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onDelete: () -> Unit,
    showDelete: Boolean,
) {
    if (canEdit(m)) {
        DropdownMenuItem(text = { Text("✏️  " + stringResource(R.string.msg_action_edit)) }, onClick = onEdit)
    }
    if (pinned) {
        DropdownMenuItem(text = { Text("📌  " + stringResource(R.string.msg_action_unpin)) }, onClick = onUnpin)
    } else {
        DropdownMenuItem(text = { Text("📌  " + stringResource(R.string.msg_action_pin)) }, onClick = onPin)
    }
    if (showDelete) {
        DropdownMenuItem(text = { Text("🗑  " + stringResource(R.string.msg_action_delete)) }, onClick = onDelete)
    }
}

@Composable
private fun EmojiPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msg_react_more)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (row in MORE_REACTIONS.chunked(6)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (e in row) {
                            Text(e, fontSize = 28.sp, modifier = Modifier.clip(CircleShape).clickable { onPick(e) }.padding(6.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun ForwardedLabel(color: Color) {
    Text(
        "↪ " + stringResource(R.string.msg_forwarded),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontStyle = FontStyle.Italic,
    )
}

/** A line in the middle of the chat: someone changed the theme or disappearing messages. */
@Composable
private fun SystemNote(m: ChatMsg, peerName: String) {
    val who = if (m.mine) stringResource(R.string.msg_you) else peerName
    val text = when (m.sys) {
        "theme" -> stringResource(R.string.msg_sys_theme, who, stringResource(chatThemeOf(m.sysValue).label))
        "disappear" -> {
            // the value is "sent:300000" or "read:300000" (older ones are just the number)
            val parts = m.sysValue.split(":")
            val mode = if (parts.size == 2) parts[0] else "sent"
            val ms = (if (parts.size == 2) parts[1] else parts[0]).toLongOrNull() ?: 0L
            if (ms <= 0L) {
                stringResource(R.string.msg_sys_disappear_off, who)
            } else {
                stringResource(
                    if (mode == "read") R.string.msg_sys_disappear_on_read else R.string.msg_sys_disappear_on_sent,
                    who,
                    durationText(ms),
                )
            }
        }
        else -> ""
    }
    if (text.isNotEmpty()) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What searching inside the chat found, newest first. Tap one to jump to it. */
@Composable
private fun SearchResults(hits: List<SearchHit>, peerName: String, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LazyColumn(
        modifier = modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (hits.isEmpty()) item { Muted(stringResource(R.string.chat_search_none)) }
        items(hits) { h ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().clickable { onPick(h.n) },
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Muted((if (h.mine) stringResource(R.string.msg_you) else peerName) + " · " + agoShort(ctx, h.at))
                    Text(h.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
