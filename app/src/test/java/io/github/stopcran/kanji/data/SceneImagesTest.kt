package io.github.stopcran.kanji.data

import android.app.Application
import android.graphics.Color
import androidx.room.Room
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.Manifest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SceneImagesTest {
    @Test
    fun decodesEveryDeclaredRealRepositoryImageWhenPresent() {
        val root = File("../../learning-japanese")
        val manifest = File(root, "manifest.json")
        assumeTrue(manifest.exists())
        val declared = Json { ignoreUnknownKeys = true }.decodeFromString(Manifest.serializer(), manifest.readText())
        assertTrue(declared.images.isNotEmpty())
        for ((path, hash) in declared.images) {
            val bytes = File(root, path).readBytes()
            assertEquals(path, hash, ContentParser.rawSha256(bytes))
            assertNull(path, SceneImages.problem(bytes))
        }
    }

    @Test
    fun decodesRealWebpPixelsAndBoundsLargerDisplayAllocations() {
        val result = SceneImages.decode(sceneWebp())
        assertTrue(result is SceneImageResult.Ready)
        val bitmap = (result as SceneImageResult.Ready).bitmap
        assertEquals(4, bitmap.width)
        assertEquals(3, bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(1, 1))
        bitmap.recycle()

        val large = Base64.getDecoder().decode(
            "UklGRk4AAABXRUJQVlA4TEIAAAAv/8P/AAcQ/Y/+BwQCyf7mExTR/4z//Oc///nPf/7zn//85z//+c9//vOf//znP//5z3/+85///Oc///nPf/7zfwM=",
        )
        val sampled = SceneImages.decode(large) as SceneImageResult.Ready
        assertEquals(512, sampled.bitmap.width)
        assertEquals(512, sampled.bitmap.height)
        sampled.bitmap.recycle()
    }

    @Test
    fun rejectsFakeContainersTruncationWrongFormatsAndExcessiveDimensions() {
        val fake = "RIFF".toByteArray() + byteArrayOf(8, 0, 0, 0) + "WEBPxxxx".toByteArray()
        assertTrue(ContentParser.hasWebpEnvelope(fake))
        val tooWide = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4TBUAAAAvAAgAAAcQ/Y/+B4CE8P+8GtH/9AcA")
        for (bytes in listOf(fake, sceneWebp().dropLast(3).toByteArray(), "PNG".toByteArray(), tooWide)) {
            assertTrue(SceneImages.decode(bytes) is SceneImageResult.Unavailable)
            assertNotNull(SceneImages.problem(bytes))
        }
    }

    @Test
    fun loadingChecksTheSourceAndExpectedImageRevision() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val bytes = sceneWebp()
            val hash = ContentParser.rawSha256(bytes)
            db.content().insertImages(listOf(ImageEntity("a/repo", "a.webp", bytes)))
            val ready = db.content().sceneImage("a/repo", "images/a.webp", hash) as SceneImageResult.Ready
            ready.bitmap.recycle()
            assertTrue(db.content().sceneImage("other/repo", "images/a.webp", hash) is SceneImageResult.Unavailable)
            db.content().insertImages(listOf(ImageEntity("a/repo", "a.webp", sceneWebp(blue = true))))
            val changed = db.content().sceneImage("a/repo", "images/a.webp", hash) as SceneImageResult.Unavailable
            assertTrue(changed.reason.contains("changed"))
            db.content().clearImages("a/repo")
            val missing = db.content().sceneImage("a/repo", "images/a.webp", hash) as SceneImageResult.Unavailable
            assertTrue(missing.reason.contains("missing"))
        } finally {
            db.close()
        }
    }
}
