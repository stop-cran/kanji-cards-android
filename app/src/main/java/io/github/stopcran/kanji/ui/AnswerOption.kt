package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val CorrectGreen = Color(0xFF2E7D32)
private val WrongRed = Color(0xFFC62828)

enum class Verdict { Correct, Wrong, Neutral }

/** An option row on the answer screen. Not a disabled button: the verdict is also spelled out in text and for screen readers, not colour alone. */
@Composable
fun AnswerOption(label: String, verdict: Verdict) {
    val (container, content, mark, spoken) = when (verdict) {
        Verdict.Correct -> Look(CorrectGreen, Color.White, "✓ ", "Correct answer: ")
        Verdict.Wrong -> Look(WrongRed, Color.White, "✗ ", "Your answer, wrong: ")
        Verdict.Neutral -> Look(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), MaterialTheme.colorScheme.onSurfaceVariant, "", "")
    }
    Surface(
        color = container,
        contentColor = content,
        shape = ButtonDefaults.shape,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken + label },
    ) {
        Box(Modifier.heightIn(min = ButtonDefaults.MinHeight).padding(horizontal = 24.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(mark + label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private data class Look(val container: Color, val content: Color, val mark: String, val spoken: String)

/** The picked value of an answer when the user chose "I don't know". */
const val NO_ANSWER = ""

@Composable
fun DontKnowButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("I don't know") }
}

/** The article behind a tap after a miss, so the feedback is read before the page; [key] resets the state for each question. */
@Composable
fun ArticleSection(openByDefault: Boolean, key: String, body: String, path: String, onOpenDoc: (String) -> Unit) {
    var open by rememberSaveable(key) { mutableStateOf(openByDefault) }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    if (open) MarkdownView(body, path, onOpenDoc) else OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("Read the article") }
}
