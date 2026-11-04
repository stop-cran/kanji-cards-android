package io.github.stopcran.kanji.ui

import androidx.compose.ui.semantics.liveRegion
import io.github.stopcran.kanji.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.stopcran.kanji.KanjiApp


/** Session key: "kreading:<timestamp>[:extra]". */
@Composable
fun KanjiReadingScreen(sessionKey: String, onBack: () -> Unit, onOpenDoc: (String) -> Unit, onPracticeMore: () -> Unit, vm: KanjiReadingViewModel = serviceViewModel(sessionKey) { KanjiReadingViewModel(it) }) {
    LaunchedEffect(sessionKey) { vm.ensureStarted(sessionKey.endsWith(":extra")) }
    val app = LocalContext.current.applicationContext as KanjiApp
    val unlockRule by app.settings.unlockRule.collectAsState()
    Page(stringResource(R.string.kanji_readings_title), onBack, scrollToTopOnBack = true) {
        when (val s = vm.ui) {
            ReadingUi.Loading -> Text(stringResource(R.string.loading), Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            is ReadingUi.Empty -> Text(
                if (s.reason == ReadingEmptyReason.NoCards) stringResource(R.string.reading_empty_no_cards)
                else stringResource(R.string.reading_empty_unlock_blocked, stringResource(unlockRuleHint(unlockRule))),
            )
            is ReadingUi.Done -> {
                Text(stringResource(R.string.session_complete), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.of_answers_correct, s.correct, s.answered))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
                OutlinedButton(onClick = onPracticeMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.practice_more)) }
            }
            is ReadingUi.Question -> {
                SessionRemaining(s.remaining)
                Text(s.card.kanji, fontSize = bigKanjiSize(120), fontFamily = s.font.family(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.pick_the, stringResource(readingKindLabel(s.question.kind))),
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                s.question.options.forEach { o ->
                    OutlinedButton(onClick = { vm.pick(o.key) }, modifier = Modifier.fillMaxWidth()) { Text(o.label) }
                }
                DontKnowButton { vm.pick(NO_ANSWER) }
            }
            is ReadingUi.Answer -> AnswerState(s, vm, onOpenDoc)
        }
    }
}

@Composable
private fun AnswerState(s: ReadingUi.Answer, vm: KanjiReadingViewModel, onOpenDoc: (String) -> Unit) {
    val kind = s.question.kind
    val right = s.question.options.first { it.key == s.question.correctKey }.label
    val kindLabel = stringResource(readingKindLabel(kind))
    Text(s.card.kanji, fontSize = bigKanjiSize(96), fontFamily = s.font.family(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
        when {
            s.correct -> stringResource(R.string.correct_reading_kind, kindLabel)
            s.picked == NO_ANSWER -> stringResource(R.string.reading_no_problem_answer, kindLabel, s.card.kanji, right)
            else -> stringResource(R.string.reading_not_quite_answer, kindLabel, s.card.kanji, right)
        },
        color = if (s.correct) correctTextColor() else wrongTextColor(),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    if (!s.correct && s.pickedBelongsTo.isNotEmpty()) {
        Text(stringResource(R.string.reading_chosen_kanji_readings, s.pickedBelongsTo.joinToString("、")),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
    s.question.options.forEach { o ->
        AnswerOption(o.label, when {
            o.key == s.question.correctKey -> Verdict.Correct
            o.key == s.picked -> Verdict.Wrong
            else -> Verdict.Neutral
        })
    }
    Text(stringResource(R.string.reading_all_card_readings, s.card.title, s.allReadings.joinToString("、")),
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.next() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.next)) }
        if (s.correct) OutlinedButton(onClick = { vm.next(guessed = true) }) { Text(stringResource(R.string.i_guessed)) }
    }
    ArticleSection(s.correct, "r:${s.card.kanji}:${s.picked}:${s.remaining}", s.card.body, "kanji/${s.card.kanji}.md", onOpenDoc)
}

private fun readingKindLabel(kind: io.github.stopcran.kanji.core.reading.ReadingKind): Int =
    when (kind) {
        io.github.stopcran.kanji.core.reading.ReadingKind.On -> R.string.reading_kind_on
        io.github.stopcran.kanji.core.reading.ReadingKind.Kun -> R.string.reading_kind_kun
    }

private fun unlockRuleHint(value: io.github.stopcran.kanji.core.unlock.UnlockRule): Int = when (value) {
    io.github.stopcran.kanji.core.unlock.UnlockRule.NextDay -> R.string.unlock_next_day_hint
    io.github.stopcran.kanji.core.unlock.UnlockRule.SpacedSameDay -> R.string.unlock_spaced_same_day_hint
}
