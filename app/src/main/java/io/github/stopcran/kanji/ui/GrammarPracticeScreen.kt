package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.R

@Composable
internal fun GrammarPracticePreviewContent(state: GrammarUi.Preview, sessionKey: String, embedded: Boolean = false, onStart: (Boolean) -> Unit) {
    key(sessionKey) { GrammarPracticePreviewBody(state, embedded, onStart) }
}

@Composable
private fun GrammarPracticePreviewBody(state: GrammarUi.Preview, embedded: Boolean, onStart: (Boolean) -> Unit) {
    val preview = state.preview
    var includeRestricted by rememberSaveable(preview) { mutableStateOf(false) }
    val included = preview.included(includeRestricted)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!embedded) {
            Text(preview.label, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.grammar_practice_source, preview.target.sourceId, preview.target.branch))
        }
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
            Text(stringResource(when {
                embedded -> R.string.grammar_practice
                includeRestricted -> R.string.grammar_start_with_exceptions
                else -> R.string.start
            }))
        }
    }
}

@Composable
internal fun GrammarPracticeUnavailable(onBack: () -> Unit, onChoose: () -> Unit) {
    Page(stringResource(R.string.grammar_topic_details), onBack) {
        Text(stringResource(R.string.grammar_target_restore_unavailable))
        Button(onClick = onChoose) { Text(stringResource(R.string.grammar_browser_title)) }
    }
}
