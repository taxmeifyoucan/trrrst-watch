package dev.trrrst.watch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Teal = Color(0xFF9EE8D8)
val BgDark = Color(0xFF0B0E0D)
val CardDark = Color(0xFF161B19)
val OnBg = Color(0xFFE3E8E6)

/** Muted accents that remain readable on the dark UI. Index 0 is the original. */
val AccentColors = listOf(
    Teal,
    Color(0xFF9ECBFF), // ice blue
    Color(0xFFD0BCFF), // lavender
    Color(0xFFFFCC99), // peach
    Color(0xFFFFB3C2)  // rose
)

private val scheme = darkColorScheme(
    primary = Teal,
    background = BgDark,
    surface = BgDark,
    surfaceVariant = CardDark,
    onPrimary = Color(0xFF00332A),
    onBackground = OnBg,
    onSurface = OnBg,
    onSurfaceVariant = Color(0xFF9BA5A1)
)

@Composable
fun TrrrstTheme(accentIndex: Int = 0, content: @Composable () -> Unit) {
    val accent = AccentColors.getOrElse(accentIndex) { AccentColors[0] }
    MaterialTheme(colorScheme = scheme.copy(primary = accent), content = content)
}
