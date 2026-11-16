package dev.pschmitt.tagesschau

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The web player's palette: studio navy with one orange signal colour. */
object Palette {
    val Studio = Color(0xFF0B2A5B)
    val Deep = Color(0xFF071D40)
    val Panel = Color(0xFF10356F)
    val Ink = Color(0xFFF2F6FC)
    val Mist = Color(0xFF9FB4D6)
    val Signal = Color(0xFFFF8A00)
}

@Composable
fun TagesschauTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Palette.Signal,
                onPrimary = Color(0xFF1A1100),
                secondary = Palette.Mist,
                background = Palette.Studio,
                onBackground = Palette.Ink,
                surface = Palette.Studio,
                onSurface = Palette.Ink,
                surfaceVariant = Palette.Panel,
                onSurfaceVariant = Palette.Mist,
                surfaceContainer = Palette.Deep,
                surfaceContainerHigh = Palette.Deep,
                surfaceContainerHighest = Palette.Panel,
            ),
        content = content,
    )
}
