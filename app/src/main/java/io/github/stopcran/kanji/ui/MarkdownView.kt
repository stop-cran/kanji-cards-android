package io.github.stopcran.kanji.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.unit.Dp
import io.github.stopcran.kanji.R
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
fun LazyMarkdownView(text: String, path: String, onOpenDoc: (String) -> Unit, modifier: Modifier = Modifier, header: (@Composable () -> Unit)? = null) {
    val blocks = remember(text, path) { Markdown.parse(text, path) }
    val onLink = rememberLinkHandler(onOpenDoc)
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    SelectionContainer(modifier) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (header != null) item { header() }
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
    if (columns == 0) return
    val density = LocalDensity.current
    val fontSize = LocalTextStyle.current.fontSize
    val limit = with(density) { MAX_TABLE_WIDTH_PX.toDp() }
    val minimum = with(density) { (fontSize.toDp() * TABLE_COLUMN_EMS).coerceIn(48.dp, limit) }
    val pageSize = (limit / (minimum + 8.dp)).toInt().coerceIn(1, TABLE_WINDOW_COLUMNS)
    var first by rememberSaveable(t) { mutableIntStateOf(0) }
    val start = if (columns <= pageSize) 0 else first.coerceIn(0, columns - 1)
    val end = (start + pageSize).coerceAtMost(columns)
    val shown = end - start
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val viewportWidth = maxWidth
        val gap = 8.dp * (shown - 1)
        val width = ((viewportWidth.coerceAtMost(limit) - gap) / shown).coerceAtLeast(minimum)
        val widths = List(shown) { width }
        val total = width * shown + gap
        Column {
            if (columns > pageSize) TableColumns(start, end, columns,
                { first = (start - pageSize).coerceAtLeast(0) }, { first = end })
            if (total > viewportWidth) Text(stringResource(R.string.article_table_scroll), style = MaterialTheme.typography.bodySmall)
            key(start) {
                Column(Modifier.horizontalScroll(rememberScrollState()).width(total)) {
                    TableRow(t.header.subList(start, end), true, widths, onLink, linkColor, codeBg)
                    t.rows.forEach { row ->
                        TableRow((start until end).map { row.getOrNull(it).orEmpty() }, false, widths, onLink, linkColor, codeBg)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableColumns(first: Int, end: Int, count: Int, previous: () -> Unit, next: () -> Unit) {
    Text(stringResource(R.string.article_table_columns, first + 1, end, count), style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth()) {
        TextButton(onClick = previous, enabled = first > 0, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.article_table_previous))
        }
        TextButton(onClick = next, enabled = end < count, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.article_table_next))
        }
    }
}

@Composable
private fun TableRow(
    cells: List<List<Span>>,
    header: Boolean,
    widths: List<Dp>,
    onLink: (LinkTarget) -> Unit,
    linkColor: androidx.compose.ui.graphics.Color,
    codeBg: androidx.compose.ui.graphics.Color,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in widths.indices) {
            val spans = cells.getOrNull(c).orEmpty().let { if (header) it.map { s -> s.copy(bold = true) } else it }
            Text(annotated(spans, onLink, linkColor, codeBg), modifier = Modifier.width(widths[c]))
        }
    }
    HorizontalDivider()
}

private const val TABLE_COLUMN_EMS = 8
private const val TABLE_WINDOW_COLUMNS = 8
// Bound the combined width, not just individual cells, for untrusted many-column tables.
private const val MAX_TABLE_WIDTH_PX = 8_000

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
