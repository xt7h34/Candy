package com.candy

import android.content.Context
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
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** A back button with the screen title, for the screens that open on top of the tabs. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
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

/** The blue check next to a verified account. */
@Composable
fun VerifiedBadge(size: Dp = 16.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0xFF1D9BF0)),
        contentAlignment = Alignment.Center,
    ) {
        Text("✓", color = Color.White, fontSize = (size.value * 0.68f).sp, fontWeight = FontWeight.Bold)
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
