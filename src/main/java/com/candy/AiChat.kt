package com.candy

import android.content.Context
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import kotlin.random.Random
import org.json.JSONObject

/** One message in the conversation with Candy. A negative [n] means it was just sent and the bot has not answered yet. */
private class AiMsg(
    val n: Long,
    val role: String,
    val text: String,
    val at: Long,
    val memAdded: List<String> = emptyList(),
    val memRemoved: List<String> = emptyList(),
) {
    val mine: Boolean get() = role == "user"
}

private fun JSONObject.toAiMsg(): AiMsg {
    val mem = optJSONObject("mem")
    val added = ArrayList<String>()
    mem?.optJSONArray("a")?.let { a ->
        for (i in 0 until a.length()) a.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }?.let { added.add(it) }
    }
    val removed = ArrayList<String>()
    mem?.optJSONArray("r")?.let { a ->
        for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { removed.add(it) }
    }
    return AiMsg(optLong("n"), optString("role"), optString("text"), optLong("at"), added, removed)
}

private fun JSONArray?.toAiMsgs(): List<AiMsg> {
    if (this == null) return emptyList()
    val out = ArrayList<AiMsg>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it.toAiMsg()) }
    return out
}

/** Candy writes light markdown: **bold** and "- " lists. That is all this understands. */
private fun aiRich(text: String, bold: SpanStyle): AnnotatedString = buildAnnotatedString {
    val lines = text.split("\n")
    for ((li, raw) in lines.withIndex()) {
        val line = if (raw.startsWith("- ") || raw.startsWith("* ")) "• " + raw.substring(2) else raw
        var i = 0
        while (i < line.length) {
            val s = line.indexOf("**", i)
            val e = if (s >= 0) line.indexOf("**", s + 2) else -1
            if (s < 0 || e < 0) {
                append(line.substring(i))
                break
            }
            append(line.substring(i, s))
            withStyle(bold) { append(line.substring(s + 2, e)) }
            i = e + 2
        }
        if (li < lines.lastIndex) append("\n")
    }
}

