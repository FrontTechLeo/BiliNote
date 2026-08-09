package com.bilinote.android.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BiliNoteBlue = Color(0xFF00AEEC)
private val BiliNoteBlueDark = Color(0xFF7DD8FF)
private val BiliNoteInk = Color(0xFF17202A)
private val BiliNoteInkDark = Color(0xFFE5F2F8)
private val BiliNoteSurface = Color(0xFFF7FAFC)
private val BiliNoteSurfaceDark = Color(0xFF111A20)

private val LightColorScheme = lightColorScheme(
    primary = BiliNoteBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5F3FF),
    onPrimaryContainer = Color(0xFF003547),
    secondary = Color(0xFF53656D),
    onSecondary = Color.White,
    background = BiliNoteSurface,
    onBackground = BiliNoteInk,
    surface = Color.White,
    onSurface = BiliNoteInk,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = BiliNoteBlueDark,
    onPrimary = Color(0xFF003547),
    primaryContainer = Color(0xFF00516A),
    onPrimaryContainer = Color(0xFFD5F3FF),
    secondary = Color(0xFFB6CBD3),
    onSecondary = Color(0xFF21343B),
    background = BiliNoteSurfaceDark,
    onBackground = BiliNoteInkDark,
    surface = Color(0xFF18242B),
    onSurface = BiliNoteInkDark,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val BiliNoteTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

@Immutable
data class BiliNoteColors(
    val success: Color,
    val progress: Color,
    val error: Color,
)

@Immutable
data class BiliNoteSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
)

object BiliNoteShapes {
    val card = RoundedCornerShape(16.dp)
    val smallCard = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(50)
}

object BiliNoteLayoutTokens {
    val readerMaxWidth: Dp = 720.dp
    val navigationTouchTarget: Dp = 48.dp
}

private val LightBiliNoteColors = BiliNoteColors(
    success = Color(0xFF2E7D32),
    progress = Color(0xFFEF8C00),
    error = Color(0xFFBA1A1A),
)

private val DarkBiliNoteColors = BiliNoteColors(
    success = Color(0xFF8DD58F),
    progress = Color(0xFFFFB957),
    error = Color(0xFFFFB4AB),
)

private val LocalBiliNoteColors = staticCompositionLocalOf { LightBiliNoteColors }
private val LocalBiliNoteSpacing = staticCompositionLocalOf { BiliNoteSpacing() }

val BiliNoteThemeColors: BiliNoteColors
    @Composable
    @ReadOnlyComposable
    get() = LocalBiliNoteColors.current

val BiliNoteThemeSpacing: BiliNoteSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalBiliNoteSpacing.current

@Composable
fun BiliNoteTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColorScheme else LightColorScheme
    val extraColors = if (darkTheme) DarkBiliNoteColors else LightBiliNoteColors

    androidx.compose.runtime.CompositionLocalProvider(
        LocalBiliNoteColors provides extraColors,
        LocalBiliNoteSpacing provides BiliNoteSpacing(),
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = BiliNoteTypography,
            content = content,
        )
    }
}
