package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.data.DocumentContent
import io.github.stopcran.kanji.data.DocumentRepository
import io.github.stopcran.kanji.data.DocumentScene
import io.github.stopcran.kanji.data.SceneImageResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DocumentUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun presentationDistinguishesLoadingMissingAndContentWithoutAnApplicationService() {
        val state = mutableStateOf<DocumentUi>(DocumentUi.Loading)
        var opened: String? = null
        val scene = DocumentScene("first", "images/first.webp", "First picture", "First sense")
        rule.setContent { MaterialTheme { DocScreen(state.value, "words/item.md", {}, { opened = it }) } }
        rule.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.loading)).assertIsDisplayed()
        rule.runOnIdle { state.value = DocumentUi.Missing }
        rule.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.not_found_in_the_current)).assertIsDisplayed()
        rule.runOnIdle {
            state.value = DocumentUi.Ready(DocumentContent("Item", "[Details](../articles/details.md)", listOf(scene)),
                image = SceneImageResult.Unavailable("Picture unavailable"))
        }
        rule.onNodeWithText("First sense").assertIsDisplayed()
        rule.onNodeWithText("Picture unavailable").assertIsDisplayed()
        rule.onNodeWithText("Details").performClick()
        rule.runOnIdle { assertEquals("articles/details.md", opened) }
    }

    @Test
    fun sceneSelectionRestoresWhileRepositoryObjectsAndLoadsAreRecreated() {
        val scenes = (1..2).map { DocumentScene("scene-$it", "images/$it.webp", "Picture $it", "Sense $it") }
        val document = DocumentContent("Item", "Article body", scenes)
        fun repository() = object : DocumentRepository {
            override fun observe(sourceId: String, path: String) = flowOf(document)
            override suspend fun image(sourceId: String, scene: DocumentScene) = SceneImageResult.Unavailable(scene.description)
        }
        val data = mutableStateOf<DocumentRepository>(repository())
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme { DocRoute(data.value, "first/repo", "words/item.md", {}, {}) }
        }
        rule.onNodeWithText("Next scene").performClick()
        rule.onNodeWithText("Sense 2").assertIsDisplayed()
        rule.runOnIdle { data.value = repository() }
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Sense 2").assertIsDisplayed()
        rule.onNodeWithText("2 / 2").assertIsDisplayed()
    }

    @Test
    fun obsoleteSourcePathAndLateImageLoadsCannotReplaceTheCurrentDocument() {
        val repository = PendingDocuments()
        val request = mutableStateOf("first/repo" to "words/old.md")
        rule.setContent {
            val (source, path) = request.value
            MaterialTheme { DocRoute(repository, source, path, {}, {}) }
        }
        rule.runOnIdle { assertTrue(repository.documents.containsKey(request.value)) }
        rule.runOnIdle { request.value = "second/repo" to "words/new.md" }
        rule.runOnIdle { assertTrue(repository.documents.containsKey(request.value)) }
        val scene = DocumentScene("scene", "images/picture.webp", "Picture", "Sense")
        rule.runOnIdle {
            repository.documents.getValue(request.value).complete(DocumentContent("Second", "Second body", listOf(scene)))
            repository.documents.getValue("first/repo" to "words/old.md").complete(DocumentContent("Obsolete", "Obsolete body"))
        }
        rule.runOnIdle { assertTrue(repository.images.containsKey("second/repo")) }
        rule.runOnIdle { request.value = "third/repo" to "words/new.md" }
        rule.runOnIdle { assertTrue(repository.documents.containsKey(request.value)) }
        rule.runOnIdle {
            repository.documents.getValue(request.value).complete(DocumentContent("Current", "Current body", listOf(scene)))
        }
        rule.runOnIdle { assertTrue(repository.images.containsKey("third/repo")) }
        rule.runOnIdle {
            repository.images.getValue("third/repo").complete(SceneImageResult.Unavailable("Current picture"))
            repository.images.getValue("second/repo").complete(SceneImageResult.Unavailable("Obsolete picture"))
        }
        rule.onNodeWithText("Current body").assertIsDisplayed()
        rule.onNodeWithText("Current picture").assertIsDisplayed()
        rule.onNodeWithText("Obsolete body").assertDoesNotExist()
        rule.onNodeWithText("Obsolete picture").assertDoesNotExist()
    }

    private class PendingDocuments : DocumentRepository {
        val documents = mutableMapOf<Pair<String, String>, CompletableDeferred<DocumentContent?>>()
        val images = mutableMapOf<String, CompletableDeferred<SceneImageResult>>()
        override fun observe(sourceId: String, path: String): Flow<DocumentContent?> = flow {
            emit(documents.getOrPut(sourceId to path) { CompletableDeferred() }.await())
            awaitCancellation()
        }
        override suspend fun image(sourceId: String, scene: DocumentScene): SceneImageResult = withContext(NonCancellable) {
            images.getOrPut(sourceId) { CompletableDeferred() }.await()
        }
    }
}
