package com.candy

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp

private val LightColors = lightColorScheme(
    primary = Color(0xFFD94668),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E2),
    onPrimaryContainer = Color(0xFF3F0017),
    secondary = Color(0xFF8A6A3C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF6E3BC),
    onSecondaryContainer = Color(0xFF2B1D00),
    tertiary = Color(0xFFE8895A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBCB),
    onTertiaryContainer = Color(0xFF3A1200),
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
    tertiary = Color(0xFFFFB98F),
    onTertiary = Color(0xFF4A1F00),
    tertiaryContainer = Color(0xFF6B3516),
    onTertiaryContainer = Color(0xFFFFDBCB),
    background = Color(0xFF130A0E),
    onBackground = Color(0xFFF1DFE4),
    surface = Color(0xFF130A0E),
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
    surfaceDim = Color(0xFF130A0E),
    surfaceContainerLowest = Color(0xFF110709),
    surfaceContainerLow = Color(0xFF1D1117),
    surfaceContainer = Color(0xFF26171D),
    surfaceContainerHigh = Color(0xFF302027),
    surfaceContainerHighest = Color(0xFF3B2A32),
)

/** The only corner sizes in the app: 8, 12, 16, 24 and 32. Use MaterialTheme.shapes.* instead of a number. */
private val CandyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** The spacing scale. Use these instead of a loose number. */
object Space {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** The brand colours of Candy: rose melting into peach. Used for the one thing that is active or primary on a screen. */
object CandyBrand {
    @Composable
    fun gradient(): Brush = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))

    /** The thin line round cards and sheets, so surfaces separate without heavy shadows. */
    @Composable
    fun hairline(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
}

/** Inter for Latin letters. It has no Arabic, so Arabic text uses the phone's own Arabic font, as before. */
private val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

// No letterSpacing anywhere: it breaks the joined letters of Arabic.
private val CandyTypography = Typography(
    displaySmall = TextStyle(fontFamily = InterFamily, fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontFamily = InterFamily, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = InterFamily, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontFamily = InterFamily, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = InterFamily, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = InterFamily, fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun CandyTheme(content: @Composable () -> Unit) {
    // the look you picked in Accessibility; "system" follows the phone
    val dark = when (AppPrefs.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // the clock and icons on the bars have to stay readable on the colour you picked
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val c = WindowCompat.getInsetsController(window, view)
                c.isAppearanceLightStatusBars = !dark
                c.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = CandyShapes,
        typography = CandyTypography,
        content = content,
    )
}
