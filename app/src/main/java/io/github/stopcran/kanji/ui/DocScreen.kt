package io.github.stopcran.kanji.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.stopcran.kanji.R

/** Lazy content; do not place inside another scrolling parent. */
@Composable
fun DocBody(
    state: DocumentUi, path: String, onOpenDoc: (String) -> Unit, modifier: Modifier = Modifier,
    topic: String? = null, onSelectScene: (String) -> Unit = {},
) {
    when (state) {
        DocumentUi.Loading -> Text(stringResource(R.string.loading), modifier)
        DocumentUi.Missing -> Text(stringResource(R.string.not_found_in_the_current), modifier)
        is DocumentUi.Ready -> {
            val header: (@Composable () -> Unit)? = if (state.document.scenes.isNotEmpty()) {
                { WordSceneGallery(state, onSelectScene) }
            } else null
            LazyMarkdownView(state.document.body, path, onOpenDoc, modifier, header, topic)
        }
    }
}

@Composable
fun DocScreen(
    state: DocumentUi, path: String, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    topic: String? = null, onSelectScene: (String) -> Unit = {},
) {
    val title = (state as? DocumentUi.Ready)?.document?.title ?: path.substringAfter('/').removeSuffix(".md")
    Page(title, onBack, scrollable = false) {
        DocBody(state, path, onOpenDoc, Modifier.weight(1f), topic, onSelectScene)
    }
}
