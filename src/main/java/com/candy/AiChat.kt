package com.candy

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One message in the conversation with Candy. A negative [n] means it was just sent and the bot has not answered yet. */
private class AiMsg(val n: Long, val role: String, val text: String, val at: Long) {
    val mine: Boolean get() = role == "user"
}

private fun JSONObject.toAiMsg() = AiMsg(optLong("n"), optString("role"), optString("text"), optLong("at"))

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

@Composable
private fun AiAvatar(size: androidx.compose.ui.unit.Dp) {
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
            Text("• • •", modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
fun AiChatScreen(net: Net, onBack: () -> Unit) {
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
    val listState = rememberLazyListState()

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
                if (a != null) msgs.add(a)
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

    Column(Modifier.fillMaxWidth().imePadding()) {
        // header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
                        Modifier.fillMaxWidth().padding(top = 36.dp, start = 16.dp, end = 16.dp),
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
