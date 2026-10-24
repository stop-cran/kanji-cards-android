package io.github.stopcran.kanji.ui

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.grammar.GrammarFocus
import io.github.stopcran.kanji.core.grammar.GrammarPractice
import io.github.stopcran.kanji.core.grammar.GrammarPracticeTarget
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import io.github.stopcran.kanji.data.grammarCurriculum
import io.github.stopcran.kanji.data.grammarSets
import kotlinx.coroutines.flow.collectLatest

private sealed interface GrammarPickerState {
    data object Loading : GrammarPickerState
    data class Ready(val sets: List<GrammarSet>, val curriculum: GrammarCurriculumState) : GrammarPickerState
    data class Unavailable(val reason: String) : GrammarPickerState
}

private data class GrammarPickerItem(val id: String, val label: String, val size: Int)

@Composable
internal fun GrammarPracticePicker(app: KanjiApp, onBack: () -> Unit, onSelect: (GrammarPracticeTarget) -> Unit) {
    val source = app.settings.source.collectAsState().value
    key(source.id, source.branch) { GrammarPracticePickerContent(app, source, onBack, onSelect) }
}

@Composable
private fun GrammarPracticePickerContent(
    app: KanjiApp,
    source: RepoSource,
    onBack: () -> Unit,
    onSelect: (GrammarPracticeTarget) -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(GrammarTargetKind.Group) }
    var query by rememberSaveable { mutableStateOf("") }
    val loaded by produceState<GrammarPickerState>(GrammarPickerState.Loading, source) {
        app.db.content().observeMeta(source.id).collectLatest { meta ->
            value = try {
                requireNotNull(meta) { "Sync this source before choosing a practice topic." }
                app.db.withTransaction {
                    val sets = app.db.grammarSets(source.id)
                    GrammarPractice.validateSnapshot(sets)
                    GrammarPickerState.Ready(sets, app.db.grammarCurriculum(app.settings, source.id))
                }
            } catch (error: IllegalArgumentException) {
                Log.e("GrammarPractice", "Cannot load practice topics", error)
                GrammarPickerState.Unavailable(error.message ?: "Practice topics are unavailable.")
            } catch (error: SQLiteException) {
                Log.e("GrammarPractice", "Cannot read practice topics", error)
                GrammarPickerState.Unavailable("Could not read practice topics. Return to Grammar and try again.")
            }
        }
    }
    Page(stringResource(R.string.grammar_choose_topic), onBack, ime = true) {
        Text(stringResource(R.string.grammar_topic_picker_help))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GrammarTargetKind.entries.forEach { option ->
                FilterChip(selected = kind == option, onClick = { kind = option }, label = { Text(targetKindLabel(option)) })
            }
        }
        OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.grammar_search_topics)) }, singleLine = true)
        when (val state = loaded) {
            GrammarPickerState.Loading -> Text(stringResource(R.string.loading))
            is GrammarPickerState.Unavailable -> Text(state.reason)
            is GrammarPickerState.Ready -> {
                val all = pickerItems(state, kind)
                if (kind == GrammarTargetKind.Chapter && state.curriculum !is GrammarCurriculumState.Ready) {
                    Text(
                        when (val curriculum = state.curriculum) {
                            is GrammarCurriculumState.Invalid -> curriculum.reason
                            GrammarCurriculumState.PendingRefresh -> stringResource(R.string.grammar_chapter_sync_needed)
                            else -> stringResource(R.string.grammar_no_chapters)
                        },
                    )
                }
                val matching = all.filter { it.label.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true) }
                    .sortedBy { it.label }
                if (matching.isEmpty()) Text(stringResource(R.string.grammar_no_topics_match))
                key(kind, query) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), state = rememberLazyListState()) {
                        items(matching, key = { it.id }) { item ->
                            TextButton(
                                onClick = { onSelect(GrammarPracticeTarget(source.id, source.branch, kind, item.id)) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(item.label)
                                    Text(stringResource(R.string.grammar_topic_size, item.size), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun pickerItems(state: GrammarPickerState.Ready, kind: GrammarTargetKind): List<GrammarPickerItem> = when (kind) {
    GrammarTargetKind.Set -> state.sets.map { GrammarPickerItem(it.id, it.title, it.exercises.size) }
    GrammarTargetKind.Group -> GrammarFocus.groups(state.sets).map { GrammarPickerItem(it.id, it.label, it.members.size) }
    GrammarTargetKind.Chapter -> {
        val groups = state.sets.flatMap { it.introductionGroups }.associateBy { it.id }
        (state.curriculum as? GrammarCurriculumState.Ready)?.curriculum?.chapters.orEmpty().map { chapter ->
            GrammarPickerItem(chapter.id, chapter.label, chapter.groups.sumOf { groups.getValue(it).members.size })
        }
    }
}

@Composable
private fun targetKindLabel(kind: GrammarTargetKind): String = stringResource(
    when (kind) {
        GrammarTargetKind.Group -> R.string.grammar_groups
        GrammarTargetKind.Set -> R.string.grammar_sets
        GrammarTargetKind.Chapter -> R.string.grammar_chapters
    },
)

@Composable
internal fun GrammarPracticePreviewContent(state: GrammarUi.Preview, onStart: (Boolean) -> Unit) {
    val preview = state.preview
    var includeRestricted by rememberSaveable(preview) { mutableStateOf(false) }
    val included = preview.included(includeRestricted)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(preview.label, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.grammar_practice_source, preview.target.sourceId, preview.target.branch))
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(stringResource(R.string.grammar_target_counts, preview.members.size, preview.members.count { it.started }, preview.members.count { !it.started }))
        Text(stringResource(R.string.grammar_practice_scope, preview.scope.introductionLabel))
        preview.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val outOfScope = preview.members.filter { it.outOfScope }
        if (outOfScope.isNotEmpty()) {
            val unclassified = stringResource(R.string.grammar_unclassified)
            val levels = outOfScope.groupingBy { it.exercise.jlpt?.let { level -> "N$level" } ?: unclassified }
                .eachCount().toSortedMap().entries.joinToString { "${it.key}: ${it.value}" }
            Text(stringResource(R.string.grammar_scope_exception, outOfScope.size, levels))
        }
        preview.members.filter { it.missingPrerequisites.isNotEmpty() }.groupBy { it.groupId }.forEach { (groupId, members) ->
            val foundations = members.flatMap { it.missingPrerequisites }.distinct().map { preview.foundations.getValue(it) }.joinToString()
            Text(stringResource(R.string.grammar_foundation_exception, preview.groups.getValue(groupId), foundations))
        }
        preview.members.mapNotNull { it.problem }.distinct().forEach {
            Text(stringResource(R.string.grammar_cannot_override, it), color = MaterialTheme.colorScheme.error)
        }
        val exceptions = preview.members.count { it.overridable }
        if (exceptions > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().toggleable(
                    value = includeRestricted, role = Role.Checkbox, onValueChange = { includeRestricted = it },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = includeRestricted, onCheckedChange = null)
                Text(stringResource(R.string.grammar_approve_exceptions, exceptions), modifier = Modifier.weight(1f))
            }
        }

        Text(
            stringResource(
                R.string.grammar_available_counts, included.count { it.due }, included.count { !it.started },
                included.count { it.started && !it.due }, preview.members.size - included.size,
            ),
        )
        Text(stringResource(R.string.grammar_initial_batch, preview.initialCount(includeRestricted)))
        Text(stringResource(R.string.grammar_target_extra_policy), style = MaterialTheme.typography.bodySmall)
        if (included.isEmpty()) Text(stringResource(R.string.grammar_target_empty))
        Button(onClick = { onStart(includeRestricted) }, enabled = included.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (includeRestricted) R.string.grammar_start_with_exceptions else R.string.start))
        }
    }
}

@Composable
internal fun GrammarPracticeUnavailable(onBack: () -> Unit, onChoose: () -> Unit) {
    Page(stringResource(R.string.grammar_topic_practice), onBack) {
        Text(stringResource(R.string.grammar_target_restore_unavailable))
        Button(onClick = onChoose) { Text(stringResource(R.string.grammar_choose_topic)) }
    }
}
