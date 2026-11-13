package com.candy

import androidx.compose.runtime.getValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import android.content.Context
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    )
}

@Composable
fun ErrorText(message: String) {
    if (message.isNotBlank()) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = Space.lg, bottom = Space.xs),
    )
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * Grey placeholders in the shape of what is loading (a card), pulsing softly. Shown instead of a spinner when there is nothing
 * to show yet, so the screen looks ready and the real content just fills in.
 */
@Composable
fun SkeletonCards(count: Int = 3, tall: Boolean = true) {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    val tone = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) {
            Column(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(tone))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.width(120.dp).height(12.dp).clip(MaterialTheme.shapes.extraSmall).background(tone))
                        Box(Modifier.width(70.dp).height(10.dp).clip(MaterialTheme.shapes.extraSmall).background(tone))
                    }
                }
                if (tall) Box(Modifier.fillMaxWidth().height(180.dp).clip(MaterialTheme.shapes.medium).background(tone))
                Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(MaterialTheme.shapes.extraSmall).background(tone))
            }
        }
    }
}

/** An arrow back (it flips in right-to-left languages) with the screen title, for the screens that open on top of the tabs. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.xs, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), onBack)
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = Space.lg),
        )
    }
}

/** A round 48dp tap target with an icon: the one way to make an icon button in the app. */
@Composable
fun AppIconButton(
    icon: ImageVector,
    desc: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(icon, contentDescription = desc, tint = tint)
    }
}

/** A round chip with an icon in it, for the small actions under a post or in a row. */
@Composable
fun IconChip(icon: ImageVector, tint: Color, bg: Color, size: androidx.compose.ui.unit.Dp = 36.dp, iconSize: androidx.compose.ui.unit.Dp = 20.dp) {
    Box(Modifier.size(size).clip(MaterialTheme.shapes.small).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** A card the way the app draws every card: soft surface, thin line, big corners. */
@Composable
fun CandyCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    colors: CardColors? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = shape,
        colors = colors ?: CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, CandyBrand.hairline()),
        content = content,
    )
}

/** "5h 20m left", or "Ended". */
fun timeLeft(c: Context, expiresAt: Long): String {
    val ms = expiresAt - System.currentTimeMillis()
    if (ms <= 0) return c.getString(R.string.drift_ended)
    val mins = (ms / 60000).toInt()
    val h = mins / 60
    val m = mins % 60
    return if (h > 0) c.getString(R.string.time_left_hm, h, m) else c.getString(R.string.time_left_m, maxOf(m, 1))
}

/**
 * Runs [block] now and then every [intervalMs], but only while the app is on screen.
 * When the app goes to the background it stops, so it does not waste the connection.
 */
@Composable
fun PollingEffect(key: Any?, intervalMs: Long, block: suspend () -> Unit) {
    PollingEffect(key, { intervalMs }, block)
}

/** Same, with an interval that can change between rounds (for example, faster while someone is typing). */
@Composable
fun PollingEffect(key: Any?, intervalMs: () -> Long, block: suspend () -> Unit) {
    val owner = LocalContext.current as LifecycleOwner
    LaunchedEffect(key) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                block()
                delay(intervalMs())
            }
        }
    }
}

/** "now", "5m", "3h" or "2d": how long ago something was posted. */
fun agoShort(c: Context, at: Long): String {
    val mins = ((System.currentTimeMillis() - at) / 60000).toInt().coerceAtLeast(0)
    return when {
        mins < 1 -> c.getString(R.string.ago_now)
        mins < 60 -> c.getString(R.string.ago_m, mins)
        mins < 60 * 24 -> c.getString(R.string.ago_h, mins / 60)
        else -> c.getString(R.string.ago_d, mins / (60 * 24))
    }
}

// A track tag starts with $ (the # is taken by the account hash), then a letter.
private val TAG_REGEX = Regex("\\$\\p{L}[\\p{L}\\p{N}_]*")

/** The text with every $tag in [color], so tags stand out from the rest of the caption. */
fun taggedText(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in TAG_REGEX.findAll(text)) {
        append(text.substring(last, m.range.first))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(m.value) }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/** The blue check next to a verified account (drawn, so it sits in the middle of the circle). */
@Composable
fun VerifiedBadge(size: Dp = 16.dp) {
    Canvas(Modifier.size(size).clip(CircleShape).background(Color(0xFF1D9BF0))) {
        val w = this.size.width
        val h = this.size.height
        val check = Path().apply {
            moveTo(w * 0.27f, h * 0.52f)
            lineTo(w * 0.44f, h * 0.68f)
            lineTo(w * 0.74f, h * 0.34f)
        }
        drawPath(check, Color.White, style = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A name, with the blue check after it when the account is verified. */
@Composable
fun NameWithBadge(name: String, verified: Boolean, style: TextStyle, fontWeight: FontWeight? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, style = style, fontWeight = fontWeight)
        if (verified) VerifiedBadge()
    }
}
