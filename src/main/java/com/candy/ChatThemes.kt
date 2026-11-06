package com.candy

import androidx.compose.ui.graphics.Color

/**
 * The look of one chat: the colour of your bubbles, and a tint that fades from the top.
 * Both people see the same one. Null means "use the app's own colours".
 */
class ChatTheme(val id: String, val label: Int, val mine: Color?, val onMine: Color?, val tint: Color?)

val ChatThemes = listOf(
    ChatTheme("default", R.string.theme_default, null, null, null),
    ChatTheme("rose", R.string.theme_rose, Color(0xFFD94668), Color.White, Color(0xFFD94668)),
    ChatTheme("ocean", R.string.theme_ocean, Color(0xFF1E88E5), Color.White, Color(0xFF1E88E5)),
    ChatTheme("forest", R.string.theme_forest, Color(0xFF2E7D32), Color.White, Color(0xFF2E7D32)),
    ChatTheme("sunset", R.string.theme_sunset, Color(0xFFEF6C00), Color.White, Color(0xFFEF6C00)),
    ChatTheme("violet", R.string.theme_violet, Color(0xFF7C4DFF), Color.White, Color(0xFF7C4DFF)),
    ChatTheme("gold", R.string.theme_gold, Color(0xFFB8860B), Color.White, Color(0xFFE9C98F)),
    ChatTheme("mono", R.string.theme_mono, Color(0xFF455A64), Color.White, Color(0xFF90A4AE)),
)

fun chatThemeOf(id: String): ChatTheme = ChatThemes.firstOrNull { it.id == id } ?: ChatThemes[0]

val DisappearOptions = listOf(0L, 3_600_000L, 86_400_000L, 604_800_000L)

fun disappearLabel(ms: Long): Int = when {
    ms <= 0L -> R.string.cs_off
    ms <= 3_600_000L -> R.string.dur_1h
    ms <= 86_400_000L -> R.string.dur_24h
    else -> R.string.dur_7d
}
