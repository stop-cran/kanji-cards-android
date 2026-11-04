package io.github.stopcran.kanji.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.stopcran.kanji.core.grammar.GrammarPracticePreview
import io.github.stopcran.kanji.core.grammar.GrammarPracticeTarget
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import io.github.stopcran.kanji.core.grammar.GrammarEvidenceDiagnostic
import io.github.stopcran.kanji.core.grammar.GrammarEvidenceDiagnosticId
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.data.GrammarMessage
import io.github.stopcran.kanji.data.GrammarMessageId
import io.github.stopcran.kanji.data.GrammarPreviewNotice
import io.github.stopcran.kanji.data.GrammarPreviewNoticeId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GrammarLocalizationUiTest {
    @get:Rule val rule = createComposeRule()

    private fun localized(language: String): Context {
        val app = RuntimeEnvironment.getApplication()
        val locale = Locale.forLanguageTag(language)
        val configuration = Configuration(app.resources.configuration).apply {
            setLocale(locale)
            setLocales(LocaleList(locale))
        }
        return app.createConfigurationContext(configuration)
    }

    @Test
    fun scopeAndEvidenceDiagnosticsRenderTypedArgumentsInEnglishAndRussian() {
        val message = GrammarMessage(GrammarMessageId.NoUnseenForScope, scope = GrammarScope.N4)
        val diagnostic = GrammarEvidenceDiagnostic(GrammarEvidenceDiagnosticId.FutureDatedEvent, 42)
        val context = mutableStateOf(localized("ru"))
        rule.setContent {
            CompositionLocalProvider(
                LocalContext provides context.value,
                LocalConfiguration provides context.value.resources.configuration,
            ) {
                MaterialTheme {
                    Column {
                        Text(grammarMessageText(message))
                        Text(grammarEvidenceDiagnosticText(diagnostic))
                    }
                }
            }
        }

        rule.onNodeWithText("Нет новых не начатых упражнений по грамматике для охвата «N4 и более простые».").assertExists()
        rule.onNodeWithText("Событие 42 с будущей датой исключено; проверьте часы устройства.").assertExists()

        rule.runOnIdle {
            context.value = localized("en")
        }
        rule.onNodeWithText("No unseen grammar exercises match N4 and easier.").assertExists()
        rule.onNodeWithText("Future-dated event 42 excluded; check the device clock.").assertExists()
    }

    @Test
    fun practiceRecoveryAndStaleNoticesRenderInEnglishAndRussian() {
        val preview = GrammarPracticePreview(
            GrammarPracticeTarget("example/repo", "main", GrammarTargetKind.Group, "group"),
            "Topic", "version", 1, GrammarScope.All, 10, emptyList(), emptyMap(), emptyMap(), null,
        )
        val notices = listOf(
            GrammarPreviewNotice(GrammarPreviewNoticeId.Interrupted),
            GrammarPreviewNotice(GrammarPreviewNoticeId.OverrideConsentExpired),
            GrammarPreviewNotice(GrammarPreviewNoticeId.PreviewChanged),
            GrammarPreviewNotice(GrammarPreviewNoticeId.QueueEmpty),
        )
        val state = GrammarUi.Preview(preview, notices)
        val context = mutableStateOf(localized("ru"))
        rule.setContent {
            CompositionLocalProvider(
                LocalContext provides context.value,
                LocalConfiguration provides context.value.resources.configuration,
            ) {
                MaterialTheme {
                    GrammarPracticePreviewContent(state, sessionKey = "notice-test") {}
                }
            }
        }

        rule.onNodeWithText("Эта тема была открыта повторно после прерывания работы приложения. Просмотрите новую подборку.").assertExists()
        rule.onNodeWithText("Прежнее разрешение на исключения истекло. Просмотрите исключения и подтвердите их заново.").assertExists()
        rule.onNodeWithText("Материалы, охват или доступность изменились. Просмотрите обновлённый план и заново подтвердите исключения.").assertExists()
        rule.onNodeWithText("С такими настройками нет доступных упражнений. Занятие не начато.").assertExists()

        rule.runOnIdle { context.value = localized("en") }
        rule.onNodeWithText("This topic was reopened after the app was interrupted. Review a fresh batch.").assertExists()
        rule.onNodeWithText("Previous exception approval has expired. Review and approve any exceptions again.").assertExists()
        rule.onNodeWithText("Content, scope or availability changed. Review this updated preview and approve any exceptions again.").assertExists()
        rule.onNodeWithText("No exercises are available with these choices. No session was started.").assertExists()
    }
}
