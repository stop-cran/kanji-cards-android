package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ContentSync
import io.github.stopcran.kanji.data.SyncActivity
import io.github.stopcran.kanji.data.SyncResult

internal data class HomeContentState(
    val ready: Boolean?,
    val activity: SyncActivity?,
    val languageMismatch: Boolean = false,
    val rolloutBlocked: Boolean = false,
) {
    val usable get() = ready == true && !languageMismatch && !rolloutBlocked
    val failure get() = (activity as? SyncActivity.Finished)?.result as? SyncResult.Failed
    val failed get() = failure != null
    val interrupted get() = activity == SyncActivity.Interrupted
    val idle get() = ready == false && activity == null
    val loading get() = !rolloutBlocked && (ready == null || (!usable && !failed && !interrupted && !idle))
    val retryable get() = !languageMismatch && !rolloutBlocked && (failed || interrupted || idle)

    @Composable
    fun summary(loaded: String): String = when {
        usable -> loaded
        rolloutBlocked -> stringResource(R.string.content_russian_default_gated_short)
        languageMismatch -> stringResource(R.string.content_language_mismatch_short)
        else -> stringResource(if (loading) R.string.content_loading_summary else R.string.content_unavailable_summary)
    }
}

@Composable
internal fun rememberHomeContent(db: AppDatabase, sync: ContentSync, source: RepoSource): HomeContentState = key(db, sync, source) {
    val ready by remember { db.content().observeContentReady(source.id) }.collectAsState(null)
    val activity by sync.activity.collectAsState()
    HomeContentState(ready, activity[source]?.state)
}

@Composable
internal fun HomeContentNotice(state: HomeContentState, onRetry: () -> Unit) {
    val running = state.activity == SyncActivity.Running
    val message = when {
        state.rolloutBlocked -> R.string.content_russian_default_gated
        state.languageMismatch -> R.string.content_language_mismatch
        state.ready == null -> R.string.content_reading_cache
        state.usable && running -> R.string.content_updating
        state.usable && state.failed -> R.string.content_update_failed
        state.usable && state.interrupted -> R.string.content_update_interrupted
        state.usable -> return
        state.failed -> R.string.content_download_failed
        state.interrupted -> R.string.content_download_interrupted
        state.idle -> R.string.content_not_downloaded
        else -> R.string.content_downloading
    }

    if (state.loading || running) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(stringResource(message))
    if (state.failed && !state.languageMismatch) {
        Text(stringResource(R.string.content_failure_detail), style = MaterialTheme.typography.bodySmall)
    }
    if (state.retryable) {
        TextButton(onClick = onRetry) { Text(stringResource(R.string.content_retry)) }
    }
}

@Composable
internal fun ContentSection(
    state: HomeContentState,
    title: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Keep saved page state out of the short cache-loading page, including across restoration.
    val pages = rememberSaveableStateHolder()
    if (state.usable) {
        pages.SaveableStateProvider("content", content)
    } else if (state.languageMismatch) {
        Page(title, onBack) {
            Text(stringResource(R.string.content_language_mismatch_detail))
        }
    } else {
        Page(title, onBack) { HomeContentNotice(state, onRetry) }
    }
}

@Composable
internal fun ContentLanguageGate(
    app: io.github.stopcran.kanji.KanjiApp,
    sourceId: String,
    onBack: () -> Unit,
    allowSessionLanguageMismatch: Boolean = false,
    content: @Composable () -> Unit,
) {
    val ready by remember(sourceId) { app.db.content().observeContentReady(sourceId) }.collectAsState(null)
    val meta by remember(sourceId) { app.db.content().observeMeta(sourceId) }.collectAsState(null)
    val uiLanguage = LocalConfiguration.current.locales.get(0)?.language
        ?.takeIf { it == "ru" } ?: "en"
    val sourceLanguage = meta?.baseLanguage ?: app.settings.sync.baseLanguage(sourceId) ?: "en"
    val languageMismatch = !contentLanguageMatches(ready, uiLanguage, sourceLanguage)
    val source by app.settings.source.collectAsState()
    val rolloutBlocked = !allowSessionLanguageMismatch && !app.contentSync.ordinaryEntryAllowed(source)
    when {
        ready == null -> Page(stringResource(R.string.content_reading_cache), onBack) {
            Text(stringResource(R.string.content_reading_cache))
        }

        rolloutBlocked -> Page(stringResource(R.string.content_russian_default_gated), onBack) {
            Text(stringResource(R.string.content_russian_default_gated))
        }

        languageMismatch && !allowSessionLanguageMismatch ->
            Page(stringResource(R.string.content_language_mismatch), onBack) {
                Text(stringResource(R.string.content_language_mismatch_detail))
            }
        ready == false -> Page(stringResource(R.string.content_unavailable_summary), onBack) {
            Text(stringResource(R.string.content_not_downloaded))
        }
        else -> content()
    }
}

private fun contentLanguageMatches(ready: Boolean?, uiLanguage: String, sourceLanguage: String): Boolean {
    if (ready == null) return true
    if (!ready) return true
    return uiLanguage == sourceLanguage
}
