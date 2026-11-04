package io.github.stopcran.kanji.ui

import androidx.compose.ui.semantics.liveRegion
import io.github.stopcran.kanji.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import io.github.stopcran.kanji.KanjiApp
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.stopcran.kanji.core.draw.DrawOutcome
import io.github.stopcran.kanji.core.draw.IssueType
import io.github.stopcran.kanji.core.draw.Pt
import io.github.stopcran.kanji.core.draw.ShapeFault
import io.github.stopcran.kanji.core.draw.regularize
import io.github.stopcran.kanji.core.draw.varyReference
import io.github.stopcran.kanji.core.draw.Stroke
import io.github.stopcran.kanji.core.draw.StrokeIssue

private fun unlockRuleHint(value: io.github.stopcran.kanji.core.unlock.UnlockRule): Int = when (value) {
    io.github.stopcran.kanji.core.unlock.UnlockRule.NextDay -> R.string.unlock_next_day_hint
    io.github.stopcran.kanji.core.unlock.UnlockRule.SpacedSameDay -> R.string.unlock_spaced_same_day_hint
}

private val Good = Color(0xFF2E7D32)
private val Bad = Color(0xFFC62828)

@Composable
fun DrawScreen(
    destination: AppDestination.Draw,
    onBack: () -> Unit,
    onOpenDoc: (String) -> Unit,
    onPracticeMore: () -> Unit,
    vm: DrawViewModel = serviceViewModel(destination.route) { DrawViewModel(it) },
) {
    LaunchedEffect(destination) { vm.ensureStarted(destination.extra) }
    val app = LocalContext.current.applicationContext as KanjiApp
    val brush by app.settings.brush.collectAsState()
    val unlockRule by app.settings.unlockRule.collectAsState()
    androidx.compose.runtime.CompositionLocalProvider(LocalBrush provides brush, LocalBrushLook provides vm.look) {
    Page(stringResource(R.string.drawing_title), onBack, scrollToTopOnBack = true) {
        when (val s = vm.ui) {
            DrawUi.Loading -> Text(stringResource(R.string.loading), Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            is DrawUi.Empty -> Text(
                when (s.reason) {
                    DrawEmptyReason.NoStrokeCards -> stringResource(R.string.draw_empty_no_strokes)
                    DrawEmptyReason.NothingDue -> stringResource(R.string.draw_empty_nothing_due)
                    DrawEmptyReason.UnlockBlocked -> stringResource(
                        R.string.draw_no_cards_unlocked,
                        stringResource(unlockRuleHint(unlockRule)),
                    )
                    DrawEmptyReason.ContentLanguageMismatch -> stringResource(R.string.content_language_mismatch_detail)
                    DrawEmptyReason.RussianDefaultGated -> stringResource(R.string.content_russian_default_gated)
                    DrawEmptyReason.CapturedSourceUnavailable -> stringResource(R.string.draw_source_changed)
                },
            )
            is DrawUi.Done -> {
                Text(stringResource(R.string.session_complete), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.of_drawn_cleanly, s.clean, s.answered))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
                OutlinedButton(onClick = onPracticeMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.practice_more)) }
            }
            is DrawUi.Question -> QuestionState(s, vm)
            is DrawUi.Checking -> Text(stringResource(R.string.checking))
            is DrawUi.Answer -> AnswerState(s, vm, onOpenDoc)
        }
    }
    }
}

