package io.github.stopcran.kanji.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.stopcran.kanji.core.listening.TaskChoiceMode
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun ListeningTaskPicture(vm: ListeningTaskViewModel, bitmap: ImageBitmap, required: Boolean, modifier: Modifier) {
    ListeningTaskPicture(bitmap, required, vm.task?.choiceMode, modifier, Modifier.listeningEvidence(vm)) {
        val state = vm.attempt
        if (vm.playing) Button(onClick = { vm.pause() }) { Text("Pause audio") }
        else if (state?.phase == "listen" || state?.wholeReplayInProgress == true) {
            Button(onClick = { vm.play() }, enabled = !vm.busy && vm.error == null) { Text("Continue audio") }
        }
    }
}

@Composable
internal fun ListeningTaskPicture(
    bitmap: ImageBitmap, required: Boolean, choiceMode: TaskChoiceMode?, modifier: Modifier, evidence: Modifier,
    onEnlarge: () -> Unit = {}, enlargementLabel: String = "Enlarge picture", controls: @Composable () -> Unit = {},
) {
    var enlarged by rememberSaveable { mutableStateOf(false) }
    val description = when {
        !required -> "Context picture. Described study is available in Listening."
        choiceMode == TaskChoiceMode.Visual -> "Essential numbered picture. Described study is available in Listening."
        else -> "Essential scene picture. Described study is available in Listening."
    }
    Column(modifier) {
        Image(bitmap, description, Modifier.weight(1f).fillMaxWidth().testTag("listening-task-picture").then(evidence),
            contentScale = ContentScale.Fit)
        TextButton(onClick = { onEnlarge(); enlarged = true }) { Text(enlargementLabel) }
    }
    if (enlarged) Dialog(onDismissRequest = { enlarged = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Page("Task picture", { enlarged = false }, scrollable = false, maxWidth = 1100.dp) {
                ZoomableTaskPicture(bitmap, description, Modifier.weight(1f)) {
                    controls()
                    TextButton(onClick = { enlarged = false }) { Text("Return to task") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoomableTaskPicture(bitmap: ImageBitmap, description: String, modifier: Modifier, controls: @Composable () -> Unit) {
    var zoom by rememberSaveable { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    fun pan(delta: Offset) { offset = boundedPictureOffset(bitmap, viewport, zoom, offset + delta) }
    fun magnify(value: Float) {
        zoom = value.coerceIn(1f, 4f)
        offset = boundedPictureOffset(bitmap, viewport, zoom, offset)
    }
    val transform = rememberTransformableState { scale, delta, _ -> magnify(zoom * scale); pan(delta) }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { viewport = it }.testTag("listening-picture-zoom")
            .transformable(transform).semantics {
                stateDescription = "${(zoom * 100).roundToInt()} percent"
                customActions = listOf(
                    CustomAccessibilityAction("Move picture left") { pan(Offset(-viewport.width / 4f, 0f)); true },
                    CustomAccessibilityAction("Move picture right") { pan(Offset(viewport.width / 4f, 0f)); true },
                    CustomAccessibilityAction("Move picture up") { pan(Offset(0f, -viewport.height / 4f)); true },
                    CustomAccessibilityAction("Move picture down") { pan(Offset(0f, viewport.height / 4f)); true },
                )
            }) {
            val bounded = boundedPictureOffset(bitmap, viewport, zoom, offset)
            Image(bitmap, description, Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom; scaleY = zoom; translationX = bounded.x; translationY = bounded.y
            }, contentScale = ContentScale.Fit)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { magnify(zoom + 0.5f) }, enabled = zoom < 4f) { Text("Zoom in") }
            TextButton(onClick = { magnify(zoom - 0.5f) }, enabled = zoom > 1f) { Text("Zoom out") }
            TextButton(onClick = { magnify(1f); offset = Offset.Zero }) { Text("Reset") }
            controls()
        }
    }
}

private fun boundedPictureOffset(bitmap: ImageBitmap, viewport: IntSize, zoom: Float, offset: Offset): Offset {
    val fit = min(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
    val x = ((bitmap.width * fit * zoom - viewport.width) / 2).coerceAtLeast(0f)
    val y = ((bitmap.height * fit * zoom - viewport.height) / 2).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-x, x), offset.y.coerceIn(-y, y))
}
