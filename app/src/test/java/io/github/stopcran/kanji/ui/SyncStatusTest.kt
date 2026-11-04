package io.github.stopcran.kanji.ui

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.content.ContentRejections
import io.github.stopcran.kanji.core.content.ContentType
import io.github.stopcran.kanji.data.SyncActivity
import io.github.stopcran.kanji.data.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "notnight")
class SyncStatusTest {
    @get:Rule val rule = createComposeRule()

    @Test fun rendersAllTotalsAndOnlyNonzeroRejectionsInRedAcrossRestoration() {
        val result = SyncResult.Updated(10, 20, listOf("A diagnostic"), grammar = 30, listening = 40,
            rejected = ContentRejections(mapOf(ContentType.Grammar to 2, ContentType.Listening to 1, ContentType.Words to 0)))
        val restored = StateRestorationTester(rule)
        lateinit var update: (SyncStatus) -> Unit
        restored.setContent {
            var status by rememberSaveable(stateSaver = SyncStatus.Saver) { mutableStateOf(SyncStatus()) }
            update = { status = it }
            KanjiTheme { Column { SyncStatusText(status) } }
        }
        rule.runOnIdle { update(SyncStatus.from(result)) }
        restored.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("30 grammar exercises", substring = true).assertIsDisplayed()
        rule.onNodeWithText("40 listening packages", substring = true).assertIsDisplayed()
        val rejected = rule.onNodeWithText("Grammar: 2 rejected file(s)", substring = true).assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rejected.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(LightScheme.error, layouts.single().layoutInput.style.color)
        assertTrue("Words:" !in layouts.single().layoutInput.text.text)
        rule.runOnIdle { update(SyncStatus.from(SyncResult.Updated(10, 20, emptyList()))) }
        rule.onNodeWithText("Invalid or unsupported content:", substring = true).assertDoesNotExist()
        rule.onNodeWithText("content issue(s)", substring = true).assertDoesNotExist()
    }

    @Test fun failuresDoNotRenderCandidateTotalsAndInterruptedSyncOffersRetry() {
        val status = SyncStatus.from(SyncResult.Failed("Keeping previous content", false,
            ContentRejections(mapOf(ContentType.Kanji to 1))))
        rule.setContent { KanjiTheme { Column { SyncStatusText(status) } } }
        rule.onNodeWithText("Sync failed:", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Kanji: 1 rejected file(s)", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Stored content:", substring = true).assertDoesNotExist()
        assertEquals("Sync interrupted; tap Save & sync to retry.", SyncStatus(SyncStatus.SYNCING).afterRecreation().message)
        assertEquals(status, status.afterRecreation())
        assertEquals(SyncStatus("Up to date"), SyncStatus.from(SyncResult.UpToDate))
    }

    @Test fun retryabilityAndDistinctGuidanceSurviveSavedStateRestoration() {
        val restored = StateRestorationTester(rule)
        lateinit var update: (SyncStatus) -> Unit
        lateinit var read: () -> SyncStatus
        restored.setContent {
            var status by rememberSaveable(stateSaver = SyncStatus.Saver) { mutableStateOf(SyncStatus()) }
            update = { status = it }
            read = { status }
            KanjiTheme { Column { SyncStatusText(status) } }
        }
        for (retryable in listOf(true, false)) {
            rule.runOnIdle { update(SyncStatus.from(SyncResult.Failed("Same failure", retryable))) }
            restored.emulateSavedInstanceStateRestore()
            rule.runOnIdle { assertEquals(retryable, read().retryable) }
            rule.onNodeWithText("Sync failed: Same failure").assertIsDisplayed()
            val hint = if (retryable) "This failure may be temporary." else "Check the error details before retrying."
            rule.onNodeWithText(hint, substring = true).assertIsDisplayed()
        }
        assertEquals(SyncStatus("Old saved failure"), SyncStatus.Saver.restore(listOf("Old saved failure", "", "")))
        rule.runOnIdle { update(SyncStatus.from(SyncResult.UpToDate)) }
        rule.onNodeWithText("Check the error details before retrying.", substring = true).assertDoesNotExist()
    }

