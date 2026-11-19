package com.candy

import android.content.Context
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.random.Random

/** The time of the last touch anywhere in the app (MainActivity reports it). Candy gets bored when it stays quiet. */
object CandyIdle {
    var last by mutableStateOf(System.currentTimeMillis())
        private set

    fun touched() {
        val now = System.currentTimeMillis()
        if (now - last > 1000L) last = now
    }
}

/** Where Candy sits on the screen edge, and whether she is tucked away. Kept on the phone. */
private object CandyFloatPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("candy_float", Context.MODE_PRIVATE)
    fun edge(ctx: Context): Int = p(ctx).getInt("edge", 1)
    fun y(ctx: Context): Float = p(ctx).getFloat("y", 0.55f)
    fun hidden(ctx: Context): Boolean = p(ctx).getBoolean("hidden", false)
    fun save(ctx: Context, edge: Int, y: Float, hidden: Boolean) {
        p(ctx).edit().putInt("edge", edge).putFloat("y", y).putBoolean("hidden", hidden).apply()
    }
}

// The window onto the art that the little Candy shows (fractions of the 1254x1254 picture): her hair, face and shoulders.
private const val PEEK_X = 0.36f
private const val PEEK_Y = 0.04f
private const val PEEK_W = 0.46f

/**
 * Elite only: Candy lives on the edge of the screen in every section except chats with people.
 * Tap: a quick chat opens over the screen. Long press: the full chat. Drag her to move her, or push her into the edge to tuck her away.
 */
@Composable
fun CandyFloatingHost(
    net: Net,
    visible: Boolean,
    screenLabel: String,
    bottomInset: Dp,
    onOpenFull: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var tier by remember { mutableStateOf("") }
    var edge by remember { mutableStateOf(CandyFloatPrefs.edge(ctx)) }
    var yFrac by remember { mutableStateOf(CandyFloatPrefs.y(ctx)) }
    var hidden by remember { mutableStateOf(CandyFloatPrefs.hidden(ctx)) }
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    var panel by remember { mutableStateOf(false) }
    var talkTick by remember { mutableStateOf(0) }
    var talkMs by remember { mutableStateOf(0L) }
    val reduceMotion = remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }

    // the plan is read from the bot now and then, so upgrading shows her without restarting the app
    LaunchedEffect(Unit) {
        while (true) {
            val r = net.get("/candy/ai/thread?limit=1")
            if (r.ok) tier = tierOf(r.body)
            delay(10 * 60_000L)
        }
    }
    LaunchedEffect(visible) { if (!visible) panel = false }

    if (tier != "elite" || !visible) return

    BoxWithConstraints(modifier) {
        val w = 92.dp
        val h = 112.dp
        val wPx = with(density) { w.toPx() }
        val hPx = with(density) { h.toPx() }
        val maxWPx = with(density) { maxWidth.toPx() }
        val maxHPx = with(density) { maxHeight.toPx() }
        val bottomPx = with(density) { bottomInset.toPx() }
        val yMax = (maxHPx - hPx - bottomPx).coerceAtLeast(1f)
        val baseX = if (edge == 1) maxWPx - wPx else 0f
        val baseY = (yFrac * yMax).coerceIn(0f, yMax)

        // the figure is positioned in screen coordinates, so right-to-left must not mirror it
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        if (hidden) {
            // only a small tab stays on the edge; tap it to bring her back
            val tabW = 28.dp
            val tabH = 56.dp
            val tabPx = with(density) { tabW.toPx() }
            val tabHPx = with(density) { tabH.toPx() }
            Box(
                Modifier
                    .offset { IntOffset(if (edge == 1) (maxWPx - tabPx).roundToInt() else 0, (baseY + hPx / 2f - tabHPx / 2f).roundToInt()) }
                    .size(tabW, tabH)
                    .clip(if (edge == 1) RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp) else RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f))
                    .clickable {
                        hidden = false
                        CandyFloatPrefs.save(ctx, edge, yFrac, false)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.candy_ai_avatar),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(22.dp).clip(CircleShape),
                )
            }
        } else {
            Box(
                Modifier
                    .offset { IntOffset((baseX + dragX).roundToInt(), (baseY + dragY).coerceIn(0f, yMax).roundToInt()) }
                    .size(w, h)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { panel = true },
                            onLongPress = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenFull()
                            },
                        )
                    }
                    .pointerInput(edge, yFrac, maxWPx, yMax) {
                        fun finish() {
                            val left = baseX + dragX
                            val pushedIn = if (edge == 1) dragX > wPx * 0.4f else dragX < -wPx * 0.4f
                            val newEdge = if (left + wPx / 2f < maxWPx / 2f) 0 else 1
                            val newY = ((baseY + dragY).coerceIn(0f, yMax) / yMax).coerceIn(0f, 1f)
                            if (pushedIn) {
                                // dragged into the edge she is on: she tucks away
                                hidden = true
                                dragX = 0f
                                dragY = 0f
                                CandyFloatPrefs.save(ctx, edge, newY, true)
                                yFrac = newY
                                return
                            }
                            val newBaseX = if (newEdge == 1) maxWPx - wPx else 0f
                            val from = left - newBaseX
                            edge = newEdge
                            yFrac = newY
                            dragY = 0f
                            dragX = from
                            CandyFloatPrefs.save(ctx, newEdge, newY, false)
                            scope.launch { animate(from, 0f, animationSpec = tween(220)) { v, _ -> dragX = v } }
                        }
                        detectDragGestures(
                            onDragEnd = { finish() },
                            onDragCancel = { finish() },
                            onDrag = { change, amount ->
                                change.consume()
                                dragX += amount.x
                                dragY += amount.y
                            },
                        )
                    },
            ) {
                CandyPeek(w, h, !reduceMotion, talkTick, talkMs)
            }
        }
        }

        if (panel) {
            CandyQuickChat(
                net = net,
                screenLabel = screenLabel,
                onClose = { panel = false },
                onOpenFull = {
                    panel = false
                    onOpenFull()
                },
                onReply = { len ->
                    talkMs = (len * 45L).coerceIn(1200L, 5000L)
                    talkTick++
                },
            )
        }
    }
}

