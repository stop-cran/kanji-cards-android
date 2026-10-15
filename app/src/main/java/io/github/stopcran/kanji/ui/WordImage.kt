package io.github.stopcran.kanji.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.KanjiApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_IMAGE_SIDE = 2_048

/** Decodes synced WebP bytes defensively; null for anything that is not a modest-size image. */
internal fun decodeSceneImage(bytes: ByteArray): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..MAX_IMAGE_SIDE || bounds.outHeight !in 1..MAX_IMAGE_SIDE) return@runCatching null
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

/**
 * The scene picture of a word, or nothing when the word has none, the file is missing or it fails to decode;
 * the text-only modes keep working in every such case. [description] is the accessibility text.
 */
@Composable
fun WordImage(image: String?, description: String, modifier: Modifier = Modifier) {
    if (image == null) return
    val app = LocalContext.current.applicationContext as KanjiApp
    val source by app.settings.source.collectAsState()
    val bitmap by produceState<ImageBitmap?>(null, source, image) {
        value = withContext(Dispatchers.IO) {
            app.db.content().imageBytes(source.id, image.removePrefix("images/"))?.let(::decodeSceneImage)
        }
    }
    val loaded = bitmap ?: return
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            bitmap = loaded,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
        )
    }
}
