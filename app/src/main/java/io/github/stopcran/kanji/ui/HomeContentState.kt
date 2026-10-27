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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ContentSync
import io.github.stopcran.kanji.data.SyncActivity
import io.github.stopcran.kanji.data.SyncResult

internal data class HomeContentState(val ready: Boolean?, val activity: SyncActivity?) {
    val usable get() = ready == true
    val failure get() = (activity as? SyncActivity.Finished)?.result as? SyncResult.Failed
    val failed get() = failure != null
    val interrupted get() = activity == SyncActivity.Interrupted
    val loading get() = ready == null || (!usable && !failed && !interrupted)

    @Composable
    fun summary(loaded: String): String = if (usable) loaded else stringResource(
        if (loading) R.string.content_loading_summary else R.string.content_unavailable_summary,
    )
}

@Composable
internal fun rememberHomeContent(db: AppDatabase, sync: ContentSync, source: RepoSource): HomeContentState = key(db, sync, source) {
    val ready by remember { db.content().observeContentReady(source.id) }.collectAsState(null)
    val activity by sync.activity.collectAsState()
    HomeContentState(ready, activity[source])
}

@Composable
internal fun HomeContentNotice(state: HomeContentState, onRetry: () -> Unit) {
    val running = state.activity == SyncActivity.Running
    val message = when {
        state.ready == null -> R.string.content_reading_cache
        state.usable && running -> R.string.content_updating
        state.usable && state.failed -> R.string.content_update_failed
        state.usable && state.interrupted -> R.string.content_update_interrupted
        state.usable -> return
        state.failed -> R.string.content_download_failed
        state.interrupted -> R.string.content_download_interrupted
        else -> R.string.content_downloading
    }
    if (state.loading || running) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(stringResource(message))
    state.failure?.let { Text(it.message, style = MaterialTheme.typography.bodySmall) }
    if (state.failed || state.interrupted) {
        TextButton(onClick = onRetry) { Text(stringResource(R.string.content_retry)) }
    }
}
