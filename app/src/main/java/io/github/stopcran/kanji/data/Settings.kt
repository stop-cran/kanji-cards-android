package io.github.stopcran.kanji.data

import android.content.Context
import io.github.stopcran.kanji.Defaults
import io.github.stopcran.kanji.core.content.RepoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _source = MutableStateFlow(load())
    val source: StateFlow<RepoSource> = _source

    private val _dailyNewCards = MutableStateFlow(prefs.getInt(KEY_NEW, 10))
    val dailyNewCards: StateFlow<Int> = _dailyNewCards

    val repoUrl: String get() = prefs.getString(KEY_URL, Defaults.CONTENT_REPO_URL) ?: Defaults.CONTENT_REPO_URL
    val branch: String get() = prefs.getString(KEY_BRANCH, Defaults.CONTENT_BRANCH) ?: Defaults.CONTENT_BRANCH

    /** Returns false (and keeps the old value) when the URL or branch is not acceptable. */
    fun setSource(url: String, branch: String): Boolean {
        val parsed = RepoSource.parse(url, branch) ?: return false
        prefs.edit().putString(KEY_URL, url.trim()).putString(KEY_BRANCH, branch.trim()).apply()
        _source.value = parsed
        return true
    }

    fun setDailyNewCards(n: Int) {
        val v = n.coerceIn(0, 200)
        prefs.edit().putInt(KEY_NEW, v).apply()
        _dailyNewCards.value = v
    }

    private fun load(): RepoSource =
        RepoSource.parse(prefs.getString(KEY_URL, null) ?: Defaults.CONTENT_REPO_URL, prefs.getString(KEY_BRANCH, null) ?: Defaults.CONTENT_BRANCH)
            ?: RepoSource.parse(Defaults.CONTENT_REPO_URL, Defaults.CONTENT_BRANCH)!!

    private companion object {
        const val KEY_URL = "repoUrl"
        const val KEY_BRANCH = "branch"
        const val KEY_NEW = "dailyNewCards"
    }
}
