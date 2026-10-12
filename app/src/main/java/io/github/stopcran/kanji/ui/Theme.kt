package io.github.stopcran.kanji.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF1F1D1A)
private val Paper = Color(0xFFF6F1E7)

/** Ink on warm paper; default Material violet accents on paper neutrals. */
internal val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4), onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8E4), onTertiaryContainer = Color(0xFF31111D),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    background = Paper, onBackground = Ink,
    surface = Color(0xFFFBF8F1), onSurface = Ink,
    surfaceVariant = Color(0xFFE9E1D0), onSurfaceVariant = Color(0xFF4C463B),
    surfaceTint = Color(0xFF6750A4),
    inverseSurface = Color(0xFF33302B), inverseOnSurface = Paper, inversePrimary = Color(0xFFD0BCFF),
    outline = Color(0xFF6F6759), outlineVariant = Color(0xFFD9CFBC), scrim = Color.Black,
    surfaceBright = Color(0xFFFBF8F1), surfaceDim = Color(0xFFE5DFD2),
    surfaceContainerLowest = Color(0xFFFFFDF8), surfaceContainerLow = Color(0xFFF9F5EB), surfaceContainer = Color(0xFFF3EEE2),
    surfaceContainerHigh = Color(0xFFEEE8DA), surfaceContainerHighest = Color(0xFFE9E1D0),
)

/** White ink on black paper (a separate palette, not an inversion). */
internal val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8), onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD8E4),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF121110), onBackground = Color(0xFFECE7DC),
    surface = Color(0xFF1B1A18), onSurface = Color(0xFFECE7DC),
    surfaceVariant = Color(0xFF2B2925), onSurfaceVariant = Color(0xFFCFC8B8),
    surfaceTint = Color(0xFFD0BCFF),
    inverseSurface = Color(0xFFECE7DC), inverseOnSurface = Ink, inversePrimary = Color(0xFF6750A4),
    outline = Color(0xFF9A927F), outlineVariant = Color(0xFF3A3731), scrim = Color.Black,
    surfaceBright = Color(0xFF363430), surfaceDim = Color(0xFF121110),
    surfaceContainerLowest = Color(0xFF0D0C0B), surfaceContainerLow = Color(0xFF171614), surfaceContainer = Color(0xFF1B1A18),
    surfaceContainerHigh = Color(0xFF232220), surfaceContainerHighest = Color(0xFF2B2925),
)

/** Follows the system light/dark setting. Drawing canvases stay paper-white on purpose (ink is dark). */
@Composable
fun KanjiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}
