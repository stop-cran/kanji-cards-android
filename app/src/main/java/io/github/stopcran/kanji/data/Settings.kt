package io.github.stopcran.kanji.data

import android.content.Context
import io.github.stopcran.kanji.Defaults
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.srs.Stacks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _source = MutableStateFlow(load())
    val source: StateFlow<RepoSource> = _source

    private val _dailyNewCards = MutableStateFlow(prefs.getInt(KEY_NEW, 10))
    val dailyNewCards: StateFlow<Int> = _dailyNewCards

    private val _varyFonts = MutableStateFlow(prefs.getBoolean(KEY_FONTS, true))
    val varyFonts: StateFlow<Boolean> = _varyFonts

    fun setVaryFonts(v: Boolean) {
        prefs.edit().putBoolean(KEY_FONTS, v).apply()
        _varyFonts.value = v
    }

    private val _brush = MutableStateFlow(io.github.stopcran.kanji.ui.BrushStyle.parse(prefs.getString(KEY_BRUSH, null)))
    val brush: StateFlow<io.github.stopcran.kanji.ui.BrushStyle> = _brush

    fun setBrush(b: io.github.stopcran.kanji.ui.BrushStyle) {
        prefs.edit().putString(KEY_BRUSH, b.name).apply()
        _brush.value = b
    }

    private val _saveDrawings = MutableStateFlow(prefs.getBoolean(KEY_SAVE_DRAWINGS, false))
    val saveDrawings: StateFlow<Boolean> = _saveDrawings

    fun setSaveDrawings(v: Boolean) {
        prefs.edit().putBoolean(KEY_SAVE_DRAWINGS, v).apply()
        _saveDrawings.value = v
    }

    private val _stack = MutableStateFlow(prefs.getString(KEY_STACK, Stacks.ALL) ?: Stacks.ALL)
    val stack: StateFlow<String> = _stack

    fun setStack(id: String) {
        prefs.edit().putString(KEY_STACK, id).apply()
        _stack.value = id
    }

    private val _reminderEnabled = MutableStateFlow(prefs.getBoolean(KEY_REMIND, false))
    val reminderEnabled: StateFlow<Boolean> = _reminderEnabled

    fun setReminderEnabled(v: Boolean) {
        prefs.edit().putBoolean(KEY_REMIND, v).apply()
        _reminderEnabled.value = v
    }

    private val _reminderHour = MutableStateFlow(prefs.getInt(KEY_REMIND_HOUR, 19))
    val reminderHour: StateFlow<Int> = _reminderHour

    fun setReminderHour(h: Int) {
        val v = h.coerceIn(0, 23)
        prefs.edit().putInt(KEY_REMIND_HOUR, v).apply()
        _reminderHour.value = v
    }

    /** Reminders sent since the last study session, and when the latest one was sent. */
    var remindersIgnored: Int
        get() = prefs.getInt(KEY_REMIND_IGNORED, 0)
        set(v) = prefs.edit().putInt(KEY_REMIND_IGNORED, v).apply()
    var lastReminderMs: Long
        get() = prefs.getLong(KEY_REMIND_LAST, 0)
        set(v) = prefs.edit().putLong(KEY_REMIND_LAST, v).apply()

    /** Commit SHA of the content branch at the last successful sync, per source id (lets syncs skip an unchanged archive). */
    fun syncedCommit(sourceId: String): String? = prefs.getString("sha:$sourceId", null)
    fun setSyncedCommit(sourceId: String, sha: String) = prefs.edit().putString("sha:$sourceId", sha).apply()
    fun clearSyncedCommit(sourceId: String) = prefs.edit().remove("sha:$sourceId").apply()
    var lastCheckMs: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0)
        set(v) = prefs.edit().putLong(KEY_LAST_CHECK, v).apply()

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
        const val KEY_FONTS = "varyFonts"
        const val KEY_SAVE_DRAWINGS = "saveDrawings"
        const val KEY_BRUSH = "brush"
        const val KEY_STACK = "stack"
        const val KEY_REMIND = "reminder"
        const val KEY_REMIND_HOUR = "reminderHour"
        const val KEY_REMIND_IGNORED = "remindersIgnored"
        const val KEY_REMIND_LAST = "lastReminderMs"
        const val KEY_LAST_CHECK = "lastCheckMs"
    }
}
