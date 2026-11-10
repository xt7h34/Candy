package com.candy

import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** How tall the floating bar is right now (0 when it is hidden), so scrolling screens can leave room at the end. */
val LocalBarInset = compositionLocalOf { 0.dp }

/** An empty space as tall as the floating bar, for the end of a scrolling screen. */
@Composable
fun BarSpacer() {
    Spacer(Modifier.height(LocalBarInset.current))
}

private class NavTab(val emoji: String, val label: Int)

private val NavTabs = listOf(
    NavTab("🍬", R.string.nav_feed),
    NavTab("🎬", R.string.nav_loops),
    NavTab("🔎", R.string.nav_search),
    NavTab("💬", R.string.nav_chats),
    NavTab("👤", R.string.nav_me),
)

/** The bottom bar: a floating pill, with a gold pill behind the tab you are on. */
@Composable
fun FloatingNavBar(selected: Int, unread: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptic = rememberAppHaptic()
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            tonalElevation = 3.dp,
            shadowElevation = 10.dp,
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
                    Box(
                        modifier = Modifier
                            .weight(if (on) 1.6f else 1f)
                            .height(52.dp)
                            .clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable(role = Role.Tab) {
                                if (i != selected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(i)
                            }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (i == 3 && unread > 0) {
                            BadgedBox(badge = { Badge { Text(unread.toString()) } }) {
                                Text(tab.emoji, fontSize = 24.sp)
                            }
                        } else {
                            Text(tab.emoji, fontSize = 24.sp)
                        }
                    }
                }
            }
        }
    }
}
