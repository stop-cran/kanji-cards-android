package io.github.stopcran.kanji.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.data.SceneImageResult

/**
 * Non-quiz media presentation. Quiz questions use already decoded bitmaps, never a graded placeholder.
 */
@Composable
fun WordImage(result: SceneImageResult?, description: String, modifier: Modifier = Modifier) {
    when (result) {
        null -> Text("Loading picture...", modifier)
        is SceneImageResult.Unavailable -> Text(result.reason, modifier)
        is SceneImageResult.Ready -> SceneBitmap(result.bitmap.asImageBitmap(), description, modifier)
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
