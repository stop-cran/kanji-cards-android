package io.github.stopcran.kanji.ui

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.data.ReadingReader
import io.github.stopcran.kanji.data.TextReadingRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface TextReadingUi {
    data object Loading : TextReadingUi
    data object Unavailable : TextReadingUi
    data class Ready(val reader: ReadingReader) : TextReadingUi
}

class TextReadingViewModel(
    private val repository: TextReadingRepository,
    private val sourceId: String,
    private val textId: String,
    private val captureId: String? = null,
) : ViewModel() {
    private val writes = Mutex()
    var ui: TextReadingUi by mutableStateOf(TextReadingUi.Loading)
        private set
    var persistenceFailed by mutableStateOf(false)
        private set
    var selectedWords by mutableStateOf<Set<String>>(emptySet())
        private set

    init {
        viewModelScope.launch { repository.selections(sourceId).collect { selectedWords = it.mapTo(mutableSetOf()) { row -> row.word } } }
        load()
    }

    fun load() {
        ui = TextReadingUi.Loading
        viewModelScope.launch {
            try {
                ui = TextReadingUi.Ready(repository.open(sourceId, textId, captureId))
            } catch (error: IllegalArgumentException) {
                Log.w("Reading", "Reading capture unavailable", error)
                ui = TextReadingUi.Unavailable
            } catch (error: SQLiteException) {
                Log.e("Reading", "Could not load reading capture", error)
                ui = TextReadingUi.Unavailable
            }
        }
    }

    fun position(section: Int, offset: Int) = persist { reader ->
        repository.position(reader, section, offset)
        reader.copy(saved = reader.saved.copy(sectionIndex = section, scrollOffset = offset))
    }

    fun toggleFurigana() = persist { reader ->
        val visible = !reader.saved.furigana
        repository.furigana(reader, visible)
        reader.copy(saved = reader.saved.copy(furigana = visible))
    }

    fun complete() = persist { reader ->
        repository.complete(reader)
        reader.copy(saved = reader.saved.copy(completedAtMs = System.currentTimeMillis()))
    }

    fun toggleSelectedWord(word: String) = persist { reader ->
        if (word in selectedWords) repository.removeWord(reader, word) else repository.selectWord(reader, word)
        reader
    }

    private fun persist(update: suspend (ReadingReader) -> ReadingReader) {
        viewModelScope.launch {
            writes.withLock {
                val reader = (ui as? TextReadingUi.Ready)?.reader ?: return@withLock
                try {
                    ui = TextReadingUi.Ready(update(reader))
                    persistenceFailed = false
                } catch (error: SQLiteException) {
                    Log.e("Reading", "Reading state was not saved", error)
                    persistenceFailed = true
                }
            }
        }
    }
}
