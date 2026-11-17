package dev.pschmitt.tagesschau

import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** The web player's palette: studio navy with one orange signal colour. */
object Palette {
    val Studio = Color(0xFF0B2A5B)
    val Deep = Color(0xFF071D40)
    val Panel = Color(0xFF10356F)
    val Ink = Color(0xFFF2F6FC)
    val Mist = Color(0xFF9FB4D6)
    val Signal = Color(0xFFFF8A00)
}

/** The fonts, as on the web: Fira Sans for text; the times in a font of their own. */
object Fonts {
    val FiraSans =
        FontFamily(
            Font(R.font.fira_sans_regular, FontWeight.Normal),
            Font(R.font.fira_sans_medium, FontWeight.Medium),
            Font(R.font.fira_sans_semi_bold, FontWeight.SemiBold),
            Font(R.font.fira_sans_bold, FontWeight.Bold),
            Font(R.font.fira_sans_extra_bold, FontWeight.ExtraBold),
            Font(R.font.fira_sans_black, FontWeight.Black),
        )
    val BarlowCondensed =
        FontFamily(
            Font(R.font.barlow_condensed_semi_bold, FontWeight.SemiBold),
            Font(R.font.barlow_condensed_bold, FontWeight.Bold),
            Font(R.font.barlow_condensed_extra_bold, FontWeight.ExtraBold),
        )
    val PlexMono =
        FontFamily(
            Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
            Font(R.font.ibm_plex_mono_semi_bold, FontWeight.SemiBold),
            Font(R.font.ibm_plex_mono_bold, FontWeight.Bold),
        )

    fun of(f: TimeFont): FontFamily =
        when (f) {
            TimeFont.BARLOW -> BarlowCondensed
            TimeFont.PLEX -> PlexMono
            TimeFont.TEXT -> FiraSans
        }
}

/** The font for times (big clock, edition list, player overlay), from the settings. */
val LocalTimeFont = staticCompositionLocalOf { Fonts.BarlowCondensed }

private fun TextStyle.fira() = copy(fontFamily = Fonts.FiraSans)

private val FiraTypography =
    Typography().run {
        Typography(
            displayLarge = displayLarge.fira(),
            displayMedium = displayMedium.fira(),
            displaySmall = displaySmall.fira(),
            headlineLarge = headlineLarge.fira(),
            headlineMedium = headlineMedium.fira(),
            headlineSmall = headlineSmall.fira(),
            titleLarge = titleLarge.fira(),
            titleMedium = titleMedium.fira(),
            titleSmall = titleSmall.fira(),
            bodyLarge = bodyLarge.fira(),
            bodyMedium = bodyMedium.fira(),
            bodySmall = bodySmall.fira(),
            labelLarge = labelLarge.fira(),
            labelMedium = labelMedium.fira(),
            labelSmall = labelSmall.fira(),
        )
    }

@Composable
fun TagesschauTheme(content: @Composable () -> Unit) {
    // The time font follows the setting, also when it changes while a screen is open.
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var timeFont by remember { mutableStateOf(settings.timeFont) }
    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Settings.KEY_TIME_FONT) timeFont = settings.timeFont
        }
        settings.prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { settings.prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
    CompositionLocalProvider(LocalTimeFont provides Fonts.of(timeFont)) {
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
            typography = FiraTypography,
            content = content,
        )
    }
}
