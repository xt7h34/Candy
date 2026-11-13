package com.candy

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How tall the floating bar is right now (0 when it is hidden), so scrolling screens can leave room at the end. */
val LocalBarInset = compositionLocalOf { 0.dp }

/** An empty space as tall as the floating bar, for the end of a scrolling screen. */
@Composable
fun BarSpacer() {
    Spacer(Modifier.height(LocalBarInset.current))
}

private class NavTab(val on: ImageVector, val off: ImageVector, val label: Int)

private val NavTabs = listOf(
    NavTab(Icons.Rounded.Home, Icons.Outlined.Home, R.string.nav_feed),
    NavTab(Icons.Rounded.PlayCircle, Icons.Outlined.PlayCircle, R.string.nav_loops),
    NavTab(Icons.Rounded.Search, Icons.Rounded.Search, R.string.nav_search),
    NavTab(Icons.Rounded.ChatBubble, Icons.Outlined.ChatBubbleOutline, R.string.nav_chats),
    NavTab(Icons.Rounded.Person, Icons.Outlined.Person, R.string.nav_me),
)

private val NavBarHeight: Dp = 56.dp

/**
 * The bottom bar: a floating pill. The tab you are on grows into a rose-to-peach pill with its name;
 * the others stay as quiet icons.
 */
@Composable
fun FloatingNavBar(selected: Int, unread: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptic = rememberAppHaptic()
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Space.lg, vertical = Space.sm),
    ) {
        Surface(
            shape = CircleShape,
            color = scheme.surfaceContainerHigh.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, CandyBrand.hairline()),
            tonalElevation = 3.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavTabs.forEachIndexed { i, tab ->
                    val on = i == selected
                    val label = stringResource(tab.label)
                    val weight by animateFloatAsState(if (on) 2.1f else 1f, spring(stiffness = 600f), label = "nav-weight")
                    val tint = if (on) scheme.onPrimary else scheme.onSurfaceVariant
                    Box(
                        modifier = Modifier
                            .weight(weight)
                            .height(NavBarHeight - 12.dp)
                            .clip(CircleShape)
                            .then(if (on) Modifier.background(CandyBrand.gradient()) else Modifier)
                            .clickable(role = Role.Tab) {
                                if (i != selected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(i)
                            }
                            .semantics { contentDescription = label; this.selected = on },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val icon = if (on) tab.on else tab.off
                            if (i == 3 && unread > 0) {
                                BadgedBox(badge = { Badge { Text(unread.toString()) } }) {
                                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
                                }
                            } else {
                                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
                            }
                            if (on) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    label,
                                    color = tint,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