private fun leftText(ctx: Context, until: Long): String {
    val mins = ((until - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1L)
    return ctx.getString(R.string.ai_hours_min, (mins / 60).toInt(), (mins % 60).toInt())
}

private fun aiDayLabel(ctx: Context, t: Long): String = when {
    DateUtils.isToday(t) -> ctx.getString(R.string.chat_today)
    DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> ctx.getString(R.string.chat_yesterday)
    else -> DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
}

private fun sameDay(a: Long, b: Long): Boolean {
    val day = DateUtils.DAY_IN_MILLIS
    val tz = java.util.TimeZone.getDefault()
    return (a + tz.getOffset(a)) / day == (b + tz.getOffset(b)) / day
}

/** The plan the bot reports: "plus", "pro", "elite", or empty. */
private fun tierOf(o: JSONObject?): String = if (o == null || o.isNull("tier")) "" else o.optString("tier")

// Where the eye and mouth layers sit on the 1254x1254 art, as fractions of its side.
private const val ART = 1254f
private const val EYES_X = 560f / ART
private const val EYES_Y = 270f / ART
private const val EYES_W = 370f / ART
private const val EYES_H = 180f / ART
private const val MOUTH_X = 680f / ART
private const val MOUTH_Y = 448f / ART
private const val MOUTH_W = 120f / ART
private const val MOUTH_H = 82f / ART

private enum class Eyes(val res: Int?) {
    Open(null), Half(R.drawable.candy_eyes_half), Closed(R.drawable.candy_eyes_closed)
}

private enum class Mouth(val res: Int?) {
    Closed(null), Oo(R.drawable.candy_mouth_oo), Slight(R.drawable.candy_mouth_slight), Ah(R.drawable.candy_mouth_ah)
}

/**
 * Candy and her café as the chat's wallpaper, pinned to the top and fading into the chat.
 * Plus: a still picture. Pro and Elite: she breathes, blinks, and her mouth moves while she answers
 * ([talkTick] goes up on every reply and [talkMs] is how long she keeps talking).
 */
@Composable
private fun CandyStage(animated: Boolean, talkTick: Int, talkMs: Long) {
    var eyes by remember { mutableStateOf(Eyes.Open) }
    var mouth by remember { mutableStateOf(Mouth.Closed) }
    val bg = MaterialTheme.colorScheme.background

    val breathe: State<Float> = if (animated) {
        rememberInfiniteTransition(label = "candy-breathe").animateFloat(
            initialValue = 1f,
            targetValue = 1.014f,
            animationSpec = infiniteRepeatable(tween(3800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "candy-breathe-scale",
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    if (animated) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(Random.nextLong(2500L, 6500L))
                eyes = Eyes.Half
                delay(60)
                eyes = Eyes.Closed
                delay(90)
                eyes = Eyes.Half
                delay(60)
                eyes = Eyes.Open
            }
        }
        LaunchedEffect(talkTick) {
            if (talkTick > 0) {
                val shapes = listOf(Mouth.Slight, Mouth.Oo, Mouth.Ah, Mouth.Slight, Mouth.Closed, Mouth.Ah, Mouth.Oo)
                val end = System.currentTimeMillis() + talkMs
                var i = 0
                while (System.currentTimeMillis() < end) {
                    mouth = shapes[i % shapes.size]
                    i++
                    delay(130)
                }
                mouth = Mouth.Closed
            }
        }
    }

    // the art is not mirrored for right-to-left languages, so its pieces line up
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxSize().background(bg).clipToBounds()) {
            val side = maxWidth * 1.15f
            val left = (maxWidth / 2 - side * 0.56f).coerceIn(maxWidth - side, 0.dp)
            Box(Modifier.align(Alignment.TopStart).requiredSize(side).offset(x = left)) {
                Image(
                    painter = painterResource(R.drawable.candy_stage_bg),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = breathe.value
                        scaleY = breathe.value
                        transformOrigin = TransformOrigin(0.55f, 1f)
                    },
                ) {
                    Image(
                        painter = painterResource(R.drawable.candy_stage_body),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                    eyes.res?.let { r ->
                        Image(
                            painter = painterResource(r),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.offset(x = side * EYES_X, y = side * EYES_Y).requiredSize(side * EYES_W, side * EYES_H),
                        )
                    }
                    mouth.res?.let { r ->
                        Image(
                            painter = painterResource(r),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.offset(x = side * MOUTH_X, y = side * MOUTH_Y).requiredSize(side * MOUTH_W, side * MOUTH_H),
                        )
                    }
                }
                // the bottom of the picture melts into the chat
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(side * 0.38f)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, bg))),
                )
            }
        }
    }
}

@Composable
private fun AiAvatar(size: Dp) {
    Image(
        painter = painterResource(R.drawable.candy_ai_avatar),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(CircleShape),
    )
}

@Composable
private fun AiBadge() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            stringResource(R.string.ai_badge),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
        )
    }
}

