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

private data class Credit(val nameId: Int, val by: String, val licenseId: Int, val url: String)

private val credits = listOf(
    Credit(R.string.credit_app, "© Roman Konstantinovskiy and contributors", R.string.license_apache, "https://github.com/stop-cran/kanji-cards-android"),
    Credit(R.string.credit_default_content, "© Roman Konstantinovskiy and contributors", R.string.license_cc_by_sa_4, Defaults.CONTENT_REPO_URL),
    Credit(R.string.credit_stroke_data, "© Ulrich Apel and contributors", R.string.license_cc_by_sa_3, "https://kanjivg.tagaini.net"),
    Credit(
        R.string.credit_dictionary_data, "© Electronic Dictionary Research and Development Group",
        R.string.license_edrdg, "https://www.edrdg.org/edrdg/licence.html",
    ),
    Credit(R.string.credit_font_noto, "© Google Inc.", R.string.license_sil_ofl, "https://github.com/notofonts/noto-cjk"),
    Credit(R.string.credit_font_klee, "© The Klee Project Authors", R.string.license_sil_ofl, "https://github.com/fontworks-fonts/Klee"),
    Credit(R.string.credit_font_yuji, "© The Yuji Project Authors", R.string.license_sil_ofl, "https://github.com/Kinutafontfactory/Yuji"),
    Credit(R.string.credit_font_hanamin, "© GlyphWiki", R.string.license_glyphwiki, "https://github.com/cjkvi/HanaMinAFDKO"),
    Credit(
        R.string.credit_libraries, "© The Android Open Source Project, JetBrains and contributors",
        R.string.license_apache, "https://www.apache.org/licenses/LICENSE-2.0",
    ),
    Credit(R.string.credit_handwriting, "© Google", R.string.license_mlkit, "https://developers.google.com/ml-kit/terms"),
    Credit(R.string.credit_fsrs, "Open Spaced Repetition project", R.string.license_fsrs, "https://github.com/open-spaced-repetition"),
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
    val uri = LocalUriHandler.current
    Page(stringResource(R.string.about_title), onBack) {
        Text(stringResource(R.string.kanji_cards, version), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.about_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(stringResource(R.string.sources_and_licences), style = MaterialTheme.typography.titleMedium)
        credits.forEach { c ->
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(stringResource(c.nameId), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.author_and_license, c.by, stringResource(c.licenseId)), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { uri.openUri(c.url) }) { Text(c.url.removePrefix("https://")) }
            }
        }
        Text(
            stringResource(R.string.about_attribution_notice),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { uri.openUri("https://github.com/stop-cran/kanji-cards-android/blob/main/docs/PRIVACY.md") }) { Text(stringResource(R.string.privacy_policy)) }
    }
}