    @Test fun candidateStatusUsesCurrentLocaleAfterSavedStateRestorationAndAcceptance() {
        val baseContext = ApplicationProvider.getApplicationContext<Application>()
        val russianConfiguration = Configuration(baseContext.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag("ru"))
        }
        val russianContext = baseContext.createConfigurationContext(russianConfiguration)
        val showRussian = mutableStateOf(false)
        val restored = StateRestorationTester(rule)
        lateinit var update: (SyncStatus) -> Unit
        lateinit var read: () -> SyncStatus
        restored.setContent {
            var status by rememberSaveable(stateSaver = SyncStatus.Saver) {
                mutableStateOf(SyncStatus(baseContext.getString(R.string.source_candidate_checking)))
            }
            update = { status = it }
            read = { status }
            val currentContext = if (showRussian.value) russianContext else baseContext
            CompositionLocalProvider(
                LocalContext provides currentContext,
                LocalConfiguration provides currentContext.resources.configuration,
            ) {
                KanjiTheme { Column { SyncStatusText(status) } }
            }
        }
        rule.onNodeWithText(baseContext.getString(R.string.source_candidate_checking)).assertIsDisplayed()
        rule.runOnIdle { showRussian.value = true }
        val checkingRussian = russianContext.getString(R.string.source_candidate_checking)
        rule.onNodeWithText(checkingRussian).assertIsDisplayed()
        rule.runOnIdle { update(read().withCurrentLocaleCandidateMessage(russianContext)) }
        rule.runOnIdle { assertEquals(CandidateMessage.Checking, read().candidateMessage) }
        restored.emulateSavedInstanceStateRestore()
        rule.onNodeWithText(checkingRussian).assertIsDisplayed()

