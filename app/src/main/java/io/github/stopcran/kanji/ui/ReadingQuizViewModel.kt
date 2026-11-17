package io.github.stopcran.kanji.ui

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.data.CapturedReadingQuiz
import io.github.stopcran.kanji.data.ReadingQuizStore
import io.github.stopcran.kanji.data.SceneImageResult
import kotlinx.coroutines.launch

sealed interface ReadingQuizUi {
    data object Loading : ReadingQuizUi
    data object Unavailable : ReadingQuizUi
    data class Ready(val quiz: CapturedReadingQuiz, val pictures: Map<String, SceneImageResult>) : ReadingQuizUi
}

class ReadingQuizViewModel(private val store: ReadingQuizStore, private val id: String) : ViewModel() {
    private var reloadOnIdle = false
    var ui: ReadingQuizUi by mutableStateOf(ReadingQuizUi.Loading)
        private set
    var busy by mutableStateOf(false)
        private set
    var saveFailed by mutableStateOf(false)
        private set

    init { load() }

    fun load() {
        if (busy) {
            reloadOnIdle = true
            return
        }
        busy = true
        viewModelScope.launch {
            try {
                val quiz = store.load(id)
                require(quiz.saved.closedAtMs == null || quiz.result != null) { "This Reading quiz was discarded" }
                val pictures = store.pictures(quiz)
                pictures.values.filterIsInstance<SceneImageResult.Unavailable>().forEach {
                    Log.w("ReadingQuiz", it.reason)
                }
                ui = ReadingQuizUi.Ready(quiz, pictures)
                saveFailed = false
            } catch (error: IllegalArgumentException) {
                Log.w("ReadingQuiz", "Captured quiz is unavailable", error)
                ui = ReadingQuizUi.Unavailable
            } catch (error: SQLiteException) {
                Log.e("ReadingQuiz", "Could not load captured quiz", error)
                ui = ReadingQuizUi.Unavailable
            } finally {
                idle()
            }
        }
    }

    fun answer(origin: ReadingQuizUi.Ready, optionId: String?) {
        val question = origin.quiz.plan.questions.getOrNull(origin.quiz.answers.size)?.question ?: return
        if (question.image?.let { origin.pictures[it.path] !is SceneImageResult.Ready } == true) return
        update(origin) { store.answer(id, question.id, optionId) }
    }

    fun submit(origin: ReadingQuizUi.Ready) = update(origin) { store.submit(id) }

    private fun update(origin: ReadingQuizUi.Ready, write: suspend () -> CapturedReadingQuiz) {
        if (busy || ui !== origin) return
        busy = true
        viewModelScope.launch {
            try {
                ui = origin.copy(quiz = write())
                saveFailed = false
            } catch (error: IllegalArgumentException) {
                Log.w("ReadingQuiz", "Quiz action no longer matches the capture", error)
                saveFailed = true
                reloadOnIdle = true
            } catch (error: SQLiteException) {
                Log.e("ReadingQuiz", "Could not save quiz action", error)
                saveFailed = true
            } finally {
                idle()
            }
        }
    }

    private fun idle() {
        busy = false
        if (reloadOnIdle) {
            reloadOnIdle = false
            load()
        }
    }
}
