package io.github.stopcran.kanji.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.readingtext.ReadingBookMode
import io.github.stopcran.kanji.core.readingtext.ReadingContent
import io.github.stopcran.kanji.data.SceneImageResult
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ReadingQuizStore

@Composable
fun ReadingQuizHost(
    vm: ReadingQuizViewModel, db: AppDatabase, quizzes: ReadingQuizStore,
    onBack: () -> Unit, onResume: (String) -> Unit, onRead: () -> Unit,
) {
    LaunchedEffect(vm) { vm.load() }
    val savedState = rememberSaveableStateHolder()
    val quiz = (vm.ui as? ReadingQuizUi.Ready)?.quiz
    val body: @Composable () -> Unit = {
        savedState.SaveableStateProvider("quiz") { ReadingQuizScreen(vm, onBack, onRead) }
    }
    if (quiz?.result == null) body()
    else ReadingQuizReaderGate(db, quizzes,
        AppDestination.TextReading(quiz.saved.sourceId, quiz.saved.textId, quiz.saved.captureId), onBack, onResume, body)
}

@Composable
fun ReadingQuizScreen(vm: ReadingQuizViewModel, onBack: () -> Unit, onRead: () -> Unit) {
    when (val state = vm.ui) {
        ReadingQuizUi.Loading -> Page(stringResource(R.string.reading_quiz_title), onBack) { Text(stringResource(R.string.loading)) }
        ReadingQuizUi.Unavailable -> Page(stringResource(R.string.reading_quiz_title), onBack) {
            Text(stringResource(R.string.reading_unavailable))
            Button(onClick = vm::load) { Text(stringResource(R.string.content_retry)) }
        }
        is ReadingQuizUi.Ready -> key(state.quiz.saved.id, state.quiz.result != null, state.quiz.answers.size) {
            ReadingQuizReady(state, vm, onBack, onRead)
        }
    }
}

@Composable
private fun ReadingQuizReady(state: ReadingQuizUi.Ready, vm: ReadingQuizViewModel, onBack: () -> Unit, onRead: () -> Unit) {
    val quiz = state.quiz
    val plan = quiz.plan
    Page(quiz.reader.content.title, onBack) {
        Text(stringResource(if (plan.mode == ReadingBookMode.OpenBook) R.string.reading_open_book else R.string.reading_closed_book))
        Text(stringResource(R.string.reading_quiz_requirement, plan.requiredCorrect, plan.highestCount, plan.highestTier))
        if (plan.limitedPool) Text(stringResource(R.string.reading_quiz_limited_pool))
        if (vm.saveFailed) Text(stringResource(R.string.reading_save_failed), color = MaterialTheme.colorScheme.error)
        if (quiz.result != null) {
            ReadingQuizFeedback(state)
            OutlinedButton(onClick = onRead) { Text(stringResource(R.string.reading_quiz_read_text)) }
        } else {
            if (plan.mode == ReadingBookMode.OpenBook) {
                OutlinedButton(onClick = onRead, enabled = !vm.busy) { Text(stringResource(R.string.reading_quiz_read_text)) }
            }
            val captured = plan.questions.getOrNull(quiz.answers.size)
            if (captured == null) {
                Text(stringResource(R.string.reading_quiz_ready_to_submit))
                Button(onClick = { vm.submit(state) }, enabled = !vm.busy) { Text(stringResource(R.string.reading_quiz_submit)) }
            } else {
                Text(stringResource(R.string.reading_quiz_question_progress, quiz.answers.size + 1, plan.questions.size))
                Text(captured.question.prompt, style = MaterialTheme.typography.titleLarge)
                val mediaReady = captured.question.image?.let { picture ->
                    ReadingQuizPicture(state.pictures[picture.path], picture.description)
                    state.pictures[picture.path] is SceneImageResult.Ready
                } ?: true
                if (!mediaReady) Button(onClick = vm::load) { Text(stringResource(R.string.content_retry)) }
                ReadingQuizChoices(state, vm, mediaReady)
            }
        }
    }
}

@Composable
private fun ReadingQuizChoices(state: ReadingQuizUi.Ready, vm: ReadingQuizViewModel, mediaReady: Boolean) {
    val captured = state.quiz.plan.questions[state.quiz.answers.size]
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    captured.optionOrder.forEach { id ->
        val option = captured.question.options.single { it.id == id }
        OutlinedButton(onClick = { selected = id }, enabled = !vm.busy && mediaReady, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (selected == id) R.string.reading_quiz_selected_option else R.string.reading_quiz_option, option.text))
        }
    }
    Button(onClick = { vm.answer(state, selected) }, enabled = selected != null && !vm.busy && mediaReady) {
        Text(stringResource(R.string.reading_quiz_save_answer))
    }
    OutlinedButton(onClick = { vm.answer(state, null) }, enabled = !vm.busy && mediaReady) {
        Text(stringResource(R.string.reading_quiz_unknown))
    }
}

@Composable
private fun ReadingQuizPicture(image: SceneImageResult?, description: String) {
    when (image) {
        is SceneImageResult.Ready -> Image(image.bitmap.asImageBitmap(), description,
            modifier = Modifier.fillMaxWidth().testTag("reading-question-image"))
        else -> Text(stringResource(R.string.reading_quiz_picture_unavailable), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ReadingQuizFeedback(state: ReadingQuizUi.Ready) {
    val quiz = state.quiz
    val result = requireNotNull(quiz.result)
    Text(stringResource(R.string.reading_quiz_score, result.highestCorrect, result.highestCount))
    Text(stringResource(if (result.passed) R.string.reading_quiz_passed else R.string.reading_quiz_not_passed))
    result.answers.forEachIndexed { index, answer ->
        val question = quiz.plan.questions[index].question
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(question.prompt, style = MaterialTheme.typography.titleMedium)
            question.image?.let { ReadingQuizPicture(state.pictures[it.path], it.description) }
            val chosen = question.options.find { it.id == answer.optionId }?.text
            Text(if (chosen == null) stringResource(R.string.reading_quiz_unknown)
                else stringResource(R.string.reading_quiz_chosen_answer, chosen))
            Text(stringResource(R.string.reading_quiz_correct_answer, question.options.single { it.id == question.correctOptionId }.text))
            Text(question.explanation)
            question.evidence.forEach { evidence ->
                val section = quiz.reader.content.sections.single { it.id == evidence.sectionId }
                Text(ReadingContent.slice(section.text, evidence.start, evidence.end))
            }
        }
    }
}