        val acceptedRussian = russianContext.getString(R.string.source_candidate_accepted)
        rule.runOnIdle { update(SyncStatus.candidate(CandidateMessage.Accepted)) }
        rule.onNodeWithText(acceptedRussian).assertIsDisplayed()
        restored.emulateSavedInstanceStateRestore()
        rule.onNodeWithText(acceptedRussian).assertIsDisplayed()
        assertEquals(
            CandidateMessage.Accepted,
            SyncStatus(acceptedRussian).withCurrentLocaleCandidateMessage(baseContext).candidateMessage,
        )
    }

    @Test fun savedInvalidRepositoryErrorsRelocalizeAcrossRestorationInBothDirections() {
        val baseContext = ApplicationProvider.getApplicationContext<Application>()
        val russianConfiguration = Configuration(baseContext.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag("ru"))
        }
        val russianContext = baseContext.createConfigurationContext(russianConfiguration)
        val showRussian = mutableStateOf(false)
        val restored = StateRestorationTester(rule)
        lateinit var update: (String) -> Unit
        restored.setContent {
            var message by rememberSaveable { mutableStateOf(baseContext.getString(R.string.invalid_repository_url)) }
            update = { message = it }
            val currentContext = if (showRussian.value) russianContext else baseContext
            CompositionLocalProvider(
                LocalContext provides currentContext,
                LocalConfiguration provides currentContext.resources.configuration,
            ) {
                KanjiTheme { Column { SyncStatusText(SyncStatus(message)) } }
            }
        }

        rule.onNodeWithText(baseContext.getString(R.string.invalid_repository_url)).assertIsDisplayed()
        rule.runOnIdle { showRussian.value = true }
        val russianError = russianContext.getString(R.string.invalid_repository_url)
        rule.onNodeWithText(russianError).assertIsDisplayed()
        restored.emulateSavedInstanceStateRestore()
        rule.onNodeWithText(russianError).assertIsDisplayed()

        rule.runOnIdle { update(russianError) }
        rule.runOnIdle { showRussian.value = false }
        val englishError = baseContext.getString(R.string.invalid_repository_url)
        rule.onNodeWithText(englishError).assertIsDisplayed()
        restored.emulateSavedInstanceStateRestore()
        rule.onNodeWithText(englishError).assertIsDisplayed()
    }

    @Test fun failedSameSourceCandidateAcceptanceDoesNotBecomeAccepted() {
        val rejected = candidateAcceptanceStatus(
            SyncResult.Failed("Invalid update; keeping previous content", retryable = false),
            candidateStillCurrent = true,
            selectedSourceMatches = true,
            completionOwned = true,
        )
        val cancelled = candidateAcceptanceStatus(
            SyncResult.Failed("Source selection was cancelled", retryable = true),
            candidateStillCurrent = true,
            selectedSourceMatches = true,
            completionOwned = true,
        )
        val successful = candidateAcceptanceStatus(
            SyncResult.Updated(1, 0, emptyList()),
            candidateStillCurrent = false,
            selectedSourceMatches = true,
            completionOwned = true,
        )

        assertEquals(CandidateMessage.Rejected, rejected?.candidateMessage)
        assertEquals(CandidateMessage.Cancelled, cancelled?.candidateMessage)
        assertEquals(CandidateMessage.Accepted, successful?.candidateMessage)
        assertEquals(
            null,
            candidateAcceptanceStatus(
                SyncResult.Failed("Obsolete candidate", retryable = false),
                candidateStillCurrent = false,
                selectedSourceMatches = true,
                completionOwned = false,
            ),
        )
    }

    @Test fun sameSourceRejectionRendersOverOldUpToDateButYieldsToNewerSyncRevision() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val sourceKey = "owner/repository@main"
        val rejected = requireNotNull(candidateAcceptanceStatus(
            SyncResult.Failed("Invalid update; keeping previous content", retryable = false),
            candidateStillCurrent = true,
            selectedSourceMatches = true,
            completionOwned = true,
        ))
        val oldActivityStatus = SyncStatus.fromActivity(
            SyncActivity.Finished(SyncResult.UpToDate),
            context,
        )
        val latestCandidateStatus = displayedSettingsSyncStatus(
            status = requireNotNull(rejected),
            provenance = SettingsStatusProvenance(sourceKey, sourceKey, 12L, 12L, sameProcess = true),
            activityStatus = oldActivityStatus,
        )
        val newerOrdinaryStatus = displayedSettingsSyncStatus(
            status = requireNotNull(rejected),
            provenance = SettingsStatusProvenance(sourceKey, sourceKey, 12L, 13L, sameProcess = true),
            activityStatus = oldActivityStatus,
        )
        val unrelatedSourceStatus = displayedSettingsSyncStatus(
            status = requireNotNull(rejected),
            provenance = SettingsStatusProvenance(sourceKey, "another/repository@main", 12L, 12L, sameProcess = true),
            activityStatus = null,
        )

        assertEquals(CandidateMessage.Rejected, latestCandidateStatus.candidateMessage)
        assertEquals(context.getString(R.string.sync_up_to_date), newerOrdinaryStatus.message)
        assertEquals(SyncStatus(), unrelatedSourceStatus)
        for (currentRevision in listOf(1L, 12L)) {
            val restoredFromPreviousProcess = displayedSettingsSyncStatus(
                status = rejected,
                provenance = SettingsStatusProvenance(sourceKey, sourceKey, 12L, currentRevision, sameProcess = false),
                activityStatus = oldActivityStatus,
            )
            assertEquals(context.getString(R.string.sync_up_to_date), restoredFromPreviousProcess.message)
        }
        rule.setContent { KanjiTheme { Column { SyncStatusText(latestCandidateStatus) } } }
        rule.onNodeWithText(context.getString(R.string.source_candidate_rejected)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.sync_up_to_date)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.source_candidate_accepted)).assertDoesNotExist()
    }

    @Test fun successfulSupersededSameSourceCompletionCannotReplaceCurrentCandidate() {
        val runs = CandidateRunOwnership()
        val oldRun = runs.start()
        runs.start()
        val completion = candidateAcceptanceStatus(
            SyncResult.Updated(1, 0, emptyList()),
            candidateStillCurrent = false,
            selectedSourceMatches = true,
            completionOwned = runs.finish(oldRun),
        )
        assertEquals(null, completion)
        val currentStatus = completion ?: SyncStatus.candidate(CandidateMessage.Checking)
        val context = ApplicationProvider.getApplicationContext<Application>()
        rule.setContent { KanjiTheme { Column { SyncStatusText(currentStatus) } } }
        rule.onNodeWithText(context.getString(R.string.source_candidate_checking)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.source_candidate_accepted)).assertDoesNotExist()
    }

    @Test fun orphanedCandidateCheckClearsWithoutDiscardingTerminalStatus() {
        assertEquals(
            SyncStatus(),
            SyncStatus.candidate(CandidateMessage.Checking).withoutOrphanedCandidateCheck(),
        )
        val accepted = SyncStatus.candidate(CandidateMessage.Accepted)
        assertEquals(accepted, accepted.withoutOrphanedCandidateCheck())
        val rejected = SyncStatus.candidate(CandidateMessage.Rejected)
        assertEquals(rejected, rejected.withoutOrphanedCandidateCheck())
        val validationError = SyncStatus("Invalid repository URL")
        assertEquals(validationError, validationError.withoutOrphanedCandidateCheck())
    }
}
