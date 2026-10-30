package io.github.stopcran.kanji.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.stopcran.kanji.data.DocumentRepository
import io.github.stopcran.kanji.data.SceneImageResult

@Composable
fun DocRoute(
    repository: DocumentRepository, sourceId: String, path: String, onBack: () -> Unit,
    onOpenDoc: (String) -> Unit, topic: String? = null,
) {
    DocumentRouteContent(repository, sourceId, path) { state, select ->
        DocScreen(state, path, onBack, onOpenDoc, topic, select)
    }
}

@Composable
fun DocBodyRoute(
    repository: DocumentRepository, sourceId: String, path: String, onOpenDoc: (String) -> Unit, modifier: Modifier = Modifier,
) {
    DocumentRouteContent(repository, sourceId, path) { state, select ->
        DocBody(state, path, onOpenDoc, modifier, onSelectScene = select)
    }
}

@Composable
private fun DocumentRouteContent(
    repository: DocumentRepository, sourceId: String, path: String,
    content: @Composable (DocumentUi, (String) -> Unit) -> Unit,
) {
    key(sourceId, path) {
        val loaded by key(repository) {
            produceState<DocumentUi>(DocumentUi.Loading) {
                repository.observe(sourceId, path).collect { document ->
                    value = document?.let { DocumentUi.Ready(it) } ?: DocumentUi.Missing
                }
            }
        }
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        val ready = loaded as? DocumentUi.Ready
        val state = if (ready == null) loaded else {
            val index = ready.document.scenes.indexOfFirst { it.id == selected }.coerceAtLeast(0)
            val scene = ready.document.scenes.getOrNull(index)
            val image by key(repository, scene, ready.document.revision) {
                produceState<SceneImageResult?>(null) {
                    if (scene != null) value = repository.image(sourceId, scene)
                }
            }
            ready.copy(selectedScene = index, image = image)
        }
        content(state) { selected = it }
    }
}
