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

/** Ink on warm paper; the only saturated colour is a hanko-seal vermilion, used for primary actions and links. */
internal val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFFB5392A), onPrimary = Color.White,
    primaryContainer = Color(0xFFF0D9D2), onPrimaryContainer = Color(0xFF3E1008),
    secondary = Color(0xFF5C554A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8DFCB), onSecondaryContainer = Color(0xFF2A261F),
    tertiary = Color(0xFF6B5E3F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEADFC2), onTertiaryContainer = Color(0xFF2D2511),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    background = Paper, onBackground = Ink,
    surface = Color(0xFFFBF8F1), onSurface = Ink,
    surfaceVariant = Color(0xFFE9E1D0), onSurfaceVariant = Color(0xFF4C463B),
    surfaceTint = Color(0xFFB5392A),
    inverseSurface = Color(0xFF33302B), inverseOnSurface = Paper, inversePrimary = Color(0xFFE07A62),
    outline = Color(0xFF6F6759), outlineVariant = Color(0xFFD9CFBC), scrim = Color.Black,
    surfaceBright = Color(0xFFFBF8F1), surfaceDim = Color(0xFFE5DFD2),
    surfaceContainerLowest = Color(0xFFFFFDF8), surfaceContainerLow = Color(0xFFF9F5EB), surfaceContainer = Color(0xFFF3EEE2),
    surfaceContainerHigh = Color(0xFFEEE8DA), surfaceContainerHighest = Color(0xFFE9E1D0),
)

/** White ink on black paper (a separate palette, not an inversion). */
internal val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFE07A62), onPrimary = Ink,
    primaryContainer = Color(0xFF6E2418), onPrimaryContainer = Color(0xFFFAD9D0),
    secondary = Color(0xFFC9C0AD), onSecondary = Color(0xFF2A261F),
    secondaryContainer = Color(0xFF3A3731), onSecondaryContainer = Color(0xFFE8DFCB),
    tertiary = Color(0xFFD6C79F), onTertiary = Color(0xFF2D2511),
    tertiaryContainer = Color(0xFF4A4128), onTertiaryContainer = Color(0xFFEADFC2),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF121110), onBackground = Color(0xFFECE7DC),
    surface = Color(0xFF1B1A18), onSurface = Color(0xFFECE7DC),
    surfaceVariant = Color(0xFF2B2925), onSurfaceVariant = Color(0xFFCFC8B8),
    surfaceTint = Color(0xFFE07A62),
    inverseSurface = Color(0xFFECE7DC), inverseOnSurface = Ink, inversePrimary = Color(0xFFB5392A),
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
