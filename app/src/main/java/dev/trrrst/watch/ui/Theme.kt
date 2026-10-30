package dev.trrrst.watch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Teal = Color(0xFF9EE8D8)
val BgDark = Color(0xFF0B0E0D)
val CardDark = Color(0xFF161B19)
val OnBg = Color(0xFFE3E8E6)

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
fun TrrrstTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
