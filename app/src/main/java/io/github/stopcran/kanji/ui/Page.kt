package io.github.stopcran.kanji.ui

import androidx.compose.ui.semantics.heading
import io.github.stopcran.kanji.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth

/** Scrollable page that respects system bars (the app draws edge-to-edge), with an optional top bar. */
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    scrollable: Boolean = true,
    maxWidth: Dp = 720.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) { Text(stringResource(R.string.back)) }
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = if (onBack == null) 8.dp else 0.dp).semantics { heading() })
            actions()
        }
        Column(Modifier.widthIn(max = maxWidth).fillMaxWidth().weight(1f).let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
