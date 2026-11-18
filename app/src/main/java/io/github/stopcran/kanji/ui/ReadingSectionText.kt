package io.github.stopcran.kanji.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.Constraints
import io.github.stopcran.kanji.core.readingtext.ReadingOccurrence
import io.github.stopcran.kanji.core.readingtext.ReadingSection

@Composable
internal fun ReadingSectionText(section: ReadingSection, furigana: Boolean, onLookup: (ReadingOccurrence) -> Unit) {
    val style = MaterialTheme.typography.titleLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fontPx = with(density) { style.fontSize.toPx() }
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = with(density) { maxWidth.toPx() }
        val inline = mutableMapOf<String, InlineTextContent>()
        val annotated = buildAnnotatedString {
            var previous = 0
            section.vocabulary.forEach { occurrence ->
                val start = section.text.offsetByCodePoints(0, occurrence.start)
                val end = section.text.offsetByCodePoints(0, occurrence.end)
                append(section.text.substring(previous, start))
                val ruby = furigana && occurrence.text.codePoints().anyMatch(Character::isIdeographic)
                if (ruby) {
                    val baseStyle = style.copy(color = MaterialTheme.colorScheme.primary, lineHeight = 1.25.em)
                    val readingStyle = style.copy(fontSize = style.fontSize * 0.5f, lineHeight = 1.3.em)
                    val availableConstraints = Constraints(maxWidth = available.toInt().coerceAtLeast(1))
                    val base = measurer.measure(occurrence.text, baseStyle, constraints = availableConstraints)
                    val reading = measurer.measure(occurrence.reading, readingStyle, constraints = availableConstraints)
                    val width = if (base.lineCount > 1 || reading.lineCount > 1) available.toInt().coerceAtLeast(1)
                        else (maxOf(base.size.width, reading.size.width) + 1).coerceAtMost(availableConstraints.maxWidth)
                    val constraints = Constraints(maxWidth = width)
                    val height = measurer.measure(occurrence.text, baseStyle, constraints = constraints).size.height +
                        measurer.measure(occurrence.reading, readingStyle, constraints = constraints).size.height
                    val key = "reading:${occurrence.start}"
                    // Placeholder em units track the actual paint size, including nonlinear system font scaling.
                    inline[key] = InlineTextContent(Placeholder((width / fontPx).em, (height / fontPx).em,
                        PlaceholderVerticalAlign.AboveBaseline)) {
                        Column(
                            Modifier.testTag("reading-word:${section.id}:${occurrence.start}").clickable { onLookup(occurrence) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(occurrence.reading, style = readingStyle)
                            Text(occurrence.text, style = baseStyle)
                        }
                    }
                    appendInlineContent(key, occurrence.text)
                } else {
                    val linkStart = length
                    append(occurrence.text)
                    addLink(LinkAnnotation.Clickable("word:${occurrence.start}", linkStyle) { onLookup(occurrence) }, linkStart, length)
                }
                previous = end
            }
            append(section.text.substring(previous))
        }
        Text(annotated, modifier = Modifier.testTag("reading-section:${section.id}"),
            style = style.copy(lineHeight = TextUnit.Unspecified), inlineContent = inline)
    }
}
