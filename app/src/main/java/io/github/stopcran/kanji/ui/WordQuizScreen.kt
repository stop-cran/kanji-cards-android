package io.github.stopcran.kanji.ui

import androidx.compose.ui.semantics.liveRegion
import io.github.stopcran.kanji.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.TypedVerdict
import io.github.stopcran.kanji.core.words.WordKanjiHelp
import androidx.compose.ui.focus.focusRequester
import io.github.stopcran.kanji.data.WordEntity
import io.github.stopcran.kanji.data.splitSep

private val originTags = setOf("wago", "kango", "gairaigo")

/** Session key: "wquiz:<timestamp>:<jp|en|rd|ty|pc|wp>[:extra]". */
@Composable
fun WordQuizScreen(sessionKey: String, onBack: () -> Unit, onOpenDoc: (String) -> Unit, onPracticeMore: (WordDirection?) -> Unit, vm: WordQuizViewModel = serviceViewModel(sessionKey) { WordQuizViewModel(it) }) {
    val parts = sessionKey.split(':')
    val direction = WordDirection.fromCode(parts.getOrNull(2))
    LaunchedEffect(sessionKey) { vm.ensureStarted(direction, sessionKey.endsWith(":extra")) }
    vm.readingTip?.let { tip ->
        WordReadingTipDialog(tip) { vm.closeReadingTip(tip) }
    }
    Page(
        when (direction) {
            WordDirection.JpToEn -> "Words: Japanese → English"
            WordDirection.EnToJp -> "Words: English → Japanese"
            WordDirection.Reading -> "Words: reading"
            WordDirection.Type -> "Words: type it"
            WordDirection.Picture -> "Words: picture → word"
            WordDirection.WordToPicture -> "Words: word → picture"
        },
        onBack,
        ime = direction == WordDirection.Type,
        scrollToTopOnBack = true,
    ) {
        vm.pictureNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        when (val s = vm.ui) {
            WordQuizUi.Loading -> Text(stringResource(R.string.loading), Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            is WordQuizUi.Empty -> Text(s.message)
            is WordQuizUi.TypeQuestion -> TypeQuestion(s, vm)
            is WordQuizUi.TypeAnswer -> TypeAnswer(s, vm, onOpenDoc)
            is WordQuizUi.Done -> {
                Text(stringResource(R.string.session_complete), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.of_answers_correct, s.correct, s.answered))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
                OutlinedButton(onClick = { onPracticeMore(s.suggestion) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (s.suggestion == null) stringResource(R.string.practice_more) else "Practice more: ${s.suggestion.label}")
                }
            }
            is WordQuizUi.Question -> {
                SessionRemaining(s.remaining)
                if (s.direction == WordDirection.Picture) {
                    val picture = requireNotNull(s.picture)
                    val target = picture.question.target.scene
                    SceneBitmap(picture.images.getValue(target.image), target.description)
                } else WordQuizPrompt(s.word, s.direction, s.font, big = true,
                    help = s.kanjiHelp[s.word.word], onRead = { vm.openReadingTip(s) })
                Text(
                    when (s.direction) {
                        WordDirection.JpToEn -> "What does it mean?"
                        WordDirection.EnToJp -> "Which word is it?"
                        WordDirection.Reading -> "How is it read?"
                        WordDirection.Type -> ""
                        WordDirection.Picture -> "Which word fits this picture?"
                        WordDirection.WordToPicture -> "Which picture fits this word?"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                if (s.direction == WordDirection.WordToPicture) {
                    PictureChoices(requireNotNull(s.picture), onPick = { vm.pick(it, origin = s) })
                } else WordQuizChoices(s) { vm.pick(it, origin = s) }
                DontKnowButton { vm.pick(NO_ANSWER, origin = s) }
                if (s.direction == WordDirection.JpToEn && vm.readingTip == null) {
                    val generation = vm.voiceGeneration
                    key(s, generation) {
                        VoiceButton(s.options.map { it.label }, { i, heard ->
                            vm.pick(s.options[i].word, heard, s, generation)
                        })
                    }
                }
            }
            is WordQuizUi.Answer -> Answer(s, vm, onOpenDoc)
        }
    }
}

@Composable
internal fun WordQuizChoices(question: WordQuizUi.Question, onPick: (String) -> Unit) {
    val japaneseOptions = question.direction in setOf(WordDirection.EnToJp, WordDirection.Picture)
    if (japaneseOptions && question.kanjiHelp.isNotEmpty()) {
        Text(stringResource(R.string.word_kanji_options_legend), style = MaterialTheme.typography.bodySmall)
    }
    question.options.forEach { option ->
        OutlinedButton(onClick = { onPick(option.word) }, modifier = Modifier.fillMaxWidth()) {
            WordOptionLabel(option.label, if (japaneseOptions) question.kanjiHelp[option.word] else null)
        }
    }
}

@Composable
internal fun WordQuizPrompt(
    word: WordEntity, direction: WordDirection, font: io.github.stopcran.kanji.core.srs.KanjiFont, big: Boolean,
    help: WordKanjiHelp? = null, onRead: (() -> Unit)? = null,
) {
    val wordFirst = direction == WordDirection.JpToEn || direction == WordDirection.Reading || direction.usesPictures
    if (wordFirst || !big) {
        WordQuizWord(wordKanjiText(word.word, help, onRead), font, if (big) 64.sp else 48.sp)
        if (help != null && onRead != null) WordReadingHelp(help, onRead)
    } else {
        Text(word.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        val hint = (listOfNotNull(word.type) + word.tags.splitSep()).filter { it !in originTags }.distinct().joinToString(" · ")
        if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun WordQuizWord(text: AnnotatedString, font: io.github.stopcran.kanji.core.srs.KanjiFont, maxFontSize: TextUnit) {
    val style = LocalTextStyle.current
    BasicText(
        text,
        modifier = Modifier.fillMaxWidth(),
        style = style.copy(
            color = if (style.color == Color.Unspecified) LocalContentColor.current else style.color,
            fontSize = maxFontSize, fontFamily = font.family(), textAlign = TextAlign.Center, lineHeight = 1.2.em,
        ),
        autoSize = WordTextAutoSize(maxFontSize),
    )
}

private data class WordTextAutoSize(val maximum: TextUnit) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        fun fits(size: Float) = performLayout(constraints, text, size.sp).let { it.lineCount <= 1 && !it.hasVisualOverflow }
        if (fits(maximum.value)) return maximum
        var low = 28f
        var high = maximum.value
        repeat(8) {
            val middle = (low + high) / 2
            if (fits(middle)) low = middle else high = middle
        }
        // Below this size, keep the text readable and let the proportional line height support wrapping.
        return low.sp
    }
}

@Composable
private fun TypeQuestion(s: WordQuizUi.TypeQuestion, vm: WordQuizViewModel) {
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(s) { runCatching { focus.requestFocus() } }
    SessionRemaining(s.remaining)
    WordQuizPrompt(s.word, WordDirection.Type, s.font, big = true)
    Text("Type the word's Japanese spelling.", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    if (s.word.word != s.word.reading) {
        Text("Reading alone counts as partial recall.", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
    androidx.compose.material3.OutlinedTextField(
        value = vm.draft,
        onValueChange = vm::onDraft,
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            autoCorrectEnabled = false, imeAction = androidx.compose.ui.text.input.ImeAction.Done,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { vm.submit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.submit() }, enabled = vm.draft.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Check") }
        DontKnowButton(Modifier.weight(1f)) { vm.submit(giveUp = true) }
    }
}

@Composable
private fun TypeAnswer(s: WordQuizUi.TypeAnswer, vm: WordQuizViewModel, onOpenDoc: (String) -> Unit) {
    val verdict = s.result.verdict
    WordQuizWord(AnnotatedString(s.word.word), s.font, 56.sp)
    Text(stringResource(R.string.value_dash_detail, s.word.reading, s.word.title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
        when {
            s.gaveUp -> "No problem — here it is"
            verdict == TypedVerdict.Exact -> "Correct!"
            verdict == TypedVerdict.KanaOnly -> "Reading recalled — spelling needs practice"
            else -> "Not quite"
        },
        color = when (verdict) {
            TypedVerdict.Wrong -> wrongTextColor()
            TypedVerdict.KanaOnly -> MaterialTheme.colorScheme.onSurface
            TypedVerdict.Exact -> correctTextColor()
        },
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    if (verdict == TypedVerdict.KanaOnly) {
        Text("Review the spelling above, then choose Next.", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
    if (!s.gaveUp) {
        val matched = s.result.matched?.takeIf { verdict == TypedVerdict.Wrong }
        val text = if (matched != null) "You typed ${s.typed} — ${matched.reading}, ${matched.title}" else "You typed ${s.typed}"
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
    val ok = verdict != TypedVerdict.Wrong
    Button(onClick = { vm.next(origin = s) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text(stringResource(R.string.next)) }
    ArticleSection(ok, "t:${s.word.word}:${s.typed}:${s.remaining}", s.word.body, "words/${s.word.word}.md", onOpenDoc)
}

@Composable
private fun Answer(s: WordQuizUi.Answer, vm: WordQuizViewModel, onOpenDoc: (String) -> Unit) {
    if (s.direction == WordDirection.Picture) {
        val picture = requireNotNull(s.picture)
        val target = picture.question.target.scene
        SceneBitmap(picture.images.getValue(target.image), target.description)
    }
    WordQuizWord(AnnotatedString(s.word.word), s.font, 56.sp)
    Text(stringResource(R.string.value_dash_detail, s.word.reading, s.word.title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    s.picture?.question?.target?.scene?.let {
        Text("${it.sense}: ${it.description}", style = MaterialTheme.typography.bodyMedium)
    }
    Text(
        when {
            s.correct -> "Correct!"
            s.picked == NO_ANSWER -> "No problem — here it is"
            else -> "Not quite"
        },
        color = if (s.correct) correctTextColor() else wrongTextColor(),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    s.pickedWord?.takeIf { !s.correct && s.direction != WordDirection.Reading }?.let {
        Text(stringResource(R.string.you_chose, it.word, it.reading, it.title), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
    if (s.direction == WordDirection.WordToPicture) {
        PictureChoices(requireNotNull(s.picture), picked = s.picked)
    } else s.options.forEach { o ->
        AnswerOption(o.label, when {
            o.word == s.key -> Verdict.Correct
            o.word == s.picked -> Verdict.Wrong
            else -> Verdict.Neutral
        })
    }
    s.heard?.let { VoiceHeard(it) { vm.retry(s) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Button(onClick = { vm.next(origin = s) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.next)) }
        if (s.correct) OutlinedButton(onClick = { vm.next(guessed = true, origin = s) }) { Text(stringResource(R.string.i_guessed)) }
    }
    ArticleSection(s.correct, "w:${s.word.word}:${s.picked}:${s.remaining}", s.word.body, "words/${s.word.word}.md", onOpenDoc)
}

@Composable
internal fun PictureChoices(picture: WordPictureUi, picked: String? = null, onPick: ((String) -> Unit)? = null) {
    val target = picture.question.target.word
    picture.question.options.chunked(2).forEachIndexed { row, options ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { column, option ->
                val scene = picture.question.scenes.getValue(option.word).scene
                val verdict = when {
                    picked == null -> Verdict.Neutral
                    option.word == target -> Verdict.Correct
                    option.word == picked -> Verdict.Wrong
                    else -> Verdict.Neutral
                }
                val color = when (verdict) {
                    Verdict.Correct -> correctTextColor()
                    Verdict.Wrong -> wrongTextColor()
                    Verdict.Neutral -> MaterialTheme.colorScheme.outline
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(2.dp, color),
                ) {
                    Column(
                        Modifier.clickable(enabled = onPick != null, role = Role.Button) { onPick?.invoke(option.word) }.padding(8.dp),
                    ) {
                        SceneBitmap(picture.images.getValue(scene.image), scene.description)
                        Text(
                            when {
                                picked == null -> "Picture ${row * 2 + column + 1}"
                                verdict == Verdict.Correct -> "Correct: ${option.label}"
                                verdict == Verdict.Wrong -> "Your choice: ${option.label}"
                                else -> option.label
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (options.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
