package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.data.WordEntity

@Composable
internal fun WordSceneGallery(app: KanjiApp, sourceId: String, word: String) {
    key(sourceId, word) {
        val scenes by remember(sourceId, word) { app.db.content().observeWordScenes(sourceId, word) }.collectAsState(emptyList())
        val meta by remember(sourceId) { app.db.content().observeMeta(sourceId) }.collectAsState(null)
        val legacy by produceState<WordEntity?>(null, meta) { value = app.db.content().word(sourceId, word) }
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        val index = scenes.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        val scene = scenes.getOrNull(index)
        if (scene == null) {
            legacy?.let { WordImage(it.image, it.imageScene ?: it.title, sourceId = sourceId) }
        } else Column {
            Text(scene.sense, style = MaterialTheme.typography.titleMedium)
            WordImage(scene.image, scene.description, sourceId = sourceId, expectedHash = scene.imageHash)
            Text(scene.description, style = MaterialTheme.typography.bodyMedium)
            if (!scene.quizEligible) Text("Article illustration only", style = MaterialTheme.typography.labelMedium)
            if (scenes.size > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { selected = scenes[index - 1].id }, enabled = index > 0) { Text("Previous scene") }
                    Text("${index + 1} / ${scenes.size}")
                    TextButton(onClick = { selected = scenes[index + 1].id }, enabled = index < scenes.lastIndex) { Text("Next scene") }
                }
            }
        }
    }
}
