package io.github.stopcran.kanji.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import io.github.stopcran.kanji.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.Defaults
import io.github.stopcran.kanji.core.srs.NewAllowance
import io.github.stopcran.kanji.core.srs.PacingNote
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.kana.KanaMode
import io.github.stopcran.kanji.core.kana.KanaScript
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.core.unlock.UnlockRule
import io.github.stopcran.kanji.data.inStack

/** Where the user can go from the home pages; sessions are started by the caller. */
@Suppress("LongParameterList")
class HomeActions(
    val onSettings: () -> Unit,
    val onCards: () -> Unit,
    val onWordCards: () -> Unit,
    val onKanjiPage: () -> Unit,
    val onWordsPage: () -> Unit,
    val onCountersPage: () -> Unit,
    val onGrammarPage: () -> Unit,
    val onListeningPage: () -> Unit,
    val onOpenDoc: (String) -> Unit,
    val onGrammarDoc: (String, String, String?) -> Unit,
    val onKanaPage: () -> Unit,
    val onKanaStudy: (KanaScript, KanaMode, extra: Boolean) -> Unit,
    val onCounters: (
        io.github.stopcran.kanji.core.counters.CounterMode, extra: Boolean,
        io.github.stopcran.kanji.core.counters.CounterGroup, io.github.stopcran.kanji.core.counters.CounterScope,
    ) -> Unit,
    val onGrammar: (extra: Boolean) -> Unit,
    val onGrammarTarget: (io.github.stopcran.kanji.core.grammar.GrammarPracticeTarget) -> Unit,
    val onQuiz: (extra: Boolean) -> Unit,
    val onDraw: (extra: Boolean) -> Unit,
    val onReadings: (extra: Boolean) -> Unit,
    val onWords: (WordDirection, extra: Boolean) -> Unit,
)

@Composable
internal fun MainPage(state: HomeState, overview: HomeOverview, preferences: HomePreferencesActions, a: HomeActions, onRetry: () -> Unit) {
    val data = state.data
    val content = state.content
    val context = LocalContext.current
    Page(
        stringResource(R.string.app_name), scrollable = !data.loading && content.ready != null,
        actions = {
            IconButton(onClick = a.onSettings) {
                Image(painterResource(R.drawable.settings_brush), stringResource(R.string.settings_title), Modifier.size(27.dp))
            }
        },
    ) {
        HomeContentNotice(content, onRetry)
        if (content.usable) LoadingBar(data)
        val defaultId = remember { RepoSource.parse(Defaults.CONTENT_REPO_URL, Defaults.CONTENT_BRANCH)?.id }
        if (data.sourceId != defaultId) Text(stringResource(R.string.cards_from, data.sourceId))
        N4Offer(preferences, data)
        PacingHint(data)
        StartingGuide(overview.startingGuideVisible, preferences.showStartingGuide, a.onKanaPage, a.onWordsPage, a.onGrammarPage)
        DailyCountsHelp(overview.kanaAllowance, data.pacing)
        Entry(stringResource(R.string.home_kana), overview.kana, R.drawable.kana_sumi, a.onKanaPage)
        val kanjiSummary = data.summary(HomeMode.Meaning, HomeMode.Drawing, HomeMode.Readings)
            ?: context.getString(R.string.loading)
        Entry(stringResource(R.string.home_kanji), content.summary(kanjiSummary), ArtSet.current.kanji, a.onKanjiPage)
        val words = data.summary(
            HomeMode.WordJp, HomeMode.WordEn, HomeMode.WordReading, HomeMode.WordType, HomeMode.WordPicture, HomeMode.WordToPicture,
        ) ?: context.getString(R.string.loading)
        Entry(stringResource(R.string.home_words), content.summary(words), ArtSet.current.words, a.onWordsPage)
        Entry(stringResource(R.string.home_counters), content.summary(overview.counters), ArtSet.current.counters, a.onCountersPage)
        Entry(stringResource(R.string.home_grammar), content.summary(overview.grammar), ArtSet.current.grammar, a.onGrammarPage)
        Entry(stringResource(R.string.home_listening), content.summary(overview.listening), ArtSet.current.listening, a.onListeningPage)
    }
}

