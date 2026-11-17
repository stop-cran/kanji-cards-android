package io.github.stopcran.kanji.ui

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.readingtext.ReadingBookMode
import io.github.stopcran.kanji.core.readingtext.ReadingQuizPlan
import io.github.stopcran.kanji.data.ReadingQuizEntity
import io.github.stopcran.kanji.data.newAllowance
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.READING_TEXT_STACK
import io.github.stopcran.kanji.data.studyMode
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ReadingQuizStore
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private data class ReadingQuizGateState(val pending: ReadingQuizEntity?, val blocked: Boolean)

@Composable
internal fun ReadingQuizReaderGate(
    db: AppDatabase, quizzes: ReadingQuizStore, destination: AppDestination.TextReading, onBack: () -> Unit,
    onResume: (String) -> Unit, content: @Composable () -> Unit,
) {
    val access by produceState<ReadingQuizGateState?>(null, destination.sourceId, destination.textId) {
        db.reading().observeOpenQuiz(destination.sourceId, destination.textId).collect { pending ->
            val blocked = try {
                pending?.let { Json.decodeFromString(ReadingQuizPlan.serializer(), it.planJson).mode == ReadingBookMode.ClosedBook } == true
            } catch (error: IllegalArgumentException) {
                Log.w("ReadingQuiz", "Could not verify passage access for unfinished quiz", error)
                true
            }
            value = ReadingQuizGateState(pending, blocked)
        }
    }
    val current = access
    when {
        current == null -> Page(stringResource(R.string.reading_title), onBack) { Text(stringResource(R.string.loading)) }
        !current.blocked -> content()
        else -> Page(stringResource(R.string.reading_quiz_title), onBack) {
            Text(stringResource(R.string.reading_quiz_closed_pending))
            val pending = requireNotNull(current.pending)
            Button(onClick = { onResume(pending.id) }) { Text(stringResource(R.string.reading_quiz_resume)) }
            ReadingQuizDiscard(quizzes, pending)
        }
    }
}

@Composable
internal fun ReadingQuizEntry(
    app: KanjiApp, destination: AppDestination.TextReading, onBack: () -> Unit, onOpen: (String) -> Unit,
) {
    val pending by produceState<ReadingQuizGateState?>(null, destination.sourceId, destination.textId) {
        app.db.reading().observeOpenQuiz(destination.sourceId, destination.textId).collect { value = ReadingQuizGateState(it, false) }
    }
    Page(stringResource(R.string.reading_quiz_title), onBack) {
        val current = pending
        if (current == null) Text(stringResource(R.string.loading))
        else if (current.pending != null) {
            Text(stringResource(R.string.reading_quiz_unfinished))
            Button(onClick = { onOpen(current.pending.id) }) { Text(stringResource(R.string.reading_quiz_resume)) }
            ReadingQuizDiscard(app.services.readingQuizzes, current.pending)
        } else ReadingQuizModeChoice(app, destination, onOpen)
    }
}

@Composable
private fun ReadingQuizModeChoice(app: KanjiApp, destination: AppDestination.TextReading, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val allowed by produceState<Boolean?>(null, destination) {
        value = app.db.reading().introduced(destination.sourceId, destination.textId) ||
            app.db.newAllowance(app.settings, destination.sourceId, Instant.now()).remaining > 0
    }
    Text(stringResource(R.string.reading_quiz_mode_help))
    if (failed) Text(stringResource(R.string.reading_quiz_start_failed), color = MaterialTheme.colorScheme.error)
    if (allowed == false) Text(stringResource(R.string.reading_quiz_no_allowance))
    ReadingBookMode.entries.forEach { mode ->
        val states by remember(destination.sourceId, mode) {
            app.db.reviews().observeStates(destination.sourceId, READING_TEXT_STACK, mode.studyMode().name)
        }.collectAsState(null)
        ReadingQuizSchedule(mode, states?.singleOrNull { it.kanji == destination.textId }, states == null)
        OutlinedButton(enabled = allowed == true && !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val reader = app.services.textReading.open(destination.sourceId, destination.textId, destination.captureHash)
                    onOpen(app.services.readingQuizzes.create(reader, mode).saved.id)
                } catch (error: IllegalArgumentException) {
                    Log.w("ReadingQuiz", "Could not start Reading quiz", error)
                    failed = true
                } catch (error: SQLiteException) {
                    Log.e("ReadingQuiz", "Could not persist Reading quiz start", error)
                    failed = true
                } finally {
                    busy = false
                }
            }
        }) {
            Text(stringResource(if (mode == ReadingBookMode.OpenBook) R.string.reading_open_book else R.string.reading_closed_book))
        }
    }
}

@Composable
internal fun ReadingQuizSchedule(mode: ReadingBookMode, state: ReviewStateEntity?, loading: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.systemDefault())
    }
    val status = when {
        loading -> stringResource(R.string.loading)
        state == null || state.phase == "New" -> stringResource(R.string.reading_quiz_mode_new)
        state.dueMs <= System.currentTimeMillis() -> stringResource(R.string.reading_quiz_mode_due)
        else -> stringResource(R.string.reading_quiz_mode_scheduled, formatter.format(Instant.ofEpochMilli(state.dueMs)))
    }
    Text(stringResource(R.string.reading_quiz_schedule,
        stringResource(if (mode == ReadingBookMode.OpenBook) R.string.reading_open_book else R.string.reading_closed_book), status),
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ReadingQuizDiscard(quizzes: ReadingQuizStore, pending: ReadingQuizEntity) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    if (failed) Text(stringResource(R.string.reading_save_failed), color = MaterialTheme.colorScheme.error)
    OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            try {
                quizzes.close(pending.id)
            } catch (error: IllegalArgumentException) {
                Log.w("ReadingQuiz", "Could not discard Reading quiz", error)
                failed = true
            } catch (error: SQLiteException) {
                Log.e("ReadingQuiz", "Could not close Reading quiz", error)
                failed = true
            } finally {
                busy = false
            }
        }
    }) { Text(stringResource(R.string.reading_quiz_discard)) }
}
