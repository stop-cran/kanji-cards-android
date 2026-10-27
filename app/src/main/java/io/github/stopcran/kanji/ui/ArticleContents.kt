package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.markdown.Block

@Composable
internal fun ArticleContents(headings: List<IndexedValue<Block.Heading>>, onSelect: (Int) -> Unit) {
    var expanded by rememberSaveable(headings) { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.article_contents)) }
    if (expanded) AlertDialog(
        onDismissRequest = { expanded = false },
        title = { Text(stringResource(R.string.article_contents)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                itemsIndexed(headings, key = { _, entry -> entry.index }) { ordinal, entry ->
                    TextButton(
                        onClick = { expanded = false; onSelect(entry.index) },
                        modifier = Modifier.fillMaxWidth().padding(start = ((entry.value.level - 1) * 8).dp),
                    ) {
                        Text(
                            stringResource(R.string.article_contents_entry, ordinal + 1, entry.value.spans.joinToString("") { it.text }),
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.article_contents_close)) }
        },
    )
}
