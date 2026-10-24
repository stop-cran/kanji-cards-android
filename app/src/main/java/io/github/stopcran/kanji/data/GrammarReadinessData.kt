package io.github.stopcran.kanji.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.withTransaction
import io.github.stopcran.kanji.core.grammar.GrammarAttainment
import io.github.stopcran.kanji.core.grammar.GrammarFacetKey
import io.github.stopcran.kanji.core.grammar.GrammarFocus
import io.github.stopcran.kanji.core.grammar.GrammarReadiness
import io.github.stopcran.kanji.core.grammar.GrammarEvidence
import io.github.stopcran.kanji.core.content.GrammarSet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.time.Instant

@Entity(tableName = "grammar_readiness", primaryKeys = ["sourceId", "groupId", "facetId", "policyVersion"])
data class GrammarReadinessEntity(
    val sourceId: String,
    val groupId: String,
    val facetId: String,
    val policyVersion: Int,
    val attainedAtMs: Long,
)

@Entity(tableName = "grammar_readiness_status")
data class GrammarReadinessStatusEntity(
    @PrimaryKey val sourceId: String,
    val mappingHash: String,
    val complete: Boolean,
    val problem: String? = null,
)

data class GrammarReadinessSnapshot(
    val facets: Set<GrammarFacetKey>,
    val readyGroups: Set<String>,
    val rebuilding: Boolean,
    val problem: String?,
)

internal suspend fun AppDatabase.grammarReadiness(sourceId: String, sets: List<GrammarSet>): GrammarReadinessSnapshot {
    val facets = reviews().grammarReadiness(sourceId, GrammarReadiness.POLICY_VERSION).mapTo(hashSetOf()) {
        GrammarFacetKey(it.groupId, it.facetId)
    }
    val status = reviews().grammarReadinessStatus(sourceId)
    return GrammarReadinessSnapshot(
        facets, GrammarReadiness.readyGroups(GrammarFocus.groups(sets), facets),
        status == null || !status.complete || status.mappingHash != GrammarReadiness.mappingHash(sets), status?.problem,
    )
}

private suspend fun AppDatabase.storeAttainment(sourceId: String, values: List<GrammarAttainment>) {
    if (values.isNotEmpty()) reviews().insertGrammarReadiness(values.map {
        GrammarReadinessEntity(sourceId, it.key.groupId, it.key.facetId, GrammarReadiness.POLICY_VERSION, it.atMs)
    })
}

internal suspend fun AppDatabase.attainGrammarReadiness(sourceId: String, now: Instant, recordedAtMs: Long) {
    val sets = grammarSets(sourceId)
    val groups = GrammarFocus.groups(sets)
    val current = groups.filter { it.authored }.flatMap { group -> group.facets.map { GrammarFacetKey(group.id, it.id) } }.toSet()
    val existing = reviews().grammarReadiness(sourceId, GrammarReadiness.POLICY_VERSION)
    val provisional = existing.filter {
        recordedAtMs <= now.toEpochMilli() && it.attainedAtMs == recordedAtMs && GrammarFacetKey(it.groupId, it.facetId) in current
    }
    val history = GrammarReadiness.History(
        groups, sets.flatMap { it.exercises }.associateBy { it.id },
        (existing - provisional.toSet()).mapTo(hashSetOf()) { GrammarFacetKey(it.groupId, it.facetId) }, now,
    )
    var afterTime = Instant.ofEpochMilli(minOf(recordedAtMs, now.toEpochMilli())).minus(GrammarEvidence.WINDOW).toEpochMilli()
    var afterId = Long.MIN_VALUE
    val attainment = mutableListOf<GrammarAttainment>()
    while (true) {
        val page = reviews().grammarEvidencePage(sourceId, afterTime, afterId, now.toEpochMilli(), 1000)
        attainment += page.flatMap { history.accept(it) }
        if (page.size < 1000) break
        afterTime = page.last().atMs
        afterId = page.last().id
    }
    attainment += history.finish()
    // A timestamp batch is provisional: a later same-timestamp reset must agree with historical replay.
    // Replay from that timestamp's full evidence window even if the answer was delayed; retain earlier/removed milestones.
    provisional.forEach {
        reviews().deleteGrammarReadinessAt(sourceId, it.groupId, it.facetId, it.policyVersion, it.attainedAtMs)
    }
    storeAttainment(sourceId, attainment)
}

