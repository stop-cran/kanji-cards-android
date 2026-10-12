package io.github.stopcran.kanji.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.R

/**
 * A home entry with a faint illustration behind it. The picture is decorative; a scrim fading to the card colour keeps the
 * caption readable and leaves the right edge (status, chevron) on plain paper.
 */
@Composable
fun ArtCard(title: String, summary: String, @DrawableRes art: Int?, onClick: () -> Unit) {
    val container = MaterialTheme.colorScheme.surfaceContainerHighest
    Card(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Box {
            if (art != null) {
                // The art is transparent ink on no paper, so it blends into the card colour; it sits right, under a fade from the text side.
                Image(
                    painterResource(art), null, Modifier.matchParentSize(),
                    alignment = Alignment.CenterEnd, contentScale = BandCrop, alpha = ART_ALPHA,
                )
                Box(
                    Modifier.matchParentSize().background(
                        Brush.horizontalGradient(
                            0f to container.copy(alpha = 0.9f),
                            0.4f to container.copy(alpha = 0.6f),
                            0.75f to Color.Transparent,
                        ),
                    ),
                )
            }
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
                // A soft halo in the card colour keeps the chevron distinct from the picture and fades out around it.
                // It is drawn larger than the layout box so it does not make the card taller.
                Box(
                    Modifier.size(CHEVRON_BOX).drawBehind {
                        drawCircle(Brush.radialGradient(0.45f to container, 1f to Color.Transparent, radius = HALO_RADIUS.toPx()), HALO_RADIUS.toPx())
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.chevron), modifier = Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
    }
}

/** Crop, but never magnify beyond twice the card height, so a very wide card keeps the whole subject (the art is transparent). */
private val BandCrop = object : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        val crop = maxOf(dstSize.width / srcSize.width, dstSize.height / srcSize.height)
        val s = minOf(crop, BAND_MAX_HEIGHT_RATIO * dstSize.height / srcSize.height)
        return ScaleFactor(s, s)
    }
}

private const val BAND_MAX_HEIGHT_RATIO = 2f
private const val ART_ALPHA = 0.55f
private val CHEVRON_BOX = 32.dp
private val HALO_RADIUS = 32.dp
