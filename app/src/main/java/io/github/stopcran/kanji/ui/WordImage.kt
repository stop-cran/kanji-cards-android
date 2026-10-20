package io.github.stopcran.kanji.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.data.SceneImageResult
import io.github.stopcran.kanji.data.sceneImage

/**
 * Non-quiz media loader. Quiz questions use already decoded bitmaps, never an asynchronous graded placeholder.
 */
@Composable
fun WordImage(
    image: String?, description: String, modifier: Modifier = Modifier, sourceId: String? = null, expectedHash: String? = null,
) {
    if (image == null) return
    val app = LocalContext.current.applicationContext as KanjiApp
    val source by app.settings.source.collectAsState()
    val owner = sourceId ?: source.id
    key(owner, image) {
        val meta by remember(owner) { app.db.content().observeMeta(owner) }.collectAsState(null)
        key(meta, expectedHash) {
            val result by produceState<SceneImageResult?>(null) {
                value = app.db.content().sceneImage(owner, image, expectedHash)
            }
            when (val loaded = result) {
                null -> Text("Loading picture...", modifier)
                is SceneImageResult.Unavailable -> Text(loaded.reason, modifier)
                is SceneImageResult.Ready -> SceneBitmap(loaded.bitmap.asImageBitmap(), description, modifier)
            }
        }
    }
}

@Composable
internal fun SceneBitmap(bitmap: ImageBitmap, description: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            bitmap = bitmap,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
        )
    }
}