@Composable
private fun QuestionState(s: DrawUi.Question, vm: DrawViewModel) {
    val strokes = vm.ink
    SessionRemaining(s.remaining)
    Text(
        stringResource(if (s.trace) R.string.draw_trace_for else R.string.draw_the_kanji_for),
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Text(s.card.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    if (s.trace) {
        Text(stringResource(R.string.tracing_practice_notice), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
    DrawingPad(
        strokes,
        onStroke = { strokes += it },
        onSize = { vm.padSized(it) },
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ghost = if (s.trace) vm.reference(s.card.kanji) else null,
    )
    when (vm.modelState) {
        ModelState.Downloading -> Text(stringResource(R.string.downloading_the_handwriting_model_first), style = MaterialTheme.typography.bodySmall)
        ModelState.Unavailable -> Text(stringResource(R.string.handwriting_model_unavailable_graded_by), style = MaterialTheme.typography.bodySmall)
        else -> {}
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) { Text(stringResource(R.string.undo)) }
        OutlinedButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text(stringResource(R.string.clear)) }
        Button(onClick = { vm.check(strokes.toList(), vm.inkSize) }, enabled = strokes.isNotEmpty(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.check)) }
    }
    if (!s.trace) {
        OutlinedButton(onClick = { vm.check(emptyList(), vm.inkSize) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.i_don_t_remember))
        }
    }
}

