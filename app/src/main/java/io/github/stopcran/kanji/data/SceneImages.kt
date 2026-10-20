package io.github.stopcran.kanji.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.stopcran.kanji.core.content.ContentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface SceneImageResult {
    data class Ready(val bitmap: Bitmap) : SceneImageResult
    data class Unavailable(val reason: String) : SceneImageResult
}

object SceneImages {
    private const val MAX_SIDE = 2_048
    private const val DISPLAY_SIDE = 512

    fun decode(bytes: ByteArray): SceneImageResult {
        if (bytes.size > ContentParser.MAX_IMAGE_BYTES || !ContentParser.hasWebpEnvelope(bytes)) {
            return SceneImageResult.Unavailable("Picture is not a complete size-limited WebP image.")
        }
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                inSampleSize = 1
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth !in 1..MAX_SIDE || options.outHeight !in 1..MAX_SIDE || options.outMimeType != "image/webp") {
                SceneImageResult.Unavailable("Picture dimensions or format could not be verified.")
            } else {
                options.inJustDecodeBounds = false
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > DISPLAY_SIDE) options.inSampleSize *= 2
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (bitmap == null) SceneImageResult.Unavailable("Picture could not be decoded.")
                else SceneImageResult.Ready(bitmap)
            }
        } catch (e: IllegalArgumentException) {
            SceneImageResult.Unavailable("Picture decoding failed: ${e.message ?: "invalid image"}")
        }
    }

    fun problem(bytes: ByteArray): String? = when (val result = decode(bytes)) {
        is SceneImageResult.Ready -> {
            result.bitmap.recycle()
            null
        }
        is SceneImageResult.Unavailable -> result.reason
    }
}

suspend fun ContentDao.sceneImage(sourceId: String, image: String, expectedHash: String? = null): SceneImageResult =
    withContext(Dispatchers.IO) {
        if (!ContentParser.IMAGE_PATH.matches(image)) return@withContext SceneImageResult.Unavailable("Invalid picture path.")
        val bytes = imageBytes(sourceId, image.removePrefix("images/"))
            ?: return@withContext SceneImageResult.Unavailable("Picture is missing. Refresh content in Settings.")
        if (expectedHash != null && ContentParser.rawSha256(bytes) != expectedHash) {
            return@withContext SceneImageResult.Unavailable("Picture changed. Start a new session with the updated content.")
        }
        SceneImages.decode(bytes)
    }
