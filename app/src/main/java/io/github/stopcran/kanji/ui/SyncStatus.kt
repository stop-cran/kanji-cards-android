package io.github.stopcran.kanji.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import io.github.stopcran.kanji.core.content.ContentRejections
import io.github.stopcran.kanji.core.content.ContentType
import io.github.stopcran.kanji.data.SyncResult

internal data class SyncStatus(val message: String = "", val rejected: String = "", val diagnostic: String = "") {
    fun afterRecreation(): SyncStatus =
        if (message == SYNCING) SyncStatus("Sync interrupted; tap Save & sync to retry.") else this

    companion object {
        const val SYNCING = "Syncing\u2026"
        val Saver = listSaver<SyncStatus, String>(
            save = { listOf(it.message, it.rejected, it.diagnostic) },
            restore = { SyncStatus(it[0], it[1], it[2]) },
        )

        fun from(result: SyncResult): SyncStatus = when (result) {
            is SyncResult.Updated -> SyncStatus(
                "Sync complete. Stored content: ${result.kanji} kanji, ${result.words} words, " +
                    "${result.grammar} grammar exercises, ${result.listening} listening packages.",
                result.rejected.describe(),
                result.problems.firstOrNull()?.let { "${result.problems.size} content issue(s): $it" }.orEmpty(),
            )
            is SyncResult.Failed -> SyncStatus("Sync failed: ${result.message}", result.rejected.describe())
            SyncResult.UpToDate -> SyncStatus("Up to date")
        }
    }
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
    if (status.message.isNotEmpty()) Text(status.message)
    if (status.rejected.isNotEmpty()) Text(status.rejected, color = MaterialTheme.colorScheme.error)
    if (status.diagnostic.isNotEmpty()) Text(status.diagnostic, style = MaterialTheme.typography.bodySmall)
}
