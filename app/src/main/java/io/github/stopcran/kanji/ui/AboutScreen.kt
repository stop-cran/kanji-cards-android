package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Column
import io.github.stopcran.kanji.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.Defaults

private data class Credit(val name: String, val by: String, val license: String, val url: String)

private val credits = listOf(
    Credit("Kanji Cards (this app)", "© Roman Konstantinovskiy and contributors", "Apache License 2.0", "https://github.com/stop-cran/kanji-cards-android"),
    Credit("Card content (default repository)", "© Roman Konstantinovskiy and contributors", "CC BY-SA 4.0", Defaults.CONTENT_REPO_URL),
    Credit("Stroke data: KanjiVG", "© Ulrich Apel and contributors", "CC BY-SA 3.0", "https://kanjivg.tagaini.net"),
    Credit("Readings and word data: KANJIDIC2 and JMdict", "© Electronic Dictionary Research and Development Group", "EDRDG licence", "https://www.edrdg.org/edrdg/licence.html"),
    Credit("Font: Noto Serif JP", "© Google Inc.", "SIL Open Font License 1.1", "https://github.com/notofonts/noto-cjk"),
    Credit("Font: Klee One", "© The Klee Project Authors", "SIL Open Font License 1.1", "https://github.com/fontworks-fonts/Klee"),
    Credit("Font: Yuji Syuku", "© The Yuji Project Authors", "SIL Open Font License 1.1", "https://github.com/Kinutafontfactory/Yuji"),
    Credit("Handwriting recognition: ML Kit Digital Ink", "© Google", "ML Kit terms; recognition runs on the device", "https://developers.google.com/ml-kit/terms"),
    Credit("Scheduling algorithm: FSRS-5 (own implementation)", "Open Spaced Repetition project", "reference implementations are MIT", "https://github.com/open-spaced-repetition"),
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
    val uri = LocalUriHandler.current
    Page("About", onBack) {
        Text(stringResource(R.string.kanji_cards, version), style = MaterialTheme.typography.titleMedium)
        Text(
            "A personal kanji trainer: guess meanings, draw kanji with stroke checks, and read articles that live in a GitHub repository " +
                "you can fork. Your progress and drawings stay on this device; the app only downloads the public content repository.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(stringResource(R.string.sources_and_licences), style = MaterialTheme.typography.titleMedium)
        credits.forEach { c ->
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(c.name, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.author_and_license, c.by, c.license), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { uri.openUri(c.url) }) { Text(c.url.removePrefix("https://")) }
            }
        }
        Text(
            "The stroke data and any content derived from KanjiVG, KANJIDIC2 or JMdict keeps its original licence; " +
                "if you fork the content repository, keep the attributions in its NOTICE file.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { uri.openUri("https://github.com/stop-cran/kanji-cards-android/blob/main/docs/PRIVACY.md") }) { Text(stringResource(R.string.privacy_policy)) }
    }
}
