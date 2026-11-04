package io.github.stopcran.kanji.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import android.content.Context
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.content.ContentRejections
import io.github.stopcran.kanji.core.content.ContentType
import io.github.stopcran.kanji.data.SyncResult
import io.github.stopcran.kanji.data.SyncActivity
import java.util.Locale

internal enum class CandidateMessage(val resource: Int) {
    Checking(R.string.source_candidate_checking),
    Accepted(R.string.source_candidate_accepted),
    Unavailable(R.string.source_candidate_unavailable),
    LanguageChanged(R.string.source_candidate_language_changed),
    NoCards(R.string.source_candidate_no_cards),
    Damaged(R.string.source_candidate_damaged),
    Cancelled(R.string.source_candidate_cancelled),
    Rejected(R.string.source_candidate_rejected),
    InvalidRepositoryUrl(R.string.invalid_repository_url),
}

internal data class SyncStatus(
    val message: String = "", val rejected: String = "", val diagnostic: String = "", val retryable: Boolean? = null,
    val candidateMessage: CandidateMessage? = null,
) {
    val retryGuidance: String get() = when (retryable) {
        true -> "This failure may be temporary. You can try Save & sync again."
        false -> "Check the error details before retrying. Save & sync remains available."
        null -> ""
    }

    fun afterRecreation(): SyncStatus =
        if (message == SYNCING) SyncStatus("Sync interrupted; tap Save & sync to retry.") else this

    fun afterRecreation(context: Context): SyncStatus =
        if (message == SYNCING) SyncStatus(context.getString(R.string.sync_interrupted)) else this

    fun withoutOrphanedCandidateCheck(): SyncStatus =
        if (candidateMessage == CandidateMessage.Checking) SyncStatus() else this

    fun withCurrentLocaleCandidateMessage(context: Context): SyncStatus {
        if (candidateMessage != null || message.isEmpty()) return this
        val savedLocales = listOf(Locale.ENGLISH, Locale.forLanguageTag("ru")).map { locale ->
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(locale)
            }
            context.createConfigurationContext(configuration)
        }
        val legacyMessage = CandidateMessage.entries.firstOrNull { candidate ->
            context.getString(candidate.resource) == message ||
                savedLocales.any { it.getString(candidate.resource) == message }
        }
        return legacyMessage?.let(::candidate) ?: this
    }

    companion object {
        const val SYNCING = "Syncing\u2026"
        fun fromActivity(activity: SyncActivity?): SyncStatus? = when (activity) {
            SyncActivity.Running -> SyncStatus(SYNCING)
            is SyncActivity.Finished -> from(activity.result)
            SyncActivity.Interrupted -> SyncStatus("Sync interrupted; tap Save & sync to retry.")
            null -> null
        }
        fun fromActivity(activity: SyncActivity?, context: Context): SyncStatus? = when (activity) {
            SyncActivity.Running -> SyncStatus(context.getString(R.string.sync_running))
            is SyncActivity.Finished -> from(activity.result, context)
            SyncActivity.Interrupted -> SyncStatus(context.getString(R.string.sync_interrupted))
            null -> null
        }
        fun candidate(message: CandidateMessage) = SyncStatus(candidateMessage = message)

        val Saver = listSaver<SyncStatus, String>(
            save = {
                listOf(
                    it.message,
                    it.rejected,
                    it.diagnostic,
                    it.retryable?.toString().orEmpty(),
                    it.candidateMessage?.name.orEmpty(),
                )
            },
            restore = {
                SyncStatus(
                    it[0],
                    it[1],
                    it[2],
                    it.getOrNull(3)?.toBooleanStrictOrNull(),
                    it.getOrNull(4)?.let { name -> CandidateMessage.entries.firstOrNull { it.name == name } },
                )
            },
        )

        fun from(result: SyncResult): SyncStatus = when (result) {
            is SyncResult.Updated -> SyncStatus(
                "Sync complete. Stored content: ${result.kanji} kanji, ${result.words} words, " +
                    "${result.grammar} grammar exercises, ${result.listening} listening packages.",
                result.rejected.describe(),
                result.problems.firstOrNull()?.let { "${result.problems.size} content issue(s): $it" }.orEmpty(),
            )
            is SyncResult.Failed -> SyncStatus("Sync failed: ${result.message}", result.rejected.describe(), retryable = result.retryable)
            SyncResult.UpToDate -> SyncStatus("Up to date")
        }

        fun from(result: SyncResult, context: Context): SyncStatus = when (result) {
            is SyncResult.Updated -> SyncStatus(
                context.getString(
                    R.string.sync_complete,
                    result.kanji,
                    result.words,
                    result.grammar,
                    result.listening,
                ),
                result.rejected.describe(context),
                result.problems.size.takeIf { it > 0 }?.let { context.getString(R.string.sync_content_issues, it) }.orEmpty(),
            )
            is SyncResult.Failed -> SyncStatus(
                context.getString(
                    when {
                        result.message.contains("repository language", ignoreCase = true) -> R.string.sync_language_changed
                        result.message.contains("No valid cards", ignoreCase = true) -> R.string.sync_no_valid_cards
                        result.message.contains("Invalid update", ignoreCase = true) -> R.string.sync_invalid_update
                        result.retryable -> R.string.sync_failed_retryable
                        else -> R.string.sync_failed_not_retryable
                    },
                ),
                result.rejected.describe(context),
                retryable = result.retryable,
            )
            SyncResult.UpToDate -> SyncStatus(context.getString(R.string.sync_up_to_date))
        }
    }
}