@Composable
private fun AnswerState(s: DrawUi.Answer, vm: DrawViewModel, onOpenDoc: (String) -> Unit) {
    val showIssues = s.outcome == DrawOutcome.Mistakes
    val (headline, color) = when (s.outcome) {
        DrawOutcome.Clean -> stringResource(R.string.draw_outcome_clean) to correctTextColor()
        DrawOutcome.Mistakes -> stringResource(R.string.draw_outcome_mistakes) to Color(0xFFEF6C00)
        DrawOutcome.NotRecognized -> {
            val text = if (s.drawn.isEmpty()) {
                stringResource(R.string.draw_outcome_here_is_kanji)
            } else {
                s.lookalike?.let {
                    if (it.title == null) stringResource(R.string.draw_outcome_lookalike_no_title, it.kanji)
                    else stringResource(R.string.draw_outcome_lookalike, it.kanji, it.title)
                } ?: stringResource(R.string.draw_outcome_not_recognized)
            }
            text to wrongTextColor()
        }
    }
    Text(s.card.kanji + "  " + s.card.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    if (s.trace) {
        Text(
            stringResource(R.string.draw_trace_practice_only),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
    Text(headline, color = color, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    s.lookalike?.takeIf { it.hasCard }?.let { TextButton(onClick = { onOpenDoc("kanji/${it.kanji}.md") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.read_about, it.kanji)) } }
    val flaggedDrawn = if (showIssues) s.match.issues.mapNotNull { it.drawnIndex }.toSet() else emptySet()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Overlay(Modifier.weight(1f)) { YourDrawing(s.drawn, s.canvasPx, flaggedDrawn, rejected = s.outcome == DrawOutcome.NotRecognized) }
        Overlay(Modifier.weight(1f)) { Reference(s.reference, if (showIssues) s.match.flaggedRefStrokes else emptySet(), numbers = true) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.your_drawing), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
        Text(stringResource(R.string.correct_strokes), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
    if (showIssues) s.match.issues.forEach { StrokeIssueText(it) }
    if (!s.recognizerUsed && s.drawn.isNotEmpty()) Text(stringResource(R.string.graded_by_stroke_shapes_only), style = MaterialTheme.typography.bodySmall)
    Button(onClick = { vm.next() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.next)) }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    MarkdownView(s.card.body, "kanji/${s.card.kanji}.md", onOpenDoc)
}

@Composable
internal fun StrokeIssueText(issue: StrokeIssue) {
    val ref = (issue.refIndex ?: 0) + 1
    val drawn = (issue.drawnIndex ?: 0) + 1
    val text = when (issue.type) {
        IssueType.Reversed -> stringResource(R.string.draw_stroke_reversed, ref)
        IssueType.WrongOrder -> stringResource(R.string.draw_stroke_wrong_order, ref, drawn)
        IssueType.WrongShape -> if (issue.faults.isEmpty()) {
            stringResource(R.string.draw_stroke_wrong_shape, ref)
        } else {
            val faults = issue.faults.map { stringResource(shapeFaultLabel(it)) }.joinToString(", ")
            stringResource(R.string.draw_stroke_shape_faults, ref, faults)
        }
        IssueType.Joined -> stringResource(R.string.draw_stroke_joined, ref, ref + 1)
        IssueType.Broken -> stringResource(R.string.draw_stroke_broken, ref)
        IssueType.Missing -> stringResource(R.string.draw_stroke_missing, ref)
        IssueType.Extra -> stringResource(R.string.draw_stroke_extra, drawn)
        IssueType.MissingHook -> stringResource(R.string.draw_stroke_missing_hook, ref)
        IssueType.ExtraHook -> stringResource(R.string.draw_stroke_extra_hook, ref)
    }
    Text(stringResource(R.string.bullet_prefix) + text, color = wrongTextColor())
}

private val shapeFaultLabels = mapOf(
    ShapeFault.TooLow to R.string.shape_fault_too_low,
    ShapeFault.TooHigh to R.string.shape_fault_too_high,
    ShapeFault.TooFarLeft to R.string.shape_fault_too_far_left,
    ShapeFault.TooFarRight to R.string.shape_fault_too_far_right,
    ShapeFault.TooShort to R.string.shape_fault_too_short,
    ShapeFault.TooLong to R.string.shape_fault_too_long,
    ShapeFault.SlopesDown to R.string.shape_fault_slopes_down,
    ShapeFault.SlopesUp to R.string.shape_fault_slopes_up,
    ShapeFault.LeansRight to R.string.shape_fault_leans_right,
    ShapeFault.LeansLeft to R.string.shape_fault_leans_left,
    ShapeFault.TooSteep to R.string.shape_fault_too_steep,
    ShapeFault.TooFlat to R.string.shape_fault_too_flat,
    ShapeFault.TooCurved to R.string.shape_fault_too_curved,
    ShapeFault.TooStraight to R.string.shape_fault_too_straight,
)

private fun shapeFaultLabel(fault: ShapeFault): Int = shapeFaultLabels.getValue(fault)

@Composable
internal fun Overlay(modifier: Modifier, draw: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(modifier.aspectRatio(1f).border(1.dp, MaterialTheme.colorScheme.outline).background(Color.White)) { draw() }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun DrawingPad(
    strokes: List<List<TimedPt>>,
    onStroke: (List<TimedPt>) -> Unit,
    onSize: (Float) -> Unit,
    modifier: Modifier,
    ghost: List<Stroke>? = null,
    subject: Int = R.string.drawing_subject_kanji,
) {
    val current = remember { mutableStateListOf<TimedPt>() }
    val brush = LocalBrush.current
    val look = LocalBrushLook.current
    val measurer = rememberTextMeasurer()
    val context = LocalContext.current
    val labels = remember(ghost) { ghost?.let { strokeNumberPositions(it) } }
    val subjectLabel = stringResource(subject)
    Canvas(
        modifier
            .border(2.dp, MaterialTheme.colorScheme.outline)
            .background(Color.White)
            .onSizeChanged { onSize(it.width.toFloat()) }
            .clipToBounds()
            .semantics { contentDescription = context.getString(R.string.drawing_area_description, subjectLabel, strokes.size) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    current.clear()
                    current += TimedPt(down.position.x.coerceIn(0f, size.width.toFloat()), down.position.y.coerceIn(0f, size.height.toFloat()), down.uptimeMillis)
                    drag(down.id) { change ->
                        change.consume()
                        // Fast strokes arrive batched: keep the intermediate samples too.
                        change.historical.forEach { current += TimedPt(it.position.x.coerceIn(0f, size.width.toFloat()), it.position.y.coerceIn(0f, size.height.toFloat()), it.uptimeMillis) }
                        current += TimedPt(change.position.x.coerceIn(0f, size.width.toFloat()), change.position.y.coerceIn(0f, size.height.toFloat()), change.uptimeMillis)
                    }
                    if (current.isNotEmpty()) onStroke(current.toList())
                    current.clear()
                }
            },
    ) {
        val guide = Color(0xFFE0E0E0)
        drawLine(guide, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2f)
        drawLine(guide, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f)
        if (ghost != null && labels != null) {
            val k = size.width / 109f
            ghost.forEachIndexed { i, s ->
                drawBrushStroke(s.map { Offset(it.x.toFloat() * k, it.y.toFloat() * k) }, look.color.copy(alpha = 0.22f), brush, look = look)
                val layout = measurer.measure("${i + 1}", TextStyle(color = Color(0xFF9FA8DA), fontSize = 15.sp))
                drawText(layout, topLeft = labels[i] * k - Offset(layout.size.width / 2f, layout.size.height / 2f))
            }
        }
        (strokes + listOf(current.toList())).forEach { s -> drawBrushStroke(s.map { Offset(it.x, it.y) }, look.color, brush, look = look) }
    }
}

@Composable
internal fun YourDrawing(drawn: List<List<TimedPt>>, canvasPx: Float, flagged: Set<Int>, rejected: Boolean) {
    // Show the slightly regularised strokes: kinks too small to matter in writing are dropped.
    val tidy = remember(drawn) { regularize(drawn.map { s -> s.map { Pt(it.x.toDouble(), it.y.toDouble()) } }, 0.02, 48, curved = true) }
    val brush = LocalBrush.current
    val look = LocalBrushLook.current
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
        val k = size.width / canvasPx
        tidy.forEachIndexed { i, s ->
            drawBrushStroke(s.map { Offset(it.x.toFloat() * k, it.y.toFloat() * k) }, if (rejected || i in flagged) Bad else look.color, brush, look = look)
        }
    }
}

@Composable
internal fun Reference(source: List<Stroke>, flagged: Set<Int>, numbers: Boolean) {
    val reference = remember(source) { varyReference(source, kotlin.random.Random.nextInt()) }
    val measurer = rememberTextMeasurer()
    val brush = LocalBrush.current
    val look = LocalBrushLook.current
    val labels = remember(reference) { strokeNumberPositions(reference) }
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
        val k = size.width / 109f
        reference.forEachIndexed { i, s ->
            val c = if (i in flagged) Bad else Good
            drawBrushStroke(s.map { Offset(it.x.toFloat() * k, it.y.toFloat() * k) }, c, brush, look = look)
            if (numbers) {
                val layout = measurer.measure("${i + 1}", TextStyle(color = c, fontSize = 15.sp))
                val centre = labels[i] * k
                drawText(layout, topLeft = centre - Offset(layout.size.width / 2f, layout.size.height / 2f))
            }
        }
    }
}

/**
 * Where to put each stroke number (in 109-unit reference space): next to the stroke start, on the side
 * with the most free room, so a number never sits on top of any stroke or another number.
 */
internal fun strokeNumberPositions(strokes: List<Stroke>): List<Offset> {
    val ink = strokes.flatMap { s -> densify(s.map { Offset(it.x.toFloat(), it.y.toFloat()) }, 1.5f) }
    val placed = ArrayList<Offset>()
    return strokes.map { s ->
        val start = Offset(s.first().x.toFloat(), s.first().y.toFloat())
        val ahead = s.getOrNull(minOf(3, s.size - 1)) ?: s.first()
        val back = kotlin.math.atan2(start.y - ahead.y.toFloat(), start.x - ahead.x.toFloat())
        var best = start
        var bestScore = -Float.MAX_VALUE
        for (radius in listOf(6f, 8f, 10f)) {
            for (step in 0 until 16) {
                val angle = step * (2 * Math.PI / 16)
                val cand = Offset(start.x + radius * kotlin.math.cos(angle).toFloat(), start.y + radius * kotlin.math.sin(angle).toFloat())
                if (cand.x < 4f || cand.y < 4f || cand.x > 105f || cand.y > 105f) continue
                val clearance = minOf(ink.minOf { (it - cand).getDistance() }, placed.minOfOrNull { (it - cand).getDistance() * 0.8f } ?: 99f)
                val away = kotlin.math.cos(angle - back).toFloat()
                val score = minOf(clearance, 6f) * 2f + away - radius * 0.1f
                if (score > bestScore) { bestScore = score; best = cand }
            }
        }
        placed += best
        best
    }
}