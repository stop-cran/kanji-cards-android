package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun WordSceneGallery(state: DocumentUi.Ready, onSelect: (String) -> Unit) {
    val scenes = state.document.scenes
    val index = state.selectedScene
    val scene = scenes.getOrNull(index) ?: return
    Column {
        scene.sense?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        WordImage(state.image, scene.description)
        if (scene.sense != null) {
            Text(scene.description, style = MaterialTheme.typography.bodyMedium)
            if (!scene.quizEligible) Text("Article illustration only", style = MaterialTheme.typography.labelMedium)
        }
        if (scenes.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onSelect(scenes[index - 1].id) }, enabled = index > 0) { Text("Previous scene") }
                Text("${index + 1} / ${scenes.size}")
                TextButton(onClick = { onSelect(scenes[index + 1].id) }, enabled = index < scenes.lastIndex) { Text("Next scene") }
            }
        }
    }
}
