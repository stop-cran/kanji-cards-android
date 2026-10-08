package io.github.stopcran.kanji.ui

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
import io.github.stopcran.kanji.core.draw.Pt
import io.github.stopcran.kanji.core.draw.regularize
import io.github.stopcran.kanji.core.draw.varyReference
import io.github.stopcran.kanji.core.draw.Stroke
import io.github.stopcran.kanji.core.draw.describe

private val Good = Color(0xFF2E7D32)
private val Bad = Color(0xFFC62828)
private val Ink = Color(0xFF1A237E)

@Composable
fun DrawScreen(
    sessionKey: String,
    onBack: () -> Unit,
    onOpenDoc: (String) -> Unit,
    onPracticeMore: () -> Unit,
    vm: DrawViewModel = viewModel(key = sessionKey),
) {
    LaunchedEffect(sessionKey) { vm.ensureStarted(sessionKey.endsWith(":extra")) }
    val app = LocalContext.current.applicationContext as KanjiApp
    val brush by app.settings.brush.collectAsState()
    androidx.compose.runtime.CompositionLocalProvider(LocalBrush provides brush, LocalBrushLook provides vm.look) {
    Page("Drawing", onBack) {
        when (val s = vm.ui) {
            DrawUi.Loading -> Text("Loading…")
            is DrawUi.Empty -> Text(s.message)
            is DrawUi.Done -> {
                Text("Session complete", style = MaterialTheme.typography.headlineSmall)
                Text("${s.clean} of ${s.answered} drawn cleanly.")
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                OutlinedButton(onClick = onPracticeMore, modifier = Modifier.fillMaxWidth()) { Text("Practice more") }
            }
            is DrawUi.Question -> QuestionState(s, vm)
            is DrawUi.Checking -> Text("Checking…")
            is DrawUi.Answer -> AnswerState(s, vm, onOpenDoc)
        }
    }
    }
}

@Composable
private fun QuestionState(s: DrawUi.Question, vm: DrawViewModel) {
    val strokes = remember(s.seq, s.card.kanji) { mutableStateListOf<List<TimedPt>>() }
    var size by remember { mutableStateOf(1f) }
    Text("${s.remaining} left", style = MaterialTheme.typography.labelMedium)
    Text("Draw the kanji for", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Text(s.card.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    DrawingPad(strokes, onStroke = { strokes += it }, onSize = { size = it }, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
    when (vm.modelState) {
        ModelState.Downloading -> Text("Downloading the handwriting model (first use only)…", style = MaterialTheme.typography.bodySmall)
        ModelState.Unavailable -> Text("Handwriting model unavailable: graded by stroke shapes only.", style = MaterialTheme.typography.bodySmall)
        else -> {}
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) { Text("Undo") }
        OutlinedButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text("Clear") }
        Button(onClick = { vm.check(strokes.toList(), size) }, enabled = strokes.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Check") }
    }
    OutlinedButton(onClick = { vm.check(emptyList(), size) }, modifier = Modifier.fillMaxWidth()) { Text("I don't remember") }
}

@Composable
private fun AnswerState(s: DrawUi.Answer, vm: DrawViewModel, onOpenDoc: (String) -> Unit) {
    val (headline, color) = when (s.outcome) {
        DrawOutcome.Clean -> "Well drawn!" to Good
        DrawOutcome.Mistakes -> "Recognised, with stroke mistakes" to Color(0xFFEF6C00)
        DrawOutcome.NotRecognized -> (if (s.drawn.isEmpty()) "Here is the kanji" else "Not recognised") to Bad
    }
    Text(s.card.kanji + "  " + s.card.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Text(headline, color = color, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    val flaggedDrawn = s.match.issues.mapNotNull { it.drawnIndex }.toSet()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Overlay(Modifier.weight(1f)) { YourDrawing(s.drawn, s.canvasPx, flaggedDrawn) }
        Overlay(Modifier.weight(1f)) { Reference(s.reference, s.match.flaggedRefStrokes, numbers = true) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your drawing", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
        Text("Correct strokes", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
    s.match.issues.forEach { Text("• " + it.describe(), color = Bad) }
    if (!s.recognizerUsed && s.drawn.isNotEmpty()) Text("Graded by stroke shapes only (handwriting model not available).", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { vm.next() }, modifier = Modifier.fillMaxWidth()) { Text(if (s.outcome == DrawOutcome.NotRecognized) "Next (will repeat)" else "Next") }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    MarkdownView(s.card.body, "kanji/${s.card.kanji}.md", onOpenDoc)
}

@Composable
private fun Overlay(modifier: Modifier, draw: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(modifier.aspectRatio(1f).border(1.dp, MaterialTheme.colorScheme.outline).background(Color.White)) { draw() }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun DrawingPad(strokes: List<List<TimedPt>>, onStroke: (List<TimedPt>) -> Unit, onSize: (Float) -> Unit, modifier: Modifier) {
    val current = remember { mutableStateListOf<TimedPt>() }
    val brush = LocalBrush.current
    val look = LocalBrushLook.current
    Canvas(
        modifier
            .border(2.dp, MaterialTheme.colorScheme.outline)
            .background(Color.White)
            .onSizeChanged { onSize(it.width.toFloat()) }
            .clipToBounds()
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
        (strokes + listOf(current.toList())).forEach { s -> drawBrushStroke(s.map { Offset(it.x, it.y) }, Ink, brush, look = look) }
    }
}

@Composable
private fun YourDrawing(drawn: List<List<TimedPt>>, canvasPx: Float, flagged: Set<Int>) {
    // Show the slightly regularised strokes: kinks too small to matter in writing are dropped.
    val tidy = remember(drawn) { regularize(drawn.map { s -> s.map { Pt(it.x.toDouble(), it.y.toDouble()) } }, 0.02, 48, curved = true) }
    val brush = LocalBrush.current
    val look = LocalBrushLook.current
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
        val k = size.width / canvasPx
        tidy.forEachIndexed { i, s ->
            drawBrushStroke(s.map { Offset(it.x.toFloat() * k, it.y.toFloat() * k) }, if (i in flagged) Bad else Ink, brush, look = look)
        }
    }
}

@Composable
private fun Reference(source: List<Stroke>, flagged: Set<Int>, numbers: Boolean) {
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