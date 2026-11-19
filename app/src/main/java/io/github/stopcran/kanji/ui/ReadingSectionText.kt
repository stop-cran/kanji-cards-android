package io.github.stopcran.kanji.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.Constraints
import io.github.stopcran.kanji.core.readingtext.ReadingOccurrence
import io.github.stopcran.kanji.core.readingtext.ReadingSection
import io.github.stopcran.kanji.core.readingtext.ReadingFurigana
import io.github.stopcran.kanji.core.readingtext.ReadingRubyPart
import kotlin.math.roundToInt

@Composable
internal fun ReadingSectionText(section: ReadingSection, furigana: Boolean, onLookup: (ReadingOccurrence) -> Unit) {
    val style = MaterialTheme.typography.titleLarge.copy(color = LocalContentColor.current, lineHeight = TextUnit.Unspecified)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var layout by remember(section) { mutableStateOf<TextLayoutResult?>(null) }
    val lookup by rememberUpdatedState(onLookup)
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
                val parts = if (furigana) ReadingFurigana.parts(occurrence.text, occurrence.reading)
                    else listOf(ReadingRubyPart(occurrence.text))
                val firstRuby = parts.indexOfFirst { it.reading != null }
                parts.forEachIndexed { index, part ->
                    val reading = part.reading
                    if (reading != null) {
                        val key = "reading:${occurrence.start}:$index"
                        val tag = "reading-word:${section.id}:${occurrence.start}" + if (index == firstRuby) "" else ":$index"
                        val ruby = measurer.readingRuby(part,
                            style.copy(color = MaterialTheme.colorScheme.primary, lineHeight = TextUnit.Unspecified),
                            available.toInt().coerceAtLeast(1), tag) { onLookup(occurrence) }
                        inline[key] = ruby.first
                        pushStyle(SpanStyle(baselineShift = ruby.second))
                        appendInlineContent(key, part.text)
                        pop()
                    } else {
                        val linkStart = length
                        append(part.text)
                        addLink(LinkAnnotation.Clickable("word:${occurrence.start}", linkStyle) { onLookup(occurrence) }, linkStart, length)
                    }
                }
                previous = end
            }

            append(section.text.substring(previous))
        }
        // Wrapped link hit rectangles can cover adjacent inline words; resolve actual text geometry first.
        BasicText(annotated, modifier = Modifier.testTag("reading-section:${section.id}").pointerInput(section) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val occurrence = layout?.let { section.lookupAt(down.position, it) }
                down.consume()
                val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                if (up != null) {
                    up.consume()
                    occurrence?.let(lookup)
                }
            }
        }, style = style, inlineContent = inline, onTextLayout = { layout = it })
    }
}

private fun ReadingSection.lookupAt(position: Offset, layout: TextLayoutResult): ReadingOccurrence? {
    val placeholder = layout.placeholderRects.withIndex().firstOrNull { (index, rect) ->
        rect != null && position.x >= rect.left && position.x <= rect.right &&
            position.y >= rect.top && position.y <= layout.getLineBottom(layout.getLineForOffset(layout.layoutInput.placeholders[index].start))
    }
    val offset = if (placeholder != null) layout.layoutInput.placeholders[placeholder.index].start else {
        val nearest = layout.getOffsetForPosition(position).coerceAtMost(text.lastIndex)
        when {
            nearest < 0 -> return null
            layout.getBoundingBox(nearest).contains(position) -> nearest
            nearest > 0 && layout.getBoundingBox(nearest - 1).contains(position) -> nearest - 1
            else -> return null
        }
    }
    val point = text.codePointCount(0, offset)
    return vocabulary.find { point in it.start until it.end }
}

private fun TextMeasurer.readingRuby(
    part: ReadingRubyPart, baseStyle: TextStyle,
    available: Int, tag: String, onLookup: () -> Unit,
): Pair<InlineTextContent, BaselineShift> {
    val readingStyle = baseStyle.copy(fontSize = baseStyle.fontSize * 0.5f, lineHeight = 1.3.em,
        color = androidx.compose.ui.graphics.Color.Unspecified)
    val availableConstraints = Constraints(maxWidth = available)
    val base = measure(part.text, baseStyle, constraints = availableConstraints)
    val reading = requireNotNull(part.reading)
    val ruby = measure(reading, readingStyle, constraints = availableConstraints)
    val width = if (base.lineCount > 1 || ruby.lineCount > 1) available
        else (maxOf(base.size.width, ruby.size.width) + 1).coerceAtMost(available)
    val constraints = Constraints(maxWidth = width)
    val measuredBase = measure(part.text, baseStyle, constraints = constraints)
    val descent = measuredBase.size.height - measuredBase.lastBaseline
    val height = measuredBase.lastBaseline.roundToInt() + measure(reading, readingStyle, constraints = constraints).size.height
    val shift = BaselineShift(-descent / measure(" ", baseStyle).firstBaseline)
    val fontPx = with(base.layoutInput.density) { baseStyle.fontSize.toPx() }
    // Reserve above the real base baseline and reserve its descent via the surrounding metric span.
    // CJK fallback descent can exceed the paragraph font's descent; TextBottom alone does not align it.
    val content = InlineTextContent(Placeholder((width / fontPx).em, (height / fontPx).em, PlaceholderVerticalAlign.AboveBaseline)) {
        Column(Modifier.layout { measurable, childConstraints ->
            val placeable = measurable.measure(childConstraints.copy(maxHeight = Constraints.Infinity))
            layout(placeable.width, placeable[LastBaseline]) { placeable.place(0, 0) }
        }.testTag(tag).clickable(onClick = onLookup), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(reading, style = readingStyle.copy(color = LocalContentColor.current))
            BasicText(part.text, style = baseStyle)
        }
    }
    return content to shift
}
