package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.words.WordKanjiHelp

@Composable
internal fun wordKanjiText(text: String, help: WordKanjiHelp?, onRead: (() -> Unit)? = null): AnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        with(RareHan) { appendWithRare(text) }
        help?.ranges?.forEach { range ->
            val style = SpanStyle(
                color = color, textDecoration = TextDecoration.Underline,
                fontWeight = if (range.level == null) FontWeight.Normal else FontWeight.Bold,
            )
            if (onRead == null) addStyle(style, range.start, range.end)
            else addLink(
                LinkAnnotation.Clickable("word-reading:${range.start}", TextLinkStyles(style)) { onRead() },
                range.start, range.end,
            )
        }
    }
}

@Composable
internal fun wordKanjiDescription(help: WordKanjiHelp): String {
    val beyond = help.ranges.filter { it.level != null }.map { help.word.substring(it.start, it.end) }.distinct()
    val unknown = help.ranges.filter { it.level == null }.map { help.word.substring(it.start, it.end) }.distinct()
    return listOfNotNull(
        beyond.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.word_kanji_beyond, it.joinToString(", ")) },
        unknown.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.word_kanji_unclassified, it.joinToString(", ")) },
    ).joinToString(" ")
}

@Composable
internal fun WordOptionLabel(label: String, help: WordKanjiHelp?) {
    val description = help?.let { "$label. ${wordKanjiDescription(it)}" }
    Text(wordKanjiText(label, help), modifier = Modifier.semantics {
        if (description != null) contentDescription = description
    })
}

@Composable
internal fun WordReadingHelp(help: WordKanjiHelp, onRead: () -> Unit) {
    Text(stringResource(R.string.word_kanji_prompt_legend), style = MaterialTheme.typography.bodySmall)
    val label = stringResource(R.string.word_reading_show)
    val description = "$label. ${wordKanjiDescription(help)}"
    OutlinedButton(onClick = onRead, modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Text(label)
    }
}

@Composable
internal fun WordReadingTipDialog(tip: WordReadingTip, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(wordKanjiText(stringResource(R.string.word_reading_title, tip.help.word), null)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(tip.help.reading, style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.word_reading_whole_word))
                Text(wordKanjiText(wordKanjiDescription(tip.help), null), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.word_reading_close)) } },
    )
}