@Composable
internal fun KanjiPage(data: HomeData, preferences: HomePreferencesActions, onBack: () -> Unit, a: HomeActions) {
    val context = LocalContext.current
    Page(stringResource(R.string.home_kanji), onBack, scrollable = !data.loading) {
        LoadingBar(data)
        Text(context.getString(stackLabel(data.stack.id)) + " · " + stringResource(R.string.cards, data.kanji.inStack(data.stack.id).size))
        if (data.stacks.size > 1) {
            LevelFilter(data.stacks, data.stack, { preferences.selectStack(it.id) }, { context.getString(stackLabel(it.id)) },
                description = if (Stacks.jlptLevel(data.stack.id) != null)
                    stringResource(R.string.level_filter_kanji_cumulative, context.getString(stackLabel(data.stack.id))) else null)
            if (Stacks.jlptLevel(data.stack.id) == null) {
                Text(stringResource(R.string.each_stack_keeps_its_own), style = MaterialTheme.typography.bodySmall)
            }
        }
        KanjiFocusGuidance(data.focus)
        ModeRow(stringResource(R.string.mode_meaning), data.mode(HomeMode.Meaning), onStart = a.onQuiz)
        ModeRow(stringResource(R.string.mode_drawing), data.mode(HomeMode.Drawing), onStart = a.onDraw)
        ModeRow(stringResource(R.string.mode_readings), data.mode(HomeMode.Readings), onStart = a.onReadings)
        OutlinedButton(onClick = a.onCards, enabled = data.kanji.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.browse_cards)) }
    }
}

@Composable
internal fun WordsPage(
    preferences: HomePreferencesActions,
    data: HomeData,
    onBack: () -> Unit,
    onBrowse: () -> Unit,
    onStart: (WordDirection, Boolean) -> Unit,
) {
    val context = LocalContext.current
    Page(stringResource(R.string.home_words), onBack, scrollable = !data.loading) {
        LoadingBar(data)
        Text(context.getString(wordStackLabel(data.wordStack.id)) + " · " + stringResource(R.string.words, data.wordCount))
        N4Offer(preferences, data)
        if (data.wordStacks.size > 1) {
            LevelFilter(data.wordStacks, data.wordStack, { preferences.selectWordStack(it.id) }, { context.getString(wordStackLabel(it.id)) },
                description = if (data.wordStack == WordStacks.n4) stringResource(R.string.level_filter_includes_easier) else null)
        }
        WordUnlockHelp()
        data.pacing?.let { Text(stringResource(R.string.counter_shared_allowance, it.remaining)) }
        Text(stringResource(R.string.mode_batch_help))
        WordModeRow(data, stringResource(R.string.mode_japanese_english), HomeMode.WordJp, WordDirection.JpToEn) { onStart(WordDirection.JpToEn, it) }
        WordModeRow(data, stringResource(R.string.mode_english_japanese), HomeMode.WordEn, WordDirection.EnToJp) { onStart(WordDirection.EnToJp, it) }
        WordModeRow(data, stringResource(R.string.mode_reading), HomeMode.WordReading, WordDirection.Reading) { onStart(WordDirection.Reading, it) }
        WordModeRow(data, stringResource(R.string.mode_type_it), HomeMode.WordType, WordDirection.Type) { onStart(WordDirection.Type, it) }
        if (data.pictureWordCount > 0) {
            WordModeRow(
                data, stringResource(R.string.mode_picture_word), HomeMode.WordPicture, WordDirection.Picture,
            ) { onStart(WordDirection.Picture, it) }
            WordModeRow(
                data, stringResource(R.string.mode_word_picture), HomeMode.WordToPicture, WordDirection.WordToPicture,
            ) { onStart(WordDirection.WordToPicture, it) }
        }
        OutlinedButton(onClick = onBrowse, enabled = data.wordCount > 0, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(stringResource(R.string.browse_words))
        }
    }
}

@Composable
private fun LoadingBar(data: HomeData) {
    if (!data.loading) return
    LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(stringResource(R.string.loading_your_cards), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Entry(title: String, summary: String, art: Int?, onClick: () -> Unit) {
    ArtCard(title, summary, art, onClick)
}

/** One study mode: a title, what is on offer, and a single button whose meaning depends on the state. */
@Composable
internal fun ModeRow(
    title: String,
    state: ModeState,
    supportingText: String? = null,
    localizedReason: String? = null,
    modeLearning: Boolean = false,
    onStart: (extra: Boolean) -> Unit,
) {
    val summary = when (state) {
        ModeState.Loading -> stringResource(R.string.loading)
        is ModeState.Start -> stringResource(if (modeLearning) R.string.study_due_learning else R.string.study_due_new, state.due, state.new)
        ModeState.PracticeMore -> stringResource(R.string.mode_nothing_due)
        is ModeState.Locked -> localizedReason ?: localizedModeReason(state.reason)
        is ModeState.Unavailable -> localizedReason ?: localizedModeReason(state.reason)
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).semantics(mergeDescendants = true) { stateDescription = summary }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(summary, style = MaterialTheme.typography.bodyMedium)
        supportingText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state == ModeState.PracticeMore) {
            OutlinedButton(onClick = { onStart(true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.practice_more)) }
        } else {
            Button(onClick = { onStart(false) }, enabled = state.enabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.start)) }
        }
    }
}

