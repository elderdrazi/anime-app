package com.kumo.anime.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF0D0E12)
val Surface1 = Color(0xFF16171D)
val Surface2 = Color(0xFF1F2028)
val Hairline = Color(0xFF24252D)
val Outline = Color(0xFF2C2D36)
val Accent = Color(0xFFB4A5FF)
val OnAccent = Color(0xFF1A1433)
val TextHi = Color(0xFFEDEDF2)
val TextBody = Color(0xFFB5B6C2)
val TextDim = Color(0xFF8E90A0)
val Mint = Color(0xFF7EE0B5)

/** Stable per-person colors for avatars and friend progress bars. */
val PersonColors = listOf(
    Color(0xFF5DCAA5) to Color(0xFF0B3A2E),
    Color(0xFFF0997B) to Color(0xFF4A1B0C),
    Color(0xFF85B7EB) to Color(0xFF0C2C4F),
    Color(0xFFED93B1) to Color(0xFF4B1528),
    Color(0xFFEFC36A) to Color(0xFF412402),
)

fun personColor(key: String) = PersonColors[(key.hashCode() and 0x7fffffff) % PersonColors.size]

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = Color(0xFF2A2640),
    onPrimaryContainer = Accent,
    secondaryContainer = Color(0xFF2A2640),
    onSecondaryContainer = Accent,
    background = Bg,
    onBackground = TextHi,
    surface = Bg,
    onSurface = TextHi,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextDim,
    surfaceContainerLowest = Bg,
    surfaceContainerLow = Surface1,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface2,
    outline = Outline,
    outlineVariant = Hairline,
    error = Color(0xFFF09595),
)

private val base = Typography()
private val typography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Normal),
)

@Composable
fun KumoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