/** The row pinned above everything in the Chats tab. Shows Candy's last message under her name. */
@Composable
fun CandyAiRow(net: Net, refreshKey: Int, onClick: () -> Unit) {
    var last by remember { mutableStateOf("") }
    LaunchedEffect(refreshKey) {
        val r = net.get("/candy/ai/thread?limit=1")
        if (r.ok) last = r.body?.optJSONArray("messages")?.optJSONObject(0)?.optString("text").orEmpty()
    }
    CandyCard(Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onClick)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AiAvatar(52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.ai_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    AiBadge()
                }
                Text(
                    last.ifBlank { stringResource(R.string.ai_row_hint) }.replace("\n", " "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AiTypingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        AiAvatar(28.dp)
        Spacer(Modifier.size(6.dp))
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("• • •", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.ai_thinking), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AiBubble(m: AiMsg) {
    val big = 20.dp
    val small = 6.dp
    val cs = MaterialTheme.colorScheme
    val boldSpan = SpanStyle(fontWeight = FontWeight.Bold)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!m.mine) {
            AiAvatar(28.dp)
            Spacer(Modifier.size(6.dp))
        }
        if (m.mine) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(topStart = big, topEnd = big, bottomEnd = small, bottomStart = big))
                    .background(Brush.linearGradient(listOf(cs.primary, lerp(cs.primary, cs.tertiary, 0.5f))))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text(m.text, color = cs.onPrimary, style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(topStart = big, topEnd = big, bottomEnd = big, bottomStart = small))
                    .background(cs.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                SelectionContainer {
                    Text(aiRich(m.text, boldSpan), color = cs.onSurface, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun MemChip(label: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** "Saved to memory: ..." under the reply that changed Candy's memory. */
@Composable
private fun AiMemChips(m: AiMsg, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 34.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in m.memAdded) MemChip("🧠 " + stringResource(R.string.ai_mem_saved, t), onClick)
        for (t in m.memRemoved) MemChip("🧠 " + stringResource(R.string.ai_mem_removed, t), onClick)
    }
}

@Composable
private fun AiDayChip(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** The conversation with Candy: last 200 messages kept by the bot, the model sees the last 20. */
@Composable
fun AiChatScreen(net: Net, onBack: () -> Unit, onOpenMemory: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgs = remember { mutableStateListOf<AiMsg>() }
    var loading by remember { mutableStateOf(true) }
    var hasMore by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var melt by remember { mutableStateOf(0) }
    var lockedUntil by remember { mutableStateOf(0L) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var tier by remember { mutableStateOf("") }
    var talkTick by remember { mutableStateOf(0) }
    var talkMs by remember { mutableStateOf(0L) }
    val listState = rememberLazyListState()
    val reduceMotion = remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val themed = tier == "plus" || tier == "pro" || tier == "elite"
    val animated = (tier == "pro" || tier == "elite") && !reduceMotion

    fun applyMelt(o: JSONObject?) {
        melt = o?.optInt("pct") ?: 0
        lockedUntil = o?.optLong("lockedUntil") ?: 0L
    }

    fun reload() {
        scope.launch {
            val r = net.get("/candy/ai/thread?limit=60")
            loading = false
            if (r.ok) {
                msgs.clear()
                msgs.addAll(r.body?.optJSONArray("messages").toAiMsgs())
                hasMore = r.body?.optBoolean("hasMore") == true
                applyMelt(r.body?.optJSONObject("melt"))
                tier = tierOf(r.body)
                error = ""
            } else {
                error = r.message(ctx)
            }
        }
    }

    fun older() {
        val first = msgs.firstOrNull { it.n > 0 } ?: return
        scope.launch {
            val r = net.get("/candy/ai/thread?limit=60&before=${first.n}")
            if (r.ok) {
                msgs.addAll(0, r.body?.optJSONArray("messages").toAiMsgs())
                hasMore = r.body?.optBoolean("hasMore") == true
            }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        sending = true
        error = ""
        val tempN = -System.currentTimeMillis()
        msgs.add(AiMsg(tempN, "user", text, System.currentTimeMillis()))
        input = ""
        scope.launch {
            val r = net.post("/candy/ai/send", JSONObject().put("text", text), readTimeoutMs = 60_000)
            sending = false
            val idx = msgs.indexOfFirst { it.n == tempN }
            if (r.ok) {
                val u = r.body?.optJSONObject("user")?.toAiMsg()
                val a = r.body?.optJSONObject("reply")?.toAiMsg()
                if (idx >= 0) msgs[idx] = u ?: AiMsg(tempN, "user", text, System.currentTimeMillis())
                if (a != null) {
                    msgs.add(a)
                    talkMs = (a.text.length * 45L).coerceIn(1200L, 5000L)
                    talkTick++
                }
                applyMelt(r.body?.optJSONObject("melt"))
                return@launch
            }
            if (idx >= 0) msgs.removeAt(idx)
            if (r.status == 0) {
                // the connection dropped: the answer may still have been saved, so look before giving the text back
                val check = net.get("/candy/ai/thread?limit=60")
                if (check.ok) {
                    val fresh = check.body?.optJSONArray("messages").toAiMsgs()
                    val arrived = fresh.size >= 2 && fresh.last().role == "assistant" && fresh[fresh.size - 2].text == text
                    msgs.clear()
                    msgs.addAll(fresh)
                    hasMore = check.body?.optBoolean("hasMore") == true
                    applyMelt(check.body?.optJSONObject("melt"))
                    if (!arrived) {
                        input = text
                        error = r.message(ctx)
                    }
                    return@launch
                }
            }
            input = text
            error = r.message(ctx)
            if (r.body?.optString("code") == "ai_locked") lockedUntil = r.body?.optLong("until") ?: 0L
        }
    }

    fun clearAll() {
        scope.launch {
            val r = net.post("/candy/ai/clear")
            if (r.ok) {
                msgs.clear()
                hasMore = false
                error = ""
            } else {
                error = r.message(ctx)
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    // keep the newest message in view
    LaunchedEffect(msgs.size, sending) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    val locked = lockedUntil > System.currentTimeMillis()
    val cs = MaterialTheme.colorScheme

    Box(Modifier.fillMaxSize()) {
    if (themed) CandyStage(animated, talkTick, talkMs)
    Column(Modifier.fillMaxSize().imePadding()) {
        // header
        Row(
            Modifier.fillMaxWidth()
                .background(if (themed) cs.background.copy(alpha = 0.55f) else Color.Transparent)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), onBack)
            AiAvatar(40.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.ai_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    AiBadge()
                }
                Text(
                    stringResource(R.string.ai_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Box {
                AppIconButton(Icons.Rounded.MoreVert, null, { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_mem_menu)) },
                        onClick = { menu = false; onOpenMemory() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_new_chat)) },
                        onClick = { menu = false; confirmClear = true },
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CandyBrand.hairline()))

        if (melt >= 60 && !locked) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Muted(stringResource(R.string.ai_melt, melt))
                LinearProgressIndicator(progress = { (melt / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }

        // messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
        ) {
            if (hasMore) {
                item(key = "older") {
                    TextButton(onClick = { older() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ai_load_older))
                    }
                }
            }
            if (!loading && msgs.isEmpty() && error.isBlank()) {
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 36.dp)
                            .background(if (themed) cs.background.copy(alpha = 0.88f) else Color.Transparent, MaterialTheme.shapes.extraLarge)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AiAvatar(96.dp)
                        Text(
                            stringResource(R.string.ai_empty_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            stringResource(R.string.ai_empty_text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        for (id in listOf(R.string.ai_sugg_1, R.string.ai_sugg_2, R.string.ai_sugg_3)) {
                            val s = stringResource(id)
                            Surface(
                                shape = CircleShape,
                                color = cs.surfaceContainerHigh,
                                modifier = Modifier.clip(CircleShape).clickable { input = s },
                            ) {
                                Text(s, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Muted(stringResource(R.string.ai_disclaimer))
                    }
                }
            }
            itemsIndexed(msgs, key = { _, m -> m.n }) { i, m ->
                val newDay = i == 0 || !sameDay(msgs[i - 1].at, m.at)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (newDay) AiDayChip(aiDayLabel(ctx, m.at))
                    AiBubble(m)
                    if (m.memAdded.isNotEmpty() || m.memRemoved.isNotEmpty()) AiMemChips(m, onOpenMemory)
                }
            }
            if (sending) item(key = "typing") { AiTypingBubble() }
            if (error.isNotBlank()) {
                item(key = "error") { ErrorText(error) { error = ""; reload() } }
            }
        }

        // input, or a note while Candy is melted
        if (locked) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.ai_locked, leftText(ctx, lockedUntil)),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            val canSend = !sending && input.isNotBlank()
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (input.isEmpty()) {
                            Text(stringResource(R.string.ai_hint), color = cs.onSurfaceVariant)
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it.take(1500) },
                            textStyle = LocalTextStyle.current.copy(color = cs.onSurface),
                            cursorBrush = SolidColor(cs.primary),
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = if (canSend) cs.primary else cs.surfaceContainerHigh,
                    modifier = Modifier.size(48.dp).clip(CircleShape).clickable(enabled = canSend) { send() },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = null,
                            tint = if (canSend) cs.onPrimary else cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.ai_new_chat_q)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; clearAll() }) { Text(stringResource(R.string.ai_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private class AiMem(val id: Long, val text: String)

/** What Candy remembers about you: see it, add to it, delete from it, or turn it off. */
@Composable
fun AiMemoryScreen(net: Net, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<AiMem>() }
    var on by remember { mutableStateOf(true) }
    var max by remember { mutableStateOf(50) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var confirmAll by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    fun load() {
        scope.launch {
            val r = net.get("/candy/ai/memory")
            loading = false
            if (r.ok) {
                items.clear()
                val a = r.body?.optJSONArray("items")
                if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { items.add(AiMem(it.optLong("id"), it.optString("text"))) }
                on = r.body?.optBoolean("on", true) ?: true
                max = r.body?.optInt("max", 50) ?: 50
                error = ""
            } else {
                error = r.message(ctx)
            }
        }
    }

    fun add() {
        val t = draft.trim()
        if (t.isEmpty()) return
        scope.launch {
            val r = net.post("/candy/ai/memory/add", JSONObject().put("text", t))
            if (r.ok) {
                draft = ""
                error = ""
                load()
            } else {
                error = r.message(ctx)
            }
        }
    }

    fun remove(id: Long) {
        scope.launch {
            val r = net.post("/candy/ai/memory/delete", JSONObject().put("id", id))
            if (r.ok) items.removeAll { it.id == id } else error = r.message(ctx)
        }
    }

    fun removeAll() {
        scope.launch {
            val r = net.post("/candy/ai/memory/delete", JSONObject().put("all", true))
            if (r.ok) items.clear() else error = r.message(ctx)
        }
    }

    fun toggle(v: Boolean) {
        on = v
        scope.launch {
            val r = net.post("/candy/ai/memory/toggle", JSONObject().put("on", v))
            if (!r.ok) {
                on = !v
                error = r.message(ctx)
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxWidth().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), onBack)
            Text(
                stringResource(R.string.ai_mem_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            if (items.isNotEmpty()) {
                TextButton(onClick = { confirmAll = true }) { Text(stringResource(R.string.ai_mem_clear_all)) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CandyBrand.hairline()))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "switch") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ai_mem_on), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = { toggle(it) })
                }
            }
            item(key = "desc") { Muted(stringResource(R.string.ai_mem_desc)) }
            if (error.isNotBlank()) item(key = "error") { ErrorText(error) { error = ""; load() } }
            if (!loading && items.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.ai_mem_empty),
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else if (items.isNotEmpty()) {
                item(key = "count") { Muted(stringResource(R.string.ai_mem_count, items.size, max)) }
            }
            items(items, key = { it.id }) { m ->
                Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp, top = 2.dp, bottom = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                        AppIconButton(Icons.Rounded.Delete, null, { remove(m.id) })
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (draft.isEmpty()) Text(stringResource(R.string.ai_mem_add_hint), color = cs.onSurfaceVariant)
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it.take(120) },
                        textStyle = LocalTextStyle.current.copy(color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary),
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val canAdd = draft.isNotBlank()
            Surface(
                shape = CircleShape,
                color = if (canAdd) cs.primary else cs.surfaceContainerHigh,
                modifier = Modifier.size(48.dp).clip(CircleShape).clickable(enabled = canAdd) { add() },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = if (canAdd) cs.onPrimary else cs.onSurfaceVariant)
                }
            }
        }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            text = { Text(stringResource(R.string.ai_mem_clear_q)) },
            confirmButton = { TextButton(onClick = { confirmAll = false; removeAll() }) { Text(stringResource(R.string.ai_delete)) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