private val readinessRebuild = Mutex()

/** Rescan only unearned facets. A restart is safe even if derived rows were lost independently of the status row. */
suspend fun AppDatabase.rebuildGrammarReadiness(sourceId: String, now: Instant = Instant.now()) = readinessRebuild.withLock {
    try {
        scanGrammarReadiness(sourceId, now)
    } catch (error: IllegalArgumentException) {
        reportReadinessFailure(sourceId, error)
        throw error
    } catch (error: android.database.sqlite.SQLiteException) {
        reportReadinessFailure(sourceId, error)
        throw error
    }
}

private suspend fun AppDatabase.reportReadinessFailure(sourceId: String, error: Exception) {
    android.util.Log.e("GrammarReadiness", "Historical readiness rebuild failed.", error)
    val status = reviews().grammarReadinessStatus(sourceId) ?: GrammarReadinessStatusEntity(sourceId, "", false)
    reviews().putGrammarReadinessStatus(status.copy(complete = false, problem = "Readiness rebuild failed: ${error.message}"))
}

private suspend fun AppDatabase.scanGrammarReadiness(sourceId: String, now: Instant) {
    val sets = withTransaction { grammarSets(sourceId) }
    val mapping = GrammarReadiness.mappingHash(sets)
    val groups = GrammarFocus.groups(sets)
    val exercises = sets.flatMap { it.exercises }.associateBy { it.id }
    var history: GrammarReadiness.History? = null
    var checkedThrough = 0L
    reviews().putGrammarReadinessStatus(GrammarReadinessStatusEntity(sourceId, mapping, false))
    var afterTime = Long.MIN_VALUE
    var afterId = Long.MIN_VALUE
    while (true) {
        val more = withTransaction {
            if (GrammarReadiness.mappingHash(grammarSets(sourceId)) != mapping) return@withTransaction false
            val latest = reviews().latestReviewId()
            if (history == null || (latest > checkedThrough && reviews().hasGrammarHistoryAfter(sourceId, checkedThrough))) {
                history = GrammarReadiness.History(groups, exercises, grammarReadiness(sourceId, sets).facets, now)
                afterTime = Long.MIN_VALUE
                afterId = Long.MIN_VALUE
            }
            checkedThrough = latest
            val scanner = requireNotNull(history)
            val page = reviews().grammarEvidencePage(sourceId, afterTime, afterId, now.toEpochMilli(), 1000)
            val attained = page.flatMap { scanner.accept(it) }.toMutableList()
            val done = page.size < 1000
            if (done) attained += scanner.finish()
            storeAttainment(sourceId, attained)
            if (done) reviews().putGrammarReadinessStatus(
                GrammarReadinessStatusEntity(sourceId, mapping, true, scanner.diagnostics.takeIf { it.isNotEmpty() }?.joinToString("\n")),
            )
            if (!done) {
                afterTime = page.last().atMs
                afterId = page.last().id
            }
            !done
        }
        if (!more) return
        yield()
    }
}

val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS grammar_readiness (sourceId TEXT NOT NULL, groupId TEXT NOT NULL, facetId TEXT NOT NULL, " +
                "policyVersion INTEGER NOT NULL, attainedAtMs INTEGER NOT NULL, PRIMARY KEY(sourceId, groupId, facetId, policyVersion))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS grammar_readiness_status (sourceId TEXT NOT NULL PRIMARY KEY, mappingHash TEXT NOT NULL, " +
                "complete INTEGER NOT NULL, problem TEXT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_review_log_sourceId_stack_mode_atMs ON review_log (sourceId, stack, mode, atMs)")
    }
}
