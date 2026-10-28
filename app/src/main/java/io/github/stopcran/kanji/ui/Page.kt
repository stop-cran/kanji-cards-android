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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth

/** Scrollable page respecting system bars, with optional fixed footer actions; [ime] lifts it above the keyboard. */
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    scrollable: Boolean = true,
    maxWidth: Dp = 720.dp,
    ime: Boolean = false,
    scrollToTopOnBack: Boolean = false,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Session pages: Back first returns to the top, so retracing article links does not exit the session by a stray extra tap.
    BackHandler(enabled = scrollToTopOnBack && scrollable && scroll.value > 0) { scope.launch { scroll.animateScrollTo(0) } }
    // Only pages with text fields track the keyboard; elsewhere a stale IME inset would shrink the page.
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().let { if (ime) it.imePadding() else it }, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) { Text(stringResource(R.string.back)) }
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = if (onBack == null) 8.dp else 0.dp).semantics { heading() })
            actions()
        }
        val body = Modifier.widthIn(max = maxWidth).fillMaxWidth().weight(1f)
        Column(
            body.let { if (scrollable) it.verticalScroll(scroll) else it }.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
        footer?.let {
            Column(
                Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = it,
            )
        }
    }
}
