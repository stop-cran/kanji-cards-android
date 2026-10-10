package io.github.stopcran.kanji.ui

import androidx.compose.material3.Text
import io.github.stopcran.kanji.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.github.stopcran.kanji.KanjiApp

/** A content document addressed by repo-relative path (`kanji/X.md`, `words/Y.md`, `articles/z.md`). Null when missing. */
suspend fun loadDoc(app: KanjiApp, sourceId: String, path: String): Pair<String, String>? {
    val name = path.substringAfter('/').removeSuffix(".md")
    val dao = app.db.content()
    return when (path.substringBefore('/')) {
        "kanji" -> dao.kanjiCard(sourceId, name)?.let { "${it.kanji}  ${it.title}" to it.body }
        "words" -> dao.word(sourceId, name)?.let { "${it.word}  ${it.title}" to it.body }
        "articles" -> dao.article(sourceId, name)?.let { it.title to it.body }
        else -> null
    }
}

/** The article body for [path]; lazy, so it must not sit inside a scrolling parent. */
@Composable
fun DocBody(app: KanjiApp, path: String, onOpenDoc: (String) -> Unit, modifier: Modifier = Modifier) {
    val source by app.settings.source.collectAsState()
    val doc by produceState<Pair<String, String>?>(null, source, path) { value = loadDoc(app, source.id, path) }
    val d = doc
    if (d == null) Text(stringResource(R.string.not_found_in_the_current), modifier) else LazyMarkdownView(d.second, path, onOpenDoc, modifier)
}

@Composable
fun DocScreen(app: KanjiApp, path: String, onBack: () -> Unit, onOpenDoc: (String) -> Unit) {
    val source by app.settings.source.collectAsState()
    val doc by produceState<Pair<String, String>?>(null, source, path) { value = loadDoc(app, source.id, path) }
    Page(doc?.first ?: path.substringAfter('/').removeSuffix(".md"), onBack, scrollable = false) {
        DocBody(app, path, onOpenDoc, Modifier.weight(1f))
    }
}
