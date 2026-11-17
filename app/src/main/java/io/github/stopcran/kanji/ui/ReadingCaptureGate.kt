package io.github.stopcran.kanji.ui

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.data.AppDatabase

internal suspend fun AppDatabase.hasReadingCapture(destination: AppDestination.TextReading): Boolean {
    val hash = destination.captureHash ?: return false
    val saved = reading().reader(destination.sourceId, destination.textId, hash) ?: return false
    return ContentParser.sha256(saved.contentJson.toByteArray()) == saved.contentHash
}

@Composable
internal fun ReadingCaptureGate(app: KanjiApp, destination: AppDestination.TextReading, onBack: () -> Unit, content: @Composable () -> Unit) {
    if (destination.captureHash == null) {
        ContentLanguageGate(app, destination.sourceId, onBack, content = content)
        return
    }
    val captured by produceState<Boolean?>(null, destination) {
        value = try {
            app.db.hasReadingCapture(destination)
        } catch (error: SQLiteException) {
            Log.e("Reading", "Could not verify captured reader admission", error)
            false
        }
    }
    when (captured) {
        null -> Page(stringResource(R.string.reading_title), onBack) { Text(stringResource(R.string.loading)) }
        false -> Page(stringResource(R.string.reading_title), onBack) { Text(stringResource(R.string.reading_unavailable)) }
        true -> content()
    }
}
