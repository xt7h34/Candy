package com.candy

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

private fun hexColor(hex: String): Color? = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    null
}

/**
 * Choose the badge that shows next to your name. Members of a Kanade plan pick from the badges of their plan; officially
 * verified accounts can keep the check; Elite members and verified accounts can also change its colour. It is the same
 * choice as `.شارة` in the bot: both read and write the same thing.
 */
@Composable
fun BadgeScreen(net: Net, onBack: () -> Unit, onSubscriptions: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    suspend fun load() {
        val r = net.get("/candy/badge")
        if (r.ok) {
            info = r.body
            error = ""
        } else {
            error = r.message(ctx)
        }
    }

    fun change(patch: JSONObject) {
        scope.launch {
            busy = true
            val r = net.post("/candy/badge", patch)
            if (r.ok) load() else error = r.message(ctx)
            busy = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.badge_title), onBack)
        val i = info
        if (i == null) {
            if (error.isNotEmpty()) ErrorText(error) else Loading()
            return@Column
        }

        val options = buildList { val a = i.optJSONArray("options"); if (a != null) for (k in 0 until a.length()) add(a.optString(k)) }
        val colors = buildList { val a = i.optJSONArray("colors"); if (a != null) for (k in 0 until a.length()) add(a.optString(k)) }
        val current = i.optString("current").ifEmpty { null }
        val currentHex = if (i.isNull("color")) null else i.optString("color").ifEmpty { null }
        val currentColor = currentHex?.let { hexColor(it) }
        val defaultHex = i.optString("defaultColor")
        val canColor = i.optBoolean("canColor")
        val tier = if (i.isNull("tier")) null else i.optString("tier").ifEmpty { null }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            ErrorText(error)

            if (options.isEmpty()) {
                // no plan and not verified: nothing to choose yet
                Image(
                    painterResource(R.drawable.no_plan),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(200.dp).clip(MaterialTheme.shapes.large),
                )
                Text(stringResource(R.string.badge_none_title), style = MaterialTheme.typography.titleLarge)
                Muted(stringResource(R.string.badge_none_desc))
                Button(onClick = onSubscriptions) { Text(stringResource(R.string.badge_get_plan)) }
                return@Column
            }

            // what it looks like now
            CandyCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(Space.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    if (current != null) BadgeIcon(current, 72.dp, currentColor)
                    Muted(stringResource(R.string.badge_current))
                }
            }

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionTitle(stringResource(R.string.badge_choose))
                for (row in options.chunked(4)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (k in row) {
                            BadgeTile(k, k == current, if (k == "check" || canColor) currentColor else null, !busy) {
                                change(JSONObject().put("icon", k))
                            }
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionTitle(stringResource(R.string.badge_color))
                if (canColor) {
                    for (row in colors.chunked(6)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (hex in row) {
                                val c = hexColor(hex)
                                if (c != null) {
                                    val isDefault = hex.equals(defaultHex, ignoreCase = true)
                                    val selected = if (currentHex == null) isDefault else hex.equals(currentHex, ignoreCase = true)
                                    ColorDot(c, selected, !busy) {
                                        change(JSONObject().put("color", if (isDefault) JSONObject.NULL else hex))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Muted(stringResource(R.string.badge_color_locked))
                    }
                }
            }

            if (tier != "elite") {
                TextButton(onClick = onSubscriptions) { Text(stringResource(R.string.badge_more)) }
            }
            Spacer(Modifier.height(LocalBarInset.current))
        }
    }
}

@Composable
private fun BadgeTile(icon: String, selected: Boolean, color: Color?, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(68.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) scheme.primaryContainer.copy(alpha = 0.55f) else scheme.surfaceContainerHigh)
            .border(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else CandyBrand.hairline(), MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BadgeIcon(icon, 40.dp, color)
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else CandyBrand.hairline(), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}