@Composable
internal fun localizedModeReason(reason: String): String {
    UnlockRule.entries.firstOrNull { it.hint == reason }?.let {
        return stringResource(unlockRuleHintResource(it))
    }
    return when {
        reason == "Needs at least 2 kanji in this stack" -> stringResource(R.string.mode_needs_kanji)
        reason == "Kanji focus full" -> stringResource(R.string.kanji_focus_full)
        reason == "Needs at least 1 kanji with stroke data" -> stringResource(R.string.mode_needs_stroke_kanji)
        reason == "Needs at least 2 words" -> stringResource(R.string.mode_needs_words)
        reason == "Needs at least two words with kanji" -> stringResource(R.string.mode_needs_reading_words)
        reason == "No kana remain in this script." -> stringResource(R.string.kana_no_locked)
        reason == "No counter cards yet. Sync the content first (⚙ settings)." -> stringResource(R.string.counter_no_cards_sync)
        reason == "Nothing is due. Come back later or raise the daily new-card limit in ⚙ settings." ->
            stringResource(R.string.mode_nothing_due_settings)
        reason.startsWith("Opens for sentences you have answered in") ->
            stringResource(R.string.counter_mode_unlock_two_days)
        reason.startsWith("Opens for a counter once three of its sentences are solid") ->
            stringResource(R.string.counter_mode_unlock_solid)
        reason == "Unlocks as you learn words" -> stringResource(R.string.word_unlocks_as_you_learn)
        reason == "Unlocks as you learn English → Japanese" -> stringResource(R.string.word_unlocks_after_english_japanese)
        else -> if (androidx.compose.ui.platform.LocalConfiguration.current.locales.get(0)?.language == "ru") {
            stringResource(R.string.mode_status_unavailable)
        } else reason
    }
}

/** Explains a reduced or empty new-item allowance; reviews are never limited, the backlog case only warns. */
@Composable
private fun PacingHint(data: HomeData) {
    data.week?.let { w ->
        val tail = if (w.studied >= w.goal) stringResource(R.string.study_goal_reached) else ""
        Text(stringResource(R.string.this_week_of_study_days, w.studied, w.goal, tail), style = MaterialTheme.typography.bodySmall)
    }
    val p = data.pacing
    if (p == null) return
    val text = pacingExplanation(p, LocalContext.current) ?: return
    Text(stringResource(R.string.content_pacing_notice, text), style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun KanjiFocusGuidance(focus: io.github.stopcran.kanji.data.FocusStatus?) {
    if (focus?.full != true) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.kanji_focus_full), style = MaterialTheme.typography.bodySmall)
        InfoDisclosureButton(
            actionDescription = stringResource(R.string.kanji_focus_info),
            title = stringResource(R.string.kanji_focus_title),
            message = stringResource(R.string.kanji_focus_explanation, focus.occupancy),
        )
    }
}

internal fun pacingExplanation(p: NewAllowance, context: android.content.Context? = null): String? = when (p.note) {
    PacingNote.None -> null
    PacingNote.RestDay -> context?.getString(R.string.pacing_rest_day)
        ?: "Rest day in your weekly rhythm: no new items today, reviews are still available."
    PacingNote.LightDay -> context?.getString(R.string.pacing_light_day, p.total)
        ?: "Light day: ${p.total} new items today."
    PacingNote.BacklogReduced -> context?.getString(R.string.pacing_backlog_reduced, p.due, p.total)
        ?: "${p.due} reviews are waiting, so new items are reduced to ${p.total} today."
    PacingNote.BacklogPaused -> context?.getString(R.string.pacing_backlog_paused, p.due)
        ?: "${p.due} reviews are waiting, so new items are paused. A short review session now keeps things from piling up."
}

@Composable
private fun N4Offer(preferences: HomePreferencesActions, data: HomeData) {
    if (!data.n4Offer) return
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.you_know_n5_well), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.most_n5_kanji_and_words))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = preferences.acceptN4) { Text(stringResource(R.string.add_n4_words)) }
                TextButton(onClick = preferences.dismissN4) { Text(stringResource(R.string.not_yet)) }
            }
        }
    }
}

private fun stackLabel(id: String): Int = when (id) {
    "jlpt-n5" -> R.string.level_n5
    "jlpt-n4" -> R.string.level_n4
    "jlpt-n3" -> R.string.level_n3
    "jlpt-n2" -> R.string.level_n2
    "jlpt-n1" -> R.string.level_n1
    "starter" -> R.string.level_starter
    "all" -> R.string.level_all
    else -> R.string.level_unclassified
}

private fun wordStackLabel(id: String): Int = when (id) {
    WordStacks.n5.id -> R.string.word_deck_n5
    WordStacks.n4.id -> R.string.word_deck_n4
    WordStacks.all.id -> R.string.word_deck_all
    WordStacks.onomatopoeia.id -> R.string.word_deck_onomatopoeia
    else -> R.string.word_deck_n5
}
