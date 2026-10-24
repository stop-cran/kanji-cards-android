package io.github.stopcran.kanji.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.scrollByOffset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.grammar.GrammarMapLayout
import io.github.stopcran.kanji.core.grammar.GrammarMapProgress
import kotlin.math.roundToInt

@Composable
internal fun GrammarMapCard(
    node: GrammarMapNode,
    modifier: Modifier = Modifier,
    onSelect: (GrammarMapDestination) -> Unit,
    compact: Boolean = false,
    selected: Boolean = false,
) {
    val progress = node.progress
    val color = when {
        progress.ready > 0 -> MaterialTheme.colorScheme.tertiaryContainer
        progress.introduced > 0 -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    OutlinedCard(
        onClick = { onSelect(node.destination) },
        modifier = modifier.testTag("grammar-node:${node.id}").semantics { this.selected = selected },
        colors = CardDefaults.outlinedCardColors(containerColor = color),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(node.label, style = MaterialTheme.typography.titleMedium, maxLines = if (compact) 3 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
            GrammarMapProgressText(progress)
        }
    }
}

@Composable
internal fun GrammarMapProgressText(progress: GrammarMapProgress) {
    Text(
        if (progress.matching == null) stringResource(R.string.grammar_map_unknown_matching)
        else stringResource(R.string.grammar_map_matching, progress.matching, progress.total),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(stringResource(R.string.grammar_map_introduced, progress.introduced, progress.total, progress.due), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.grammar_map_available, progress.available, progress.groups), style = MaterialTheme.typography.bodySmall)
    if (progress.authored > 0) {
        Text(stringResource(R.string.grammar_map_readiness, progress.ready, progress.authored), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.grammar_map_freshness, progress.familiar, progress.authored), style = MaterialTheme.typography.bodySmall)
    } else {
        Text(stringResource(R.string.grammar_map_compatibility), style = MaterialTheme.typography.bodySmall)
    }
    if (progress.needsFoundation > 0) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.roadmap_lock), null, Modifier.size(18.dp))
            Text(
                pluralStringResource(R.plurals.grammar_map_locked_groups, progress.needsFoundation, progress.needsFoundation),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (progress.available > 0 && (progress.available < progress.groups || progress.needsFoundation > 0)) {
        Text(stringResource(R.string.grammar_map_partial), style = MaterialTheme.typography.bodySmall)
    }
}

