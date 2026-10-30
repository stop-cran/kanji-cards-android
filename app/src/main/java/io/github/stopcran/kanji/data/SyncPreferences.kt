package io.github.stopcran.kanji.data

import android.content.SharedPreferences
import io.github.stopcran.kanji.core.content.RepoSource

/** Legacy sync hints only; accepted content/catalog metadata remains in its Room transaction. */
class SyncPreferences internal constructor(
    private val prefs: SharedPreferences,
    private val onGrammarEpochChanged: (String, Int) -> Unit,
) {
    fun grammarParserEpoch(sourceId: String): Int = prefs.getInt("$GRAMMAR_EPOCH$sourceId", 0)

    internal fun grammarSources(): Set<String> =
        prefs.all.keys.filter { it.startsWith(GRAMMAR_EPOCH) }.mapTo(hashSetOf()) { it.removePrefix(GRAMMAR_EPOCH) }

    fun setGrammarParserEpoch(sourceId: String, epoch: Int) {
        putEpoch(GRAMMAR_EPOCH, sourceId, epoch)
        onGrammarEpochChanged(sourceId, epoch)
    }

    fun sceneParserEpoch(sourceId: String): Int = prefs.getInt("sceneParserEpoch:$sourceId", 0)
    fun listeningParserEpoch(sourceId: String): Int = prefs.getInt("listeningParserEpoch:$sourceId", 0)
    fun counterParserEpoch(sourceId: String): Int = prefs.getInt("counterParserEpoch:$sourceId", 0)

    fun setSceneParserEpoch(sourceId: String, epoch: Int) = putEpoch("sceneParserEpoch:", sourceId, epoch)
    fun setListeningParserEpoch(sourceId: String, epoch: Int) = putEpoch("listeningParserEpoch:", sourceId, epoch)
    fun setCounterParserEpoch(sourceId: String, epoch: Int) = putEpoch("counterParserEpoch:", sourceId, epoch)

    private fun putEpoch(prefix: String, sourceId: String, epoch: Int) {
        require(epoch >= 0) { "Parser epoch must not be negative." }
        prefs.edit().putInt("$prefix$sourceId", epoch).apply()
    }

    fun syncedCommit(source: RepoSource): String? = prefs.getString("sha:${key(source)}", null)
    fun setSyncedCommit(source: RepoSource, sha: String) = prefs.edit().putString("sha:${key(source)}", sha).apply()
    fun clearSyncedCommit(source: RepoSource) = prefs.edit().remove("sha:${key(source)}").apply()

    fun headEtag(source: RepoSource): Pair<String, String>? =
        prefs.getString("etag:${key(source)}", null)?.split('|')?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun setHeadEtag(source: RepoSource, etag: String, sha: String) =
        prefs.edit().putString("etag:${key(source)}", "$etag|$sha").apply()

    fun fallbackAttemptMs(source: RepoSource): Long = prefs.getLong("fallback:${key(source)}", 0)
    fun setFallbackAttemptMs(source: RepoSource, ms: Long) = prefs.edit().putLong("fallback:${key(source)}", ms).apply()

    /** Historical launch throttle is global, not a per-branch accepted revision. */
    var lastCheckMs: Long
        get() = prefs.getLong("lastCheckMs", 0)
        set(value) = prefs.edit().putLong("lastCheckMs", value).apply()

    private fun key(source: RepoSource) = "${source.id}@${source.branch}"

    private companion object {
        const val GRAMMAR_EPOCH = "grammarParserEpoch:"
    }
}