internal fun candidateAcceptanceStatus(
    result: SyncResult,
    candidateStillCurrent: Boolean,
    selectedSourceMatches: Boolean,
    completionOwned: Boolean,
): SyncStatus? = when {
    !completionOwned -> null
    result is SyncResult.Failed -> if (candidateStillCurrent) {
        SyncStatus.candidate(
            if (result.retryable) CandidateMessage.Cancelled else CandidateMessage.Rejected,
        )
    } else {
        null
    }
    else -> if (selectedSourceMatches) SyncStatus.candidate(CandidateMessage.Accepted) else null
}

internal fun currentTerminalCandidateStatus(
    status: SyncStatus,
    provenance: SettingsStatusProvenance,
): SyncStatus? =
    status.takeIf {
        it.candidateMessage != null &&
            it.candidateMessage != CandidateMessage.Checking &&
            provenance.sameProcess &&
            provenance.statusSource == provenance.sourceKey &&
            provenance.candidateActivityRevision >= provenance.currentActivityRevision
    }

internal data class SettingsStatusProvenance(
    val statusSource: String,
    val sourceKey: String,
    val candidateActivityRevision: Long,
    val currentActivityRevision: Long,
    val sameProcess: Boolean,
)

internal fun displayedSettingsSyncStatus(
    status: SyncStatus,
    provenance: SettingsStatusProvenance,
    activityStatus: SyncStatus?,
): SyncStatus =
    currentTerminalCandidateStatus(
        status,
        provenance,
    ) ?: activityStatus
        ?: status.takeIf { provenance.statusSource == provenance.sourceKey }
        ?: SyncStatus()

private fun ContentRejections.describe(context: Context): String {
    val lines = ContentType.entries.mapNotNull { type ->
        files[type]?.takeIf { it > 0 }?.let { count ->
            val label = when (type) {
                ContentType.Kanji -> R.string.sync_rejected_kanji
                ContentType.Words -> R.string.sync_rejected_words
                ContentType.Grammar -> R.string.sync_rejected_grammar
                ContentType.Counters -> R.string.sync_rejected_counters
                ContentType.Listening -> R.string.sync_rejected_listening
                ContentType.Other -> R.string.sync_rejected_other
            }
            context.getString(R.string.sync_rejected_files, context.getString(label), count)
        }
    } + if (counterQuestions > 0) listOf(context.getString(R.string.sync_rejected_counter_questions, counterQuestions)) else emptyList()
    return if (lines.isEmpty()) "" else
        context.getString(R.string.sync_invalid_content, lines.joinToString("\n"))
}

private fun ContentRejections.describe(): String {
    val lines = ContentType.entries.mapNotNull { type ->
        files[type]?.takeIf { it > 0 }?.let { count ->
            val label = when (type) {
                ContentType.Kanji -> "Kanji"
                ContentType.Words -> "Words"
                ContentType.Grammar -> "Grammar"
                ContentType.Counters -> "Numbers & counters"
                ContentType.Listening -> "Listening"
                ContentType.Other -> "Other"
            }
            "$label: $count rejected file(s)"
        }
    } + if (counterQuestions > 0) listOf("Numbers & counters: $counterQuestions rejected question(s) within imported files") else emptyList()
    return if (lines.isEmpty()) "" else
        "Invalid or unsupported content:\n${lines.joinToString("\n")}\nGrammar/counter files can contain several cards."
}

@Composable
internal fun SyncStatusText(status: SyncStatus) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val localizedStatus = remember(status, configuration) { status.withCurrentLocaleCandidateMessage(context) }
    val message = localizedStatus.candidateMessage?.let { stringResource(it.resource) } ?: localizedStatus.message
    if (message.isNotEmpty()) Text(message)
    val retryGuidance = when (status.retryable) {
        true -> stringResource(R.string.sync_retry_guidance_temporary)
        false -> stringResource(R.string.sync_retry_guidance_not_retryable)
        null -> ""
    }
    if (retryGuidance.isNotEmpty()) Text(retryGuidance, style = MaterialTheme.typography.bodySmall)
    if (status.rejected.isNotEmpty()) Text(status.rejected, color = MaterialTheme.colorScheme.error)
    if (status.diagnostic.isNotEmpty()) Text(status.diagnostic, style = MaterialTheme.typography.bodySmall)
}
