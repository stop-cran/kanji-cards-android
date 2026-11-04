package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Box
import io.github.stopcran.kanji.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

private val CorrectGreen = Color(0xFF2E7D32)
private val WrongRed = Color(0xFFC62828)

enum class Verdict { Correct, Wrong, Neutral }

/** An option row on the answer screen. Not a disabled button: the verdict is also spelled out in text and for screen readers, not colour alone. */
@Composable
fun AnswerOption(label: String, verdict: Verdict) {
    val look = when (verdict) {
        Verdict.Correct -> Look(CorrectGreen, Color.White, "✓ ")
        Verdict.Wrong -> Look(WrongRed, Color.White, "✗ ")
        Verdict.Neutral -> Look(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), MaterialTheme.colorScheme.onSurfaceVariant, "")
    }
    val spoken = when (verdict) {
        Verdict.Correct -> stringResource(R.string.answer_correct_accessibility, label)
        Verdict.Wrong -> stringResource(R.string.answer_wrong_accessibility, label)
        Verdict.Neutral -> label
    }
    Surface(
        color = look.container,
        contentColor = look.content,
        shape = ButtonDefaults.shape,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Box(Modifier.heightIn(min = ButtonDefaults.MinHeight).padding(horizontal = 24.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(look.mark + label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private data class Look(val container: Color, val content: Color, val mark: String)

/** The picked value of an answer when the user chose "I don't know". */
const val NO_ANSWER = ""

@Composable
internal fun SessionRemaining(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.session_questions_remaining, count), style = MaterialTheme.typography.labelMedium)
        InfoDisclosureButton(
            actionDescription = stringResource(R.string.session_repeats_action),
            title = stringResource(R.string.session_repeats_title),
            message = stringResource(R.string.session_repeats_explanation),
        )
    }
}

@Composable
internal fun InfoDisclosureButton(actionDescription: String, title: String, message: String) {
    var showExplanation by rememberSaveable { mutableStateOf(false) }
    IconButton(
        onClick = { showExplanation = true },
        modifier = Modifier.semantics {
            contentDescription = actionDescription
        },
    ) {
        Text("(i)", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
    }
    if (showExplanation) {
        AlertDialog(
            onDismissRequest = { showExplanation = false },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { showExplanation = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
fun DontKnowButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth()) { Text(stringResource(R.string.i_don_t_know)) }
}

/** The article behind a tap after a miss, so the feedback is read before the page; [key] resets the state for each question. */
@Composable
fun ArticleSection(openByDefault: Boolean, key: String, body: String, path: String, onOpenDoc: (String) -> Unit) {
    var open by rememberSaveable(key) { mutableStateOf(openByDefault) }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    if (open) MarkdownView(body, path, onOpenDoc) else OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.read_the_article)) }
}