/** Candy's hair, face and shoulders in a small window: she blinks and breathes, her mouth moves when she answers, and she gets bored and sleeps. */
@Composable
private fun CandyPeek(width: Dp, height: Dp, animated: Boolean, talkTick: Int, talkMs: Long) {
    var mood by remember { mutableStateOf(Mood.Awake) }
    var blinkEyes by remember { mutableStateOf<Eyes?>(null) }
    var actEyes by remember { mutableStateOf<Eyes?>(null) }
    var talkMouth by remember { mutableStateOf<Mouth?>(null) }
    var actMouth by remember { mutableStateOf<Mouth?>(null) }
    val lastActive = CandyIdle.last
    val cs = MaterialTheme.colorScheme

    val eyes = actEyes ?: blinkEyes ?: when (mood) {
        Mood.Awake -> Eyes.Open
        Mood.Bored -> Eyes.Half
        Mood.Asleep -> Eyes.Closed
    }
    val mouth = talkMouth ?: actMouth ?: if (mood == Mood.Bored) Mouth.Bored else Mouth.Closed

    val transition = rememberInfiniteTransition(label = "candy-peek")
    val breathe = if (animated) {
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.02f,
            animationSpec = infiniteRepeatable(
                tween(if (mood == Mood.Awake) 3200 else 5200, easing = FastOutSlowInEasing),
                RepeatMode.Reverse,
            ),
            label = "candy-peek-breathe",
        ).value
    } else {
        1f
    }

    if (animated) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(Random.nextLong(2500L, 6500L))
                when (mood) {
                    Mood.Awake -> {
                        blinkEyes = Eyes.Half
                        delay(60)
                        blinkEyes = Eyes.Closed
                        delay(90)
                        blinkEyes = Eyes.Half
                        delay(60)
                        blinkEyes = null
                    }
                    Mood.Bored -> {
                        blinkEyes = Eyes.Closed
                        delay(260)
                        blinkEyes = null
                    }
                    Mood.Asleep -> Unit
                }
            }
        }
        LaunchedEffect(talkTick) {
            if (talkTick > 0) {
                val shapes = listOf(Mouth.Slight, Mouth.Oo, Mouth.Ah, Mouth.Slight, Mouth.Closed, Mouth.Ah, Mouth.Oo)
                val end = System.currentTimeMillis() + talkMs
                var i = 0
                while (System.currentTimeMillis() < end) {
                    talkMouth = shapes[i % shapes.size]
                    i++
                    delay(130)
                }
                talkMouth = null
            }
        }
        // awake -> bored -> yawn -> asleep, starting over on every touch anywhere in the app
        LaunchedEffect(lastActive) {
            val wasAsleep = mood == Mood.Asleep
            mood = Mood.Awake
            actEyes = null
            actMouth = null
            if (wasAsleep) {
                actEyes = Eyes.Half
                delay(220)
                actEyes = null
            }
            delay(25_000L)
            mood = Mood.Bored
            delay(15_000L)
            actEyes = Eyes.Closed
            actMouth = Mouth.Slight
            delay(300)
            actMouth = Mouth.Ah
            delay(1200)
            actMouth = Mouth.Slight
            delay(250)
            actEyes = null
            actMouth = null
            delay(20_000L)
            mood = Mood.Asleep
        }
    }

    Box(
        Modifier
            .size(width, height)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // her shoulders fade out at the bottom instead of ending in a hard line
                drawRect(
                    brush = Brush.verticalGradient(0.62f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            }
            .clipToBounds(),
    ) {
        // the art is not mirrored for right-to-left languages, so its pieces line up
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val side = width / PEEK_W
            Box(
                Modifier
                    .layout { measurable, constraints ->
                        val s = side.roundToPx()
                        val p = measurable.measure(Constraints.fixed(s, s))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            p.place(-(PEEK_X * s).roundToInt(), -(PEEK_Y * s).roundToInt())
                        }
                    }
                    .graphicsLayer {
                        scaleX = breathe
                        scaleY = breathe
                        transformOrigin = TransformOrigin(0.5f, 1f)
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
        }
        if (mood == Mood.Asleep) {
            Text(
                "z z",
                color = cs.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp),
            )
        }
    }
}

