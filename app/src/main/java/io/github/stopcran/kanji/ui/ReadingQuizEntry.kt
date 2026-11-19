package io.github.stopcran.kanji.ui

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.readingtext.ReadingBookMode
import io.github.stopcran.kanji.core.readingtext.ReadingQuizPlan
import io.github.stopcran.kanji.data.ReadingQuizAvailability
import io.github.stopcran.kanji.data.ReadingQuizEntity
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
            ReadingQuizDiscard(quizzes, pending.id)
        }
    }
}

/** UI view of [ReadingQuizStore.observeAvailability]: `null` availability while loading or after a failed check. */
class ReadingQuizAvailabilityState internal constructor(
    val availability: ReadingQuizAvailability?, val failed: Boolean, internal val retry: () -> Unit,
)

/**
 * Shared by the reader and the mode chooser. Re-collected on each resume (and on Retry); [ReadingQuizStore.create]
 * stays authoritative.
 */
@Composable
internal fun rememberReadingQuizAvailability(
    quizzes: ReadingQuizStore, sourceId: String, textId: String,
): ReadingQuizAvailabilityState {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var attempt by remember(sourceId, textId) { mutableIntStateOf(0) }
    var availability by remember(sourceId, textId) { mutableStateOf<ReadingQuizAvailability?>(null) }
    var failed by remember(sourceId, textId) { mutableStateOf(false) }
    LaunchedEffect(lifecycle, sourceId, textId, attempt) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // A fresh check after resume or Retry must not briefly offer a start computed before it.
            availability = null
            failed = false
            try {
                quizzes.observeAvailability(sourceId, textId).collect {
                    availability = it
                    failed = false
                }
            } catch (error: SQLiteException) {
                Log.e("ReadingQuiz", "Could not check Reading quiz availability", error)
                availability = null
                failed = true
            }
        }
    }
    return ReadingQuizAvailabilityState(availability, failed) { attempt++ }
}

@Composable
internal fun ReadingQuizAvailabilityFailure(state: ReadingQuizAvailabilityState) {
    Text(stringResource(R.string.reading_quiz_availability_failed), color = MaterialTheme.colorScheme.error)
    OutlinedButton(onClick = state.retry) { Text(stringResource(R.string.content_retry)) }
}

@Composable
internal fun ReadingQuizEntry(
    app: KanjiApp, destination: AppDestination.TextReading, onBack: () -> Unit, onOpen: (String) -> Unit,
) {
    val state = rememberReadingQuizAvailability(app.services.readingQuizzes, destination.sourceId, destination.textId)
    Page(stringResource(R.string.reading_quiz_title), onBack) {
        when (val current = state.availability) {
            null -> if (state.failed) ReadingQuizAvailabilityFailure(state) else Text(stringResource(R.string.loading))
            is ReadingQuizAvailability.Resume -> {
                Text(stringResource(R.string.reading_quiz_unfinished))
                Button(onClick = { onOpen(current.id) }) { Text(stringResource(R.string.reading_quiz_resume)) }
                ReadingQuizDiscard(app.services.readingQuizzes, current.id)
            }
            else -> ReadingQuizModeChoice(app, destination, current == ReadingQuizAvailability.Available, onOpen)
        }
    }
}

@Composable
private fun ReadingQuizModeChoice(app: KanjiApp, destination: AppDestination.TextReading, allowed: Boolean, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Text(stringResource(R.string.reading_quiz_mode_help))
    if (failed) Text(stringResource(R.string.reading_quiz_start_failed), color = MaterialTheme.colorScheme.error)
    if (!allowed) Text(stringResource(R.string.reading_quiz_no_allowance))
    ReadingBookMode.entries.forEach { mode ->
        val states by remember(destination.sourceId, mode) {
            app.db.reviews().observeStates(destination.sourceId, READING_TEXT_STACK, mode.studyMode().name)
        }.collectAsState(null)
        ReadingQuizSchedule(mode, states?.singleOrNull { it.kanji == destination.textId }, states == null)
        OutlinedButton(enabled = allowed && !busy, onClick = {
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
private fun ReadingQuizDiscard(quizzes: ReadingQuizStore, pendingId: String) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    if (failed) Text(stringResource(R.string.reading_save_failed), color = MaterialTheme.colorScheme.error)
    OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            try {
                quizzes.close(pendingId)
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
