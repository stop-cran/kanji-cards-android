package io.github.stopcran.kanji.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.core.markdown.Block
import io.github.stopcran.kanji.core.markdown.LinkTarget
import io.github.stopcran.kanji.core.markdown.Markdown
import io.github.stopcran.kanji.core.markdown.Span

/** Renders content Markdown. In-repo links call [onOpenDoc] with the repo-relative path; https links open the browser. */
@Composable
fun MarkdownView(text: String, path: String, onOpenDoc: (String) -> Unit, modifier: Modifier = Modifier) {
    val blocks = remember(text, path) { Markdown.parse(text, path) }
    val onLink = rememberLinkHandler(onOpenDoc)
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant

    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { RenderBlock(it, onLink, linkColor, codeBg) }
        }
    }
}

/** Same rendering as [MarkdownView] but lazy: only visible blocks are composed. For a full-page article; do not nest in a scrolling parent. */
@Composable
fun LazyMarkdownView(text: String, path: String, onOpenDoc: (String) -> Unit, modifier: Modifier = Modifier) {
    val blocks = remember(text, path) { Markdown.parse(text, path) }
    val onLink = rememberLinkHandler(onOpenDoc)
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    SelectionContainer(modifier) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(blocks.size) { RenderBlock(blocks[it], onLink, linkColor, codeBg) }
        }
    }
}

@Composable
private fun rememberLinkHandler(onOpenDoc: (String) -> Unit): (LinkTarget) -> Unit {
    val context = LocalContext.current
    return { t ->
        when (t) {
            is LinkTarget.Doc -> onOpenDoc(t.path)
            is LinkTarget.Web -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.url))) }
        }
    }
}

@Composable
private fun RenderBlock(b: Block, onLink: (LinkTarget) -> Unit, linkColor: androidx.compose.ui.graphics.Color, codeBg: androidx.compose.ui.graphics.Color) {
    val typo = MaterialTheme.typography
    when (b) {
        is Block.Heading -> Text(
            annotated(b.spans, onLink, linkColor, codeBg),
            style = when (b.level) { 1 -> typo.headlineSmall; 2 -> typo.titleLarge; else -> typo.titleMedium },
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = if (b.level == 1) 0.dp else 8.dp).semantics { heading() },
        )
        is Block.Paragraph -> Text(annotated(b.spans, onLink, linkColor, codeBg))
        is Block.ListItem -> Row(Modifier.padding(start = (12 * b.level).dp)) {
            Text(if (b.ordinal != null) "${b.ordinal}. " else "• ")
            Text(annotated(b.spans, onLink, linkColor, codeBg))
        }
        is Block.Quote -> Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(3.dp).background(linkColor)) { Text(" ") }
            Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                b.blocks.forEach { RenderBlock(it, onLink, linkColor, codeBg) }
            }
        }
        is Block.Code -> Text(b.text, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().background(codeBg).horizontalScroll(rememberScrollState()).padding(8.dp))
        Block.Rule -> HorizontalDivider()
        is Block.Table -> RenderTable(b, onLink, linkColor, codeBg)
    }
}

@Composable
private fun RenderTable(t: Block.Table, onLink: (LinkTarget) -> Unit, linkColor: androidx.compose.ui.graphics.Color, codeBg: androidx.compose.ui.graphics.Color) {
    val columns = t.header.size
    val weights = (0 until columns).map { c ->
        val longest = (listOf(t.header) + t.rows).maxOf { row -> row.getOrNull(c)?.sumOf { it.text.length } ?: 0 }
        longest.coerceIn(4, 40).toFloat()
    }
    Column(Modifier.fillMaxWidth()) {
        TableRow(t.header, true, weights, onLink, linkColor, codeBg)
        t.rows.forEach { TableRow(it, false, weights, onLink, linkColor, codeBg) }
    }
}

@Composable
private fun TableRow(
    cells: List<List<Span>>,
    header: Boolean,
    weights: List<Float>,
    onLink: (LinkTarget) -> Unit,
    linkColor: androidx.compose.ui.graphics.Color,
    codeBg: androidx.compose.ui.graphics.Color,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in weights.indices) {
            val spans = cells.getOrNull(c).orEmpty().let { if (header) it.map { s -> s.copy(bold = true) } else it }
            Text(annotated(spans, onLink, linkColor, codeBg), modifier = Modifier.weight(weights[c]))
        }
    }
    HorizontalDivider()
}

private fun annotated(spans: List<Span>, onLink: (LinkTarget) -> Unit, linkColor: androidx.compose.ui.graphics.Color, codeBg: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        for (s in spans) {
            val style = SpanStyle(
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                fontFamily = if (s.code) FontFamily.Monospace else null,
                background = if (s.code) codeBg else androidx.compose.ui.graphics.Color.Unspecified,
            )
            val link = s.link
            if (link == null) {
                withStyle(style) { with(RareHan) { appendWithRare(s.text) } }
            } else {
                val linkStyles = TextLinkStyles(style.merge(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))
                withLink(LinkAnnotation.Clickable("link", linkStyles) { onLink(link) }) { with(RareHan) { appendWithRare(s.text) } }
            }
        }
    }
