package com.candy

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFFD94668),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E2),
    onPrimaryContainer = Color(0xFF3F0017),
    secondary = Color(0xFF8A6A3C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF6E3BC),
    onSecondaryContainer = Color(0xFF2B1D00),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF26191D),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF26191D),
    surfaceVariant = Color(0xFFF8E1E6),
    onSurfaceVariant = Color(0xFF52434A),
    outline = Color(0xFF857379),
    outlineVariant = Color(0xFFD8C2C8),
    error = Color(0xFFBA1A1A),
    surfaceTint = Color(0xFFD94668),
    inverseSurface = Color(0xFF3A2E33),
    inverseOnSurface = Color(0xFFFFEDF0),
    inversePrimary = Color(0xFFFFB2C4),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F7),
    surfaceDim = Color(0xFFEBD7DB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0F2),
    surfaceContainer = Color(0xFFFCEAEE),
    surfaceContainerHigh = Color(0xFFF7E4E9),
    surfaceContainerHighest = Color(0xFFF1DEE3),
)

// Deep wine and rose, with the gold kept for the active tab.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF9DB8),
    onPrimary = Color(0xFF4B0A25),
    primaryContainer = Color(0xFF6B1D3A),
    onPrimaryContainer = Color(0xFFFFD9E3),
    secondary = Color(0xFFE9C98F),
    onSecondary = Color(0xFF3D2A05),
    secondaryContainer = Color(0xFF6B4F1E),
    onSecondaryContainer = Color(0xFFFFEFD2),
    background = Color(0xFF170D11),
    onBackground = Color(0xFFF1DFE4),
    surface = Color(0xFF170D11),
    onSurface = Color(0xFFF1DFE4),
    surfaceVariant = Color(0xFF4A3840),
    onSurfaceVariant = Color(0xFFD9C2CA),
    outline = Color(0xFFA28B94),
    outlineVariant = Color(0xFF4A3840),
    error = Color(0xFFFFB4AB),
    surfaceTint = Color(0xFFFF9DB8),
    inverseSurface = Color(0xFFF1DFE4),
    inverseOnSurface = Color(0xFF392C31),
    inversePrimary = Color(0xFFB0305A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF42333A),
    surfaceDim = Color(0xFF170D11),
    surfaceContainerLowest = Color(0xFF110709),
    surfaceContainerLow = Color(0xFF211318),
    surfaceContainer = Color(0xFF2A1A20),
    surfaceContainerHigh = Color(0xFF35242B),
    surfaceContainerHighest = Color(0xFF402E36),
)

private val CandyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun CandyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = CandyShapes,
        content = content,
    )
}
