package io.github.stopcran.kanji.data

import android.content.Context
import android.util.Log
import io.github.stopcran.kanji.Defaults
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.counters.CounterScope
import io.github.stopcran.kanji.core.focus.FocusBatch
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import io.github.stopcran.kanji.core.listening.ListeningScope
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.unlock.UnlockRule
import io.github.stopcran.kanji.core.words.WordStacks
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class GrammarPreferences(
    val newScope: GrammarScope = GrammarScope.All,
    val browseScope: GrammarScope = GrammarScope.All,
    val parserEpoch: Int = 0,
    val guided: Boolean = false,
) {
    val levelsReady: Boolean get() = parserEpoch >= GRAMMAR_LEVEL_EPOCH
    val groupsReady: Boolean get() = parserEpoch >= GRAMMAR_GROUP_EPOCH
}

data class GrammarBrowserPreferences(val kind: GrammarTargetKind = GrammarTargetKind.Chapter, val diagram: Boolean = true)

data class PendingSourceCandidate(val token: String, val url: String, val branch: String, val source: RepoSource)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val sync = SyncPreferences(prefs, ::updateGrammarEpoch)

    /** Aggregate legacy persistence, including sync hints, for the unchanged progress export. */
    fun snapshot(): Map<String, Any?> = prefs.all.toSortedMap()
    private val _appLanguage = MutableStateFlow(loadAppLanguage(context))
    val appLanguage: StateFlow<AppLanguage> = _appLanguage
    private val _source = MutableStateFlow(load())
    val source: StateFlow<RepoSource> = _source
    private val _pendingSourceCandidate = MutableStateFlow(loadPendingSourceCandidate())
    val pendingSourceCandidate: StateFlow<PendingSourceCandidate?> = _pendingSourceCandidate

    @Synchronized
    internal fun applyLanguageSelection(language: AppLanguage, selectedSource: RepoSource?) {
        val edit = prefs.edit().putString(KEY_APP_LANGUAGE, language.storedValue)
        selectedSource?.let {
            edit.putString(KEY_URL, "https://github.com/${it.owner}/${it.repo}")
                .putString(KEY_BRANCH, it.branch)
                .remove(KEY_PENDING_SOURCE_TOKEN)
                .remove(KEY_PENDING_SOURCE_URL)
                .remove(KEY_PENDING_SOURCE_BRANCH)
        }
        check(edit.commit()) { "Could not persist the selected app language." }
        _appLanguage.value = language
        selectedSource?.let {
            _source.value = it
            _pendingSourceCandidate.value = null
        }
    }

    @Synchronized
    internal fun recoverLanguageSelection(accepted: AcceptedSourceEntity, language: AppLanguage): Boolean {
        val parsed = RepoSource.parse(accepted.url, accepted.branch) ?: return false
        if (parsed.id != accepted.sourceId) return false
        val knownLanguage = sync.baseLanguage(parsed.id)
        if (knownLanguage != null && knownLanguage != accepted.baseLanguage) return false
        val defaultLanguage = when (parsed.id) {
            LanguageSources.defaultForBase("ru").id -> "ru"
            LanguageSources.defaultForBase("en").id -> "en"
            else -> null
        }
        if (defaultLanguage != null && accepted.baseLanguage != defaultLanguage) return false
        applyLanguageSelection(language, parsed)
        return true
    }

    @Synchronized
    internal fun beginSourceCandidate(url: String, branch: String): PendingSourceCandidate? {
        val parsed = RepoSource.parse(url, branch) ?: return null
        val pending = PendingSourceCandidate(UUID.randomUUID().toString(), url.trim(), branch.trim(), parsed)
        prefs.edit()
            .putString(KEY_PENDING_SOURCE_TOKEN, pending.token)
            .putString(KEY_PENDING_SOURCE_URL, pending.url)
            .putString(KEY_PENDING_SOURCE_BRANCH, pending.branch)
            .apply()
        _pendingSourceCandidate.value = pending
        return pending
    }

    @Synchronized
    fun isCurrentSourceCandidate(token: String): Boolean = _pendingSourceCandidate.value?.token == token

    @Synchronized
    internal fun cancelSourceCandidate(token: String? = null) {
        val pending = _pendingSourceCandidate.value ?: return
        if (token != null && token != pending.token) return
        clearPendingSourceCandidate()
    }

    @Synchronized
    internal fun acceptSourceCandidate(accepted: AcceptedSourceEntity): Boolean {
        val pending = _pendingSourceCandidate.value?.takeIf {
            it.token == accepted.token && it.source.id == accepted.sourceId && it.source.branch == accepted.branch
        } ?: return false
        val parsed = RepoSource.parse(accepted.url, accepted.branch) ?: return false
        if (parsed.id != pending.source.id) return false
        val language = AppLanguage.forBaseLanguage(accepted.baseLanguage)
        prefs.edit()
            .putString(KEY_URL, accepted.url)
            .putString(KEY_BRANCH, accepted.branch)
            .putString(KEY_APP_LANGUAGE, language.storedValue)
            .remove(KEY_PENDING_SOURCE_TOKEN)
            .remove(KEY_PENDING_SOURCE_URL)
            .remove(KEY_PENDING_SOURCE_BRANCH)
            .apply()
        _source.value = parsed
        _appLanguage.value = language
        _pendingSourceCandidate.value = null
        return true
    }
    private val _startingGuideVisible = MutableStateFlow(prefs.getBoolean(KEY_STARTING_GUIDE, true))
    val startingGuideVisible: StateFlow<Boolean> = _startingGuideVisible

    fun setStartingGuideVisible(visible: Boolean) {
        prefs.edit().putBoolean(KEY_STARTING_GUIDE, visible).apply()
        _startingGuideVisible.value = visible
    }

    private val _grammarPreferences = MutableStateFlow(loadGrammarPreferences())
    /** Read-only projection of learner choices and the sync owner's parser readiness. */
    val grammarPreferences: StateFlow<Map<String, GrammarPreferences>> = _grammarPreferences

    fun grammarFor(sourceId: String): GrammarPreferences = _grammarPreferences.value[sourceId] ?: GrammarPreferences()

    private val _counterScopes = MutableStateFlow(loadCounterScopes())
    val counterScopes: StateFlow<Map<String, CounterScope>> = _counterScopes

    fun counterScopeFor(sourceId: String): CounterScope = _counterScopes.value[sourceId] ?: CounterScope.N5

    fun setCounterScope(sourceId: String, scope: CounterScope) {
        prefs.edit().putString("$COUNTER_SCOPE$sourceId", scope.code).apply()
        _counterScopes.update { it + (sourceId to scope) }
    }

    private fun loadCounterScopes(): Map<String, CounterScope> =
        prefs.all.filterKeys { it.startsWith(COUNTER_SCOPE) }.mapKeys { it.key.removePrefix(COUNTER_SCOPE) }.mapValues { entry ->
            CounterScope.entries.firstOrNull { it.code == entry.value } ?: CounterScope.N5.also {
                Log.w("CounterScope", "Unknown saved counter scope; using N5.")
            }
        }

    private val _listeningScopes = MutableStateFlow(loadListeningScopes())
    val listeningScopes: StateFlow<Map<String, ListeningScope>> = _listeningScopes

    fun listeningScopeFor(sourceId: String): ListeningScope = _listeningScopes.value[sourceId] ?: ListeningScope.N5

    fun setListeningScope(sourceId: String, scope: ListeningScope) {
        prefs.edit().putString("$LISTENING_SCOPE$sourceId", scope.code).apply()
        _listeningScopes.update { it + (sourceId to scope) }
    }

    private fun loadListeningScopes(): Map<String, ListeningScope> =
        prefs.all.filterKeys { it.startsWith(LISTENING_SCOPE) }.mapKeys { it.key.removePrefix(LISTENING_SCOPE) }.mapValues { entry ->
            ListeningScope.entries.firstOrNull { it.code == entry.value } ?: ListeningScope.N5.also {
                Log.w("ListeningScope", "Unknown saved listening scope; using N5.")
            }
        }

    fun grammarBrowserFor(source: RepoSource): GrammarBrowserPreferences {
        val key = "grammarBrowser:${source.id}|${source.branch}"
        val saved = prefs.getString("$key:kind", null)
        val kind = saved?.let { name -> GrammarTargetKind.entries.firstOrNull { it.name == name } }
        if (saved != null && kind == null) Log.w("GrammarBrowser", "Unknown saved browser kind; using chapters.")
        return GrammarBrowserPreferences(kind ?: GrammarTargetKind.Chapter, prefs.getBoolean("$key:diagram", true))
    }

    fun setGrammarBrowser(source: RepoSource, value: GrammarBrowserPreferences) {
        val key = "grammarBrowser:${source.id}|${source.branch}"
        prefs.edit().putString("$key:kind", value.kind.name).putBoolean("$key:diagram", value.diagram).apply()
    }

    fun setGrammarNewScope(sourceId: String, scope: GrammarScope) {
        prefs.edit().putString("$GRAMMAR_NEW$sourceId", scope.id).apply()
        _grammarPreferences.update { it + (sourceId to (it[sourceId] ?: GrammarPreferences()).copy(newScope = scope)) }
    }

    fun setGrammarBrowseScope(sourceId: String, scope: GrammarScope) {
        prefs.edit().putString("$GRAMMAR_BROWSE$sourceId", scope.id).apply()
        _grammarPreferences.update { it + (sourceId to (it[sourceId] ?: GrammarPreferences()).copy(browseScope = scope)) }
    }

    private fun updateGrammarEpoch(sourceId: String, epoch: Int) {
        _grammarPreferences.update { it + (sourceId to (it[sourceId] ?: GrammarPreferences()).copy(parserEpoch = epoch)) }
    }

    internal fun setGrammarGuided(sourceId: String, guided: Boolean) {
        prefs.edit().putBoolean("$GRAMMAR_GUIDED$sourceId", guided).apply()
        _grammarPreferences.update { it + (sourceId to (it[sourceId] ?: GrammarPreferences()).copy(guided = guided)) }
    }

    private fun loadGrammarPreferences(): Map<String, GrammarPreferences> {
        val prefixes = listOf(GRAMMAR_NEW, GRAMMAR_BROWSE, GRAMMAR_GUIDED)
        val sources = prefs.all.keys.mapNotNull { key ->
            prefixes.firstOrNull { key.startsWith(it) }?.let { key.removePrefix(it) }
        }.toSet() + sync.grammarSources()
        return sources.associateWith { sourceId ->
            GrammarPreferences(
                GrammarScope.fromId(prefs.getString("$GRAMMAR_NEW$sourceId", "all") ?: "all"),
                GrammarScope.fromId(prefs.getString("$GRAMMAR_BROWSE$sourceId", "all") ?: "all"),
                sync.grammarParserEpoch(sourceId),
                prefs.getBoolean("$GRAMMAR_GUIDED$sourceId", false),
            )
        }
    }

    private val _dailyNewCards = MutableStateFlow(prefs.getInt(KEY_NEW, 10))
    val dailyNewCards: StateFlow<Int> = _dailyNewCards

    private val _weekPlan = MutableStateFlow(prefs.getString(KEY_WEEK, null) ?: DEFAULT_WEEK)
    /** New-item level for each weekday, Monday first, as [io.github.stopcran.kanji.core.srs.DayLevel] codes. */
    val weekPlan: StateFlow<String> = _weekPlan

    fun setWeekPlan(plan: String) {
        prefs.edit().putString(KEY_WEEK, plan).apply()
        _weekPlan.value = plan
    }

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

    private val _traceNew = MutableStateFlow(prefs.getBoolean(KEY_TRACE_NEW, true))
    /** Show a light reference to draw over the first time a kanji is asked in Draw. */
    val traceNew: StateFlow<Boolean> = _traceNew

    fun setTraceNew(v: Boolean) {
        prefs.edit().putBoolean(KEY_TRACE_NEW, v).apply()
        _traceNew.value = v
    }

    private val _saveDrawings = MutableStateFlow(prefs.getBoolean(KEY_SAVE_DRAWINGS, false))
    val saveDrawings: StateFlow<Boolean> = _saveDrawings

    fun setSaveDrawings(v: Boolean) {
        prefs.edit().putBoolean(KEY_SAVE_DRAWINGS, v).apply()
        _saveDrawings.value = v
    }

    private val _voiceInput = MutableStateFlow(prefs.getBoolean(KEY_VOICE_INPUT, false))
    val voiceInput: StateFlow<Boolean> = _voiceInput

    fun setVoiceInput(v: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE_INPUT, v).apply()
        _voiceInput.value = v
    }

    private val _unlockRule = MutableStateFlow(UnlockRule.parse(prefs.getString(KEY_UNLOCK, null)))
    /** When Draw and the readings open for a kanji; the default keeps the original two-day rule. */
    val unlockRule: StateFlow<UnlockRule> = _unlockRule

    fun setUnlockRule(r: UnlockRule) {
        prefs.edit().putString(KEY_UNLOCK, r.name).apply()
        _unlockRule.value = r
    }

    private val _focusEnabled = MutableStateFlow(prefs.getBoolean(KEY_FOCUS, false))
    val focusEnabled: StateFlow<Boolean> = _focusEnabled

    /** Turning it off forgets every cutoff, so switching it on again counts only kanji introduced from then on. */
    fun setFocusEnabled(v: Boolean) {
        prefs.edit().apply {
            putBoolean(KEY_FOCUS, v)
            if (!v) prefs.all.keys.filter { it.startsWith(FOCUS_SINCE) }.forEach { remove(it) }
        }.apply()
        _focusEnabled.value = v
    }

    private val _focusSize = MutableStateFlow(prefs.getInt(KEY_FOCUS_SIZE, FocusBatch.DEFAULT_SIZE).coerceIn(FocusBatch.SIZE_RANGE))
    val focusSize: StateFlow<Int> = _focusSize

    fun setFocusSize(n: Int) {
        val v = n.coerceIn(FocusBatch.SIZE_RANGE)
        prefs.edit().putInt(KEY_FOCUS_SIZE, v).apply()
        _focusSize.value = v
    }

    /** A shared JLPT batch adopts the earliest existing level cutoff on upgrade. */
    fun focusSince(sourceId: String, stack: String, nowMs: Long): Long {
        val scope = Stacks.stateId(stack)
        val key = "$FOCUS_SINCE$sourceId|$scope"
        if (prefs.contains(key)) return prefs.getLong(key, nowMs)
        val previous = if (scope == Stacks.JLPT_STATE) {
            Stacks.jlpt.map { "$FOCUS_SINCE$sourceId|${it.id}" }.filter { prefs.contains(it) }.map { prefs.getLong(it, nowMs) }.minOrNull()
        } else null
        return (previous ?: nowMs).also { prefs.edit().putLong(key, it).apply() }
    }

    private val _stack = MutableStateFlow(prefs.getString(KEY_STACK, Stacks.ALL) ?: Stacks.ALL)
    val stack: StateFlow<String> = _stack

    fun setStack(id: String) {
        prefs.edit().putString(KEY_STACK, id).apply()
        _stack.value = id
    }

    private val _wordStack = MutableStateFlow(prefs.getString(KEY_WORD_STACK, WordStacks.n5.id) ?: WordStacks.n5.id)
    val wordStack: StateFlow<String> = _wordStack

    fun setWordStack(id: String) {
        prefs.edit().putString(KEY_WORD_STACK, id).apply()
        _wordStack.value = id
    }

    private val _n4Unlocked = MutableStateFlow(prefs.getBoolean(KEY_N4, false))
    val n4Unlocked: StateFlow<Boolean> = _n4Unlocked

    fun setN4Unlocked(v: Boolean) {
        prefs.edit().putBoolean(KEY_N4, v).apply()
        _n4Unlocked.value = v
    }

    /** When the user last dismissed the offer to move on to N4 (0 = never). */
    private val _advanceDismissedMs = MutableStateFlow(prefs.getLong(KEY_ADVANCE_DISMISSED, 0))
    val advanceDismissedMs: StateFlow<Long> = _advanceDismissedMs

    fun dismissAdvance(nowMs: Long) {
        prefs.edit().putLong(KEY_ADVANCE_DISMISSED, nowMs).apply()
        _advanceDismissedMs.value = nowMs
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

    /** Index of the illustration set shown in the previous session, or -1. */
    var artSetLast: Int
        get() = prefs.getInt(KEY_ART_SET, -1)
        set(v) = prefs.edit().putInt(KEY_ART_SET, v).apply()

    val repoUrl: String get() = prefs.getString(KEY_URL, null) ?: "https://github.com/${source.value.owner}/${source.value.repo}"
    val branch: String get() = prefs.getString(KEY_BRANCH, null) ?: source.value.branch

    /** Setup/test initializer; production source changes must use ContentSync's serialized authority protocol. */
    internal fun setSource(url: String, branch: String): Boolean {
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

    private fun load(): RepoSource {
        val savedUrl = prefs.getString(KEY_URL, null)
        val savedBranch = prefs.getString(KEY_BRANCH, null)
        if (savedUrl == null && savedBranch == null && AppLocale.resolvedLanguage(_appLanguage.value) == "ru") {
            return LanguageSources.default(AppLanguage.Russian)
        }
        return RepoSource.parse(savedUrl ?: Defaults.CONTENT_REPO_URL, savedBranch ?: Defaults.CONTENT_BRANCH) ?: defaultSource
    }

    private fun loadPendingSourceCandidate(): PendingSourceCandidate? {
        val token = prefs.getString(KEY_PENDING_SOURCE_TOKEN, null) ?: return null
        val url = prefs.getString(KEY_PENDING_SOURCE_URL, null) ?: return null
        val branch = prefs.getString(KEY_PENDING_SOURCE_BRANCH, null) ?: return null
        val source = RepoSource.parse(url, branch) ?: return null
        return PendingSourceCandidate(token, url, branch, source)
    }

    private fun loadAppLanguage(context: Context): AppLanguage {
        val saved = prefs.getString(KEY_APP_LANGUAGE, null)
        if (saved != null) return AppLanguage.parse(saved)
        val firstInstall = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        }.getOrDefault(0L)
        val lastUpdate = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        return (if (firstInstall == 0L || firstInstall == lastUpdate) AppLanguage.System else AppLanguage.English).also {
            prefs.edit().putString(KEY_APP_LANGUAGE, it.storedValue).apply()
        }
    }

    private fun clearPendingSourceCandidate() {
        prefs.edit()
            .remove(KEY_PENDING_SOURCE_TOKEN)
            .remove(KEY_PENDING_SOURCE_URL)
            .remove(KEY_PENDING_SOURCE_BRANCH)
            .apply()
        _pendingSourceCandidate.value = null
    }

    private companion object {
        val defaultSource = RepoSource("stop-cran", "learning-japanese", Defaults.CONTENT_BRANCH)
        const val KEY_URL = "repoUrl"
        const val KEY_BRANCH = "branch"
        private const val KEY_APP_LANGUAGE = "appLanguage"
        private const val KEY_PENDING_SOURCE_TOKEN = "pendingSourceToken"
        private const val KEY_PENDING_SOURCE_URL = "pendingSourceUrl"
        private const val KEY_PENDING_SOURCE_BRANCH = "pendingSourceBranch"
        const val KEY_STARTING_GUIDE = "startingGuideVisible"
        const val KEY_NEW = "dailyNewCards"
        const val KEY_WEEK = "weekPlan"
        const val DEFAULT_WEEK = "FFFFFLL"
        const val KEY_FONTS = "varyFonts"
        const val KEY_SAVE_DRAWINGS = "saveDrawings"
        const val KEY_TRACE_NEW = "traceNewKanji"
        const val KEY_VOICE_INPUT = "voiceInput"
        const val KEY_UNLOCK = "unlockRule"
        const val KEY_FOCUS = "focusBatch"
        const val KEY_FOCUS_SIZE = "focusBatchSize"
        const val FOCUS_SINCE = "focusSince:"
        const val KEY_BRUSH = "brush"
        const val KEY_STACK = "stack"
        const val KEY_WORD_STACK = "wordStack"
        const val KEY_N4 = "n4Unlocked"
        const val KEY_ART_SET = "artSetLast"
        const val KEY_ADVANCE_DISMISSED = "advanceDismissedMs"
        const val KEY_REMIND = "reminder"
        const val KEY_REMIND_HOUR = "reminderHour"
        const val KEY_REMIND_IGNORED = "remindersIgnored"
        const val KEY_REMIND_LAST = "lastReminderMs"
        const val GRAMMAR_NEW = "grammarNew:"
        private const val COUNTER_SCOPE = "counterScope:"
        private const val LISTENING_SCOPE = "listeningScope:"
        const val GRAMMAR_BROWSE = "grammarBrowse:"
        const val GRAMMAR_GUIDED = "grammarGuided:"
    }
}
