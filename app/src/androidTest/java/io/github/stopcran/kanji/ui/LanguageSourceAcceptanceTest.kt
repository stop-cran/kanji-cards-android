package io.github.stopcran.kanji.ui

import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.MainActivity
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.data.AppLanguage
import io.github.stopcran.kanji.data.AppLocale
import io.github.stopcran.kanji.data.ContentHttp
import io.github.stopcran.kanji.data.ContentResponse
import io.github.stopcran.kanji.data.ContentSync
import io.github.stopcran.kanji.data.PendingSourceCandidate
import io.github.stopcran.kanji.data.SourceCandidateResult
import io.github.stopcran.kanji.data.SyncResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/** Opt-in real-pilot check; use a disposable emulator and push language-acceptance.zip to app external files. */
class LanguageSourceAcceptanceTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test
    fun acceptedRussianArchiveStaysBoundAcrossNativeLocaleChanges() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("languageAcceptance") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KanjiApp
        val archive = File(app.getExternalFilesDir(null), "language-acceptance.zip")
        assertTrue("Push the corrected reviewed RU pilot archive", archive.isFile)
        val oldUrl = app.settings.repoUrl
        val oldBranch = app.settings.branch
        val oldLanguage = app.settings.appLanguage.value
        val oldGuide = app.settings.startingGuideVisible.value
        var scenario: ActivityScenario<MainActivity>? = null
        val candidateUrl = "https://github.com/native-acceptance/ru-${UUID.randomUUID()}"
        val candidateBranch = "ru-pilot-a"
        val source = requireNotNull(RepoSource.parse(candidateUrl, candidateBranch))
        val head = "c".repeat(40)
        val http = fixtureHttp(source, head, archive)
        val sync = ContentSync(app.db, app.settings.sync, http)
        var candidate: PendingSourceCandidate? = null
        try {
            val selectedCandidate = requireNotNull(runBlocking { sync.beginSourceCandidate(app.settings, candidateUrl, candidateBranch) })
            candidate = selectedCandidate
            val validation = runBlocking { sync.validateSourceCandidate(source, selectedCandidate.token) }
            assertTrue("Real package must pass source validation: $validation", validation is SourceCandidateResult.Validated)
            val validated = (validation as SourceCandidateResult.Validated).candidate
            assertEquals("ru", validated.baseLanguage)
            val result = runBlocking {
                sync.acceptSourceCandidate(
                    validated,
                    { app.settings.isCurrentSourceCandidate(selectedCandidate.token) },
                    { accepted -> check(app.settings.acceptSourceCandidate(accepted)) },
                )
            }
            assertTrue("Real package must import through ContentSync: $result", result is SyncResult.Updated)
            app.settings.sync.lastCheckMs = System.currentTimeMillis()
            app.settings.setStartingGuideVisible(false)
            runBlocking { AppLocale.apply(app, AppLanguage.Russian) }
            assertEquals(source, app.settings.source.value)

            scenario = ActivityScenario.launch(MainActivity::class.java)
            show("Кандзи")
            rule.onNodeWithText("Кандзи").performClick()
            rule.waitUntil(30_000) {
                runCatching {
                    rule.onNodeWithText("Все кандзи", substring = true).performScrollTo()
                    rule.onNodeWithText("Все кандзи", substring = true).assertIsDisplayed()
                }.isSuccess
            }
            show("Все кандзи", substring = true)
            rule.onNodeWithText("Все кандзи").performClick()
            rule.onNode(hasSetTextAction()).performTextReplacement("日")
            show("日")
            rule.onAllNodesWithText("日").onLast().performClick()
            show("Значение и происхождение")
            rule.onNodeWithTag("article-body").performScrollToNode(hasText("Чтения"))
            show("Чтения")

            scenario.onActivity { activity -> runBlocking { AppLocale.apply(activity, AppLanguage.English) } }
            show("Saved lessons use a different language.")
            rule.onNodeWithText("Значение и происхождение").assertDoesNotExist()
            assertEquals("The selected repository must not be silently replaced", source, app.settings.source.value)

            scenario.onActivity { activity -> runBlocking { AppLocale.apply(activity, AppLanguage.Russian) } }
            rule.waitUntil(30_000) {
                runCatching { rule.onNodeWithTag("article-body").fetchSemanticsNode() }.isSuccess
            }
            rule.onNodeWithTag("article-body").performScrollToNode(hasText("Значение и происхождение"))
            show("Значение и происхождение")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            show("Все кандзи", substring = true)
        } finally {
            scenario?.close()
            candidate?.takeIf { app.settings.isCurrentSourceCandidate(it.token) }?.let {
                runBlocking { sync.cancelSourceCandidate(app.settings, it.token) }
            }
            runBlocking { AppLocale.apply(app, oldLanguage) }
            app.settings.setSource(oldUrl, oldBranch)
            app.settings.setStartingGuideVisible(oldGuide)
        }
    }

    private fun show(text: String, substring: Boolean = false) {
        rule.waitUntil(30_000) {
            try {
                rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
            } catch (_: IllegalStateException) {
                false
            }
        }
        rule.onAllNodesWithText(text, substring = substring).onFirst().assertIsDisplayed()
    }

    private fun fixtureHttp(source: RepoSource, head: String, archive: File) = ContentHttp { url, _, _, _ ->
        val bytes = when (url) {
            "https://api.github.com/repos/${source.owner}/${source.repo}/commits/${source.branch}" -> head.toByteArray()
            source.commitZipUrl(head) -> archive.readBytes()
            else -> error("Unexpected language-acceptance request: $url")
        }
        object : ContentResponse {
            override val code = 200
            override val body = ByteArrayInputStream(bytes)
            override fun header(name: String): String? = null
            override fun close() = Unit
        }
    }
}
