package com.candy

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFFE95F6F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9DE),
    onPrimaryContainer = Color(0xFF3F0014),
    secondary = Color(0xFF8A6A3C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFCF2E0),
    onSecondaryContainer = Color(0xFF2B1D00),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF241A1C),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF241A1C),
    surfaceVariant = Color(0xFFF8E1E4),
    onSurfaceVariant = Color(0xFF524345),
    outline = Color(0xFF857375),
    outlineVariant = Color(0xFFD8C2C5),
    error = Color(0xFFBA1A1A),
    surfaceTint = Color(0xFFE95F6F),
    inverseSurface = Color(0xFF3A2E30),
    inverseOnSurface = Color(0xFFFFEDEE),
    inversePrimary = Color(0xFFFFB2BC),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F6),
    surfaceDim = Color(0xFFEBD7D9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0F0),
    surfaceContainer = Color(0xFFFCEAEB),
    surfaceContainerHigh = Color(0xFFF7E4E6),
    surfaceContainerHighest = Color(0xFFF1DEE0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB2BC),
    onPrimary = Color(0xFF670023),
    primaryContainer = Color(0xFF8F1639),
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Color(0xFFE1C28F),
    onSecondary = Color(0xFF402D05),
    secondaryContainer = Color(0xFF5B4319),
    onSecondaryContainer = Color(0xFFFCF2E0),
    background = Color(0xFF191113),
    onBackground = Color(0xFFEEDFE0),
    surface = Color(0xFF191113),
    onSurface = Color(0xFFEEDFE0),
    surfaceVariant = Color(0xFF524345),
    onSurfaceVariant = Color(0xFFD6C2C4),
    outline = Color(0xFF9F8C8F),
    outlineVariant = Color(0xFF524345),
    error = Color(0xFFFFB4AB),
    surfaceTint = Color(0xFFFFB2BC),
    inverseSurface = Color(0xFFEEDFE0),
    inverseOnSurface = Color(0xFF382E30),
    inversePrimary = Color(0xFFB42E4F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF403739),
    surfaceDim = Color(0xFF191113),
    surfaceContainerLowest = Color(0xFF140C0E),
    surfaceContainerLow = Color(0xFF221A1C),
    surfaceContainer = Color(0xFF271E20),
    surfaceContainerHigh = Color(0xFF32282B),
    surfaceContainerHighest = Color(0xFF3D3336),
)

@Composable
fun CandyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