/** A small chat over whatever screen you are on. It is the same conversation as the full chat. */
@Composable
private fun CandyQuickChat(
    net: Net,
    screenLabel: String,
    onClose: () -> Unit,
    onOpenFull: () -> Unit,
    onReply: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<Pair<Boolean, String>>() }
    var loading by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val cs = MaterialTheme.colorScheme

    BackHandler { onClose() }

    fun load() {
        scope.launch {
            val r = net.get("/candy/ai/thread?limit=8")
            loading = false
            if (r.ok) {
                items.clear()
                val a = r.body?.optJSONArray("messages")
                if (a != null) {
                    for (i in 0 until a.length()) {
                        val o = a.optJSONObject(i) ?: continue
                        items.add((o.optString("role") == "user") to o.optString("text"))
                    }
                }
                error = ""
            } else {
                error = r.message(ctx)
            }
        }
    }

    fun send() {
        val t = input.trim()
        if (t.isEmpty() || sending) return
        sending = true
        error = ""
        items.add(true to t)
        input = ""
        scope.launch {
            val r = net.post("/candy/ai/send", JSONObject().put("text", t), readTimeoutMs = 60_000)
            sending = false
            if (r.ok) {
                val reply = r.body?.optJSONObject("reply")?.optString("text").orEmpty()
                if (reply.isNotEmpty()) {
                    items.add(false to reply)
                    onReply(reply.length)
                }
            } else {
                if (items.isNotEmpty()) items.removeAt(items.lastIndex)
                input = t
                error = r.message(ctx)
            }
        }
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(items.size, sending) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    val nothing = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = nothing, indication = null) { onClose() },
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = cs.surface,
            tonalElevation = 3.dp,
        ) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.candy_ai_avatar),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(36.dp).clip(CircleShape),
                    )
                    Text(
                        stringResource(R.string.ai_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenFull) { Text(stringResource(R.string.ai_open_full)) }
                    AppIconButton(Icons.Rounded.Close, null, onClose)
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
                ) {
                    itemsIndexed(items) { _, m ->
                        val mine = m.first
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                            Box(
                                Modifier
                                    .widthIn(max = 280.dp)
                                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = if (mine) 5.dp else 18.dp, bottomStart = if (mine) 18.dp else 5.dp))
                                    .background(if (mine) cs.primary else cs.surfaceContainerHigh)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    m.second,
                                    color = if (mine) cs.onPrimary else cs.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    if (sending) {
                        item(key = "thinking") {
                            Text(stringResource(R.string.ai_thinking), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                    if (error.isNotBlank()) {
                        item(key = "error") { ErrorText(error) { error = ""; load() } }
                    }
                }

                if (screenLabel.isNotBlank() && input.isBlank() && !sending) {
                    Surface(
                        shape = CircleShape,
                        color = cs.secondaryContainer.copy(alpha = 0.65f),
                        modifier = Modifier.padding(start = 14.dp, top = 4.dp).clip(CircleShape)
                            .clickable { input = ctx.getString(R.string.ai_screen_q, screenLabel) },
                    ) {
                        Text(
                            stringResource(R.string.ai_screen_chip),
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            if (input.isEmpty()) Text(stringResource(R.string.ai_hint), color = cs.onSurfaceVariant)
                            BasicTextField(
                                value = input,
                                onValueChange = { input = it.take(1500) },
                                textStyle = LocalTextStyle.current.copy(color = cs.onSurface),
                                cursorBrush = SolidColor(cs.primary),
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    val canSend = !sending && input.isNotBlank()
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
}
