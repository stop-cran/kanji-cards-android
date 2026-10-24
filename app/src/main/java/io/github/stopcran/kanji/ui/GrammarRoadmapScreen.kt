package io.github.stopcran.kanji.ui

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.data.GrammarBrowserPreferences
import io.github.stopcran.kanji.data.changeGrammarFocus
import io.github.stopcran.kanji.data.grammarChanges
import io.github.stopcran.kanji.data.grammarRoadmap
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
internal fun rememberGrammarMapActions(
    app: KanjiApp,
    source: RepoSource,
    navigate: (GrammarMapDestination) -> Unit,
    article: (String) -> Unit,
): Pair<GrammarMapActions, String?> {
    val scope = rememberCoroutineScope()
    var focusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    return GrammarMapActions(
        navigate, article,
        { id ->
            scope.launch {
                focusMessage = try {
                    require(source == app.settings.source.value) { "The selected content source changed." }
                    app.db.changeGrammarFocus(app.settings, source.id, id, Instant.now())
                    app.getString(R.string.grammar_map_focus_selected)
                } catch (error: IllegalArgumentException) {
                    Log.e("GrammarBrowser", "Cannot select focus", error)
                    error.message ?: app.getString(R.string.grammar_map_focus_error)
                } catch (error: SQLiteException) {
                    Log.e("GrammarBrowser", "Cannot save focus", error)
                    app.getString(R.string.grammar_map_focus_error)
                }
            }
        },
        { app.settings.setGrammarBrowseScope(source.id, it) },
    ) to focusMessage
}

@Composable
internal fun rememberGrammarMap(app: KanjiApp, source: RepoSource): State<GrammarMapState> {
    val preferences = app.settings.grammarPreferences.collectAsState().value[source.id]
    val daily = app.settings.dailyNewCards.collectAsState().value
    val week = app.settings.weekPlan.collectAsState().value
    return produceState<GrammarMapState>(GrammarMapState.Loading, source, preferences, daily, week) {
        app.db.grammarChanges(source.id).collectLatest {
            value = try {
                GrammarMapState.Ready(app.db.grammarRoadmap(app.settings, source, Instant.now()))
            } catch (error: IllegalArgumentException) {
                Log.e("GrammarBrowser", "Cannot load Grammar", error)
                GrammarMapState.Unavailable(error.message ?: "Grammar metadata is unavailable.")
            } catch (error: SQLiteException) {
                Log.e("GrammarBrowser", "Cannot read Grammar", error)
                GrammarMapState.Unavailable("Could not read Grammar. Return and try again.")
            }
        }
    }
}

@Composable
internal fun GrammarRoadmapPage(
    loaded: GrammarMapState,
    destination: GrammarMapDestination,
    actions: GrammarMapActions,
    onBack: () -> Unit,
    focusMessage: String? = null,
    practice: (@Composable () -> Unit)? = null,
) {
    // Save disappearing browser/evidence subtrees, but never restore consent from a transient loading frame.
    val detailState = rememberSaveableStateHolder()
    // The same scroll host survives short loading frames and fresh practice previews.
    Page(stringResource(R.string.grammar_topic_details), onBack, scrollable = loaded != GrammarMapState.Loading, ime = true) {
        Text(stringResource(R.string.grammar_practice_source, destination.sourceId, destination.branch), style = MaterialTheme.typography.bodySmall)
        when (val state = loaded) {
            GrammarMapState.Loading -> Text(stringResource(R.string.loading))
            is GrammarMapState.Unavailable -> Text(state.message, color = MaterialTheme.colorScheme.error)
            is GrammarMapState.Ready -> {
                TextButton(onClick = { actions.navigate(destination.overview()) }) { Text(stringResource(R.string.grammar_browser_title)) }
                GrammarCatalogNotice(state.snapshot.curriculum)
                if (destination.group != null) {
                    val item = state.snapshot.groups[destination.group]
                    if (item == null) Text(stringResource(R.string.grammar_map_removed)) else {
                        GrammarMapGroupDetails(state.snapshot, item, destination, actions, detailState, focusMessage, practice)
                    }
                } else if (destination.target() != null) {
                    GrammarMapCollectionDetails(state.snapshot, destination, actions, detailState, practice)
                } else {
                    detailState.SaveableStateProvider(GrammarRoadmapRoutes.create(destination)) {
                        GrammarMapBrowser(state.snapshot, destination, actions, GrammarBrowserPreferences())
                    }
                }
            }
        }
    }
}

@Composable
internal fun GrammarCatalogNotice(curriculum: GrammarCurriculumState) {
    when (curriculum) {
        is GrammarCurriculumState.Ready -> Unit
        is GrammarCurriculumState.Invalid -> Text(curriculum.reason, color = MaterialTheme.colorScheme.error)
        GrammarCurriculumState.PendingRefresh -> Text(stringResource(R.string.grammar_chapter_sync_needed))
        GrammarCurriculumState.Absent -> Text(stringResource(R.string.grammar_no_chapters))
    }
}