/** Only viewport-sized content is measured, even when a fork supplies a very deep or wide DAG. */
@Composable
internal fun GrammarRoadmapDiagram(
    layout: GrammarMapLayout,
    nodes: Map<String, GrammarMapNode>,
    selectedRoute: String? = null,
    onSelect: (GrammarMapDestination) -> Unit,
) {
    val density = LocalDensity.current
    val cardWidth = 280.dp
    val cardHeight = 360.dp * density.fontScale.coerceAtLeast(1f)
    val stepX = with(density) { (cardWidth + 64.dp).toPx() }
    val stepY = with(density) { (cardHeight + 32.dp).toPx() }
    var x by rememberSaveable { mutableFloatStateOf(0f) }
    var y by rememberSaveable { mutableFloatStateOf(0f) }
    val viewportHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.55f).coerceIn(180.dp, 440.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().height(viewportHeight).clipToBounds()) {
        val visibleColumns = constraints.maxWidth / stepX
        val visibleRows = constraints.maxHeight / stepY
        val maxX = (layout.columns - 1 + with(density) { cardWidth.toPx() } / stepX - visibleColumns).coerceAtLeast(0f)
        val maxY = (layout.rows - 1 + with(density) { cardHeight.toPx() } / stepY - visibleRows).coerceAtLeast(0f)
        LaunchedEffect(maxX, maxY) {
            x = x.coerceIn(0f, maxX)
            y = y.coerceIn(0f, maxY)
        }
        val left = x.coerceIn(0f, maxX)
        val top = y.coerceIn(0f, maxY)
        val horizontal = rememberScrollableState { delta ->
            val next = (x.coerceIn(0f, maxX) - delta / stepX).coerceIn(0f, maxX)
            val consumed = (x.coerceIn(0f, maxX) - next) * stepX
            x = next
            consumed
        }
        val vertical = rememberScrollableState { delta ->
            val next = (y.coerceIn(0f, maxY) - delta / stepY).coerceIn(0f, maxY)
            val consumed = (y.coerceIn(0f, maxY) - next) * stepY
            y = next
            consumed
        }
        val visible by remember(layout, visibleColumns, visibleRows, maxX, maxY) {
            derivedStateOf {
                layout.positions.filter {
                    it.column + 1 > x.coerceIn(0f, maxX) && it.column < x.coerceIn(0f, maxX) + visibleColumns &&
                        it.row + 1 > y.coerceIn(0f, maxY) && it.row < y.coerceIn(0f, maxY) + visibleRows
                }
            }
        }
        fun pan(offset: Offset): Offset {
            val oldX = x.coerceIn(0f, maxX)
            val oldY = y.coerceIn(0f, maxY)
            x = (oldX + offset.x / stepX).coerceIn(0f, maxX)
            y = (oldY + offset.y / stepY).coerceIn(0f, maxY)
            return Offset((x - oldX) * stepX, (y - oldY) * stepY)
        }
        androidx.compose.foundation.layout.Box(
            Modifier.matchParentSize().testTag("grammar-diagram").semantics {
                horizontalScrollAxisRange = ScrollAxisRange({ x.coerceIn(0f, maxX) * stepX }, { maxX * stepX })
                verticalScrollAxisRange = ScrollAxisRange({ y.coerceIn(0f, maxY) * stepY }, { maxY * stepY })
                scrollBy { dx, dy -> pan(Offset(dx, dy)) != Offset.Zero }
                scrollByOffset { pan(it) }
            }.scrollable(horizontal, Orientation.Horizontal).scrollable(vertical, Orientation.Vertical),
        ) {
            GrammarMapArrows(layout, Offset(left * stepX, top * stepY), Offset(stepX, stepY))
            visible.forEach { position ->
                key(position.id) {
                    GrammarMapCard(
                        nodes.getValue(position.id),
                        Modifier.offset {
                            IntOffset(((position.column - left) * stepX).roundToInt(), ((position.row - top) * stepY).roundToInt())
                        }.wrapContentSize(Alignment.TopStart, unbounded = true).size(cardWidth, cardHeight),
                        onSelect, compact = true,
                        selected = selectedRoute == GrammarRoadmapRoutes.create(nodes.getValue(position.id).destination),
                    )
                }
            }
        }
    }
}

@Composable
private fun GrammarMapArrows(layout: GrammarMapLayout, scroll: Offset, step: Offset) {
    val positions = remember(layout) { layout.positions.associateBy { it.id } }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxSize()) {
        val cardWidth = 280.dp.toPx()
        layout.edges.forEach { edge ->
            val from = positions.getValue(edge.foundation)
            val to = positions.getValue(edge.dependent)
            val start = Offset(from.column * step.x + cardWidth, from.row * step.y + (step.y - 32.dp.toPx()) / 2) - scroll
            val end = Offset(to.column * step.x, to.row * step.y + (step.y - 32.dp.toPx()) / 2) - scroll
            val horizontallyVisible = end.x >= 0 && start.x <= size.width
            val verticallyVisible = maxOf(start.y, end.y) >= 0 && minOf(start.y, end.y) <= size.height
            if (horizontallyVisible && verticallyVisible) {
                val bend = (start.x + end.x) / 2
                val path = Path().apply {
                    moveTo(start.x, start.y)
                    cubicTo(bend, start.y, bend, end.y, end.x, end.y)
                }
                drawPath(path, color, style = Stroke(2.dp.toPx()))
                drawLine(color, end - Offset(8.dp.toPx(), 5.dp.toPx()), end, 2.dp.toPx())
                drawLine(color, end - Offset(8.dp.toPx(), -5.dp.toPx()), end, 2.dp.toPx())
            }
        }
    }
}
